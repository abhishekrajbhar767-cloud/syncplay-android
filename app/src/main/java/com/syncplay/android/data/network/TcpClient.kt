package com.syncplay.android.data.network

import android.util.Log
import com.syncplay.android.data.model.ProtocolMessage
import com.syncplay.android.data.sync.TimeSyncManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent TCP client that connects to a discovered host, completes HELLO/WELCOME,
 * answers heartbeat PINGs, and drives Phase 2 NTP clock sync via SYNC_REQ / SYNC_RES.
 */
class TcpClient(
    private val deviceId: String,
    private val deviceName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tag = "SyncPlayTcpClient"

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val running = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val pingSequence = AtomicLong(0)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastTrafficEpochMs: Long = 0L

    private val _connectionState = MutableStateFlow<ClientConnectionState>(ClientConnectionState.Disconnected)
    val connectionState: StateFlow<ClientConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    sealed interface ClientConnectionState {
        data object Disconnected : ClientConnectionState
        data class Connecting(val hostAddress: String, val port: Int) : ClientConnectionState
        data class Connected(
            val hostId: String,
            val hostName: String,
            val hostAddress: String,
            val sessionId: String,
            val roundTripMs: Long? = null,
        ) : ClientConnectionState
        data class Failed(val message: String) : ClientConnectionState
    }

    sealed interface ClientEvent {
        data class Welcome(val hostName: String, val sessionId: String) : ClientEvent
        data class RoundTrip(val millis: Long) : ClientEvent
        data class ClockSynced(val offsetMs: Long, val rttMs: Long) : ClientEvent
        data class Disconnected(val reason: String) : ClientEvent
    }

    suspend fun connect(hostAddress: String, port: Int) = withContext(ioDispatcher) {
        check(running.compareAndSet(false, true)) { "Client already connecting/connected" }
        _connectionState.value = ClientConnectionState.Connecting(hostAddress, port)

        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = NetworkConstants.SOCKET_SO_TIMEOUT_MS
            sock.connect(
                InetSocketAddress(hostAddress, port),
                NetworkConstants.SOCKET_CONNECT_TIMEOUT_MS,
            )

            val bufferedReader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val bufferedWriter = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))

            socket = sock
            reader = bufferedReader
            writer = bufferedWriter
            lastTrafficEpochMs = System.currentTimeMillis()

            sendLocked(
                ProtocolMessage.Hello(
                    deviceId = deviceId,
                    deviceName = deviceName,
                )
            )

            val welcomeLine = bufferedReader.readLine()
                ?: throw IllegalStateException("Host closed before WELCOME")
            val welcome = ProtocolCodec.decode(welcomeLine) as? ProtocolMessage.Welcome
                ?: throw IllegalStateException("Expected WELCOME, got: $welcomeLine")

            _connectionState.value = ClientConnectionState.Connected(
                hostId = welcome.hostId,
                hostName = welcome.hostName,
                hostAddress = hostAddress,
                sessionId = welcome.sessionId,
            )
            _events.tryEmit(ClientEvent.Welcome(welcome.hostName, welcome.sessionId))
            Log.i(tag, "Connected to host ${welcome.hostName} @$hostAddress:$port")

            // Phase 2: bind TimeSyncManager to this socket and start 3–5s NTP loop.
            TimeSyncManager.becomeClient(
                TimeSyncManager.SyncRequestSender { syncId, t1 ->
                    sendLocked(ProtocolMessage.SyncReq(syncId = syncId, t1 = t1))
                }
            )
            TimeSyncManager.startPeriodicSync(scope)

            readJob = scope.launch { readLoop() }
            watchdogJob = scope.launch { watchdogLoop() }
        } catch (t: Throwable) {
            Log.e(tag, "Connect failed", t)
            cleanup(emitFailed = true, message = t.message ?: "Connection failed")
            throw t
        }
    }

    fun disconnect(reason: String = "client_left") {
        if (!running.get()) return
        scope.launch {
            runCatching {
                sendLocked(ProtocolMessage.Disconnect(reason = reason))
            }
            cleanup(emitFailed = false, message = reason)
        }
    }

    private suspend fun readLoop() {
        val bufferedReader = reader ?: return
        try {
            while (running.get() && scope.isActive) {
                val line = try {
                    withContext(ioDispatcher) { bufferedReader.readLine() }
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } ?: break

                lastTrafficEpochMs = System.currentTimeMillis()
                when (val message = ProtocolCodec.decode(line)) {
                    is ProtocolMessage.Ping -> {
                        sendLocked(
                            ProtocolMessage.Pong(
                                sequence = message.sequence,
                                sentAtEpochMs = message.sentAtEpochMs,
                            )
                        )
                    }
                    is ProtocolMessage.Pong -> {
                        val rtt = System.currentTimeMillis() - message.sentAtEpochMs
                        val current = _connectionState.value
                        if (current is ClientConnectionState.Connected) {
                            _connectionState.value = current.copy(roundTripMs = rtt)
                        }
                        _events.tryEmit(ClientEvent.RoundTrip(rtt))
                    }
                    is ProtocolMessage.SyncRes -> {
                        // T4 is stamped inside TimeSyncManager.onSyncResponse for accuracy.
                        TimeSyncManager.onSyncResponse(
                            syncId = message.syncId,
                            t1 = message.t1,
                            t2 = message.t2,
                            t3 = message.t3,
                        )
                        val sync = TimeSyncManager.state.value
                        if (sync.isSynced && sync.rttMs != null) {
                            _events.tryEmit(
                                ClientEvent.ClockSynced(
                                    offsetMs = sync.offsetMs,
                                    rttMs = sync.rttMs,
                                )
                            )
                        }
                    }
                    is ProtocolMessage.Disconnect -> {
                        cleanup(emitFailed = false, message = message.reason)
                        break
                    }
                    is ProtocolMessage.Error -> {
                        cleanup(emitFailed = true, message = message.message)
                        break
                    }
                    else -> Log.d(tag, "Unhandled host message: $message")
                }
            }
        } catch (_: SocketException) {
            // expected on shutdown
        } catch (t: Throwable) {
            Log.w(tag, "Read loop ended: ${t.message}")
        } finally {
            if (running.get()) {
                cleanup(emitFailed = false, message = "connection_lost")
            }
        }
    }

    private suspend fun watchdogLoop() {
        while (running.get() && scope.isActive) {
            delay(NetworkConstants.HEARTBEAT_INTERVAL_MS)
            val age = System.currentTimeMillis() - lastTrafficEpochMs
            if (age > NetworkConstants.HEARTBEAT_TIMEOUT_MS) {
                Log.w(tag, "No traffic for ${age}ms; treating as dead")
                cleanup(emitFailed = true, message = "Heartbeat timeout")
                break
            }
            // Optionally probe host if quiet; host normally drives PING.
            if (age > NetworkConstants.HEARTBEAT_INTERVAL_MS * 2) {
                runCatching {
                    sendLocked(
                        ProtocolMessage.Ping(
                            sequence = pingSequence.incrementAndGet(),
                            sentAtEpochMs = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    private suspend fun sendLocked(message: ProtocolMessage) {
        val bufferedWriter = writer ?: return
        writeMutex.withLock {
            bufferedWriter.write(ProtocolCodec.encode(message))
            bufferedWriter.newLine()
            bufferedWriter.flush()
        }
        lastTrafficEpochMs = System.currentTimeMillis()
    }

    private fun cleanup(emitFailed: Boolean, message: String) {
        if (!running.compareAndSet(true, false) &&
            _connectionState.value is ClientConnectionState.Disconnected
        ) {
            return
        }
        running.set(false)
        TimeSyncManager.stopSyncLoop()
        TimeSyncManager.reset()
        readJob?.cancel()
        watchdogJob?.cancel()
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null

        _connectionState.value = if (emitFailed) {
            ClientConnectionState.Failed(message)
        } else {
            ClientConnectionState.Disconnected
        }
        _events.tryEmit(ClientEvent.Disconnected(message))
        scope.cancel()
        Log.i(tag, "Client cleaned up ($message)")
    }
}
