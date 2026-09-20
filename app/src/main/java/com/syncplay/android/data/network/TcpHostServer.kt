package com.syncplay.android.data.network

import android.util.Log
import com.syncplay.android.data.model.ConnectedDevice
import com.syncplay.android.data.model.ProtocolMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Concurrent TCP host that accepts multiple client sockets, runs HELLO/WELCOME handshake,
 * maintains per-client heartbeat (PING/PONG), and answers Phase 2 NTP [ProtocolMessage.SyncReq]
 * packets with low-latency [ProtocolMessage.SyncRes] (T2/T3).
 *
 * Instances are single-use: create a new [TcpHostServer] after [stop].
 */
class TcpHostServer(
    private val hostId: String,
    private val hostName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tag = "SyncPlayTcpHost"
    private val sessionId: String = UUID.randomUUID().toString()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + ioDispatcher)
    private val clientPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "syncplay-client-${System.nanoTime()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null

    private val clients = ConcurrentHashMap<String, ClientSession>()

    @Volatile
    private var activeAudioSession: ProtocolMessage.AudioSession? = null

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val boundPort: Int?
        get() = serverSocket?.localPort?.takeIf { it > 0 }

    data class StartResult(val port: Int, val sessionId: String)

    suspend fun start(preferredPort: Int = NetworkConstants.DEFAULT_TCP_PORT): StartResult =
        withContext(ioDispatcher) {
            check(running.compareAndSet(false, true)) { "Host server already running" }

            try {
                val socket = tryBind(preferredPort)
                serverSocket = socket
                _isRunning.value = true

                acceptJob = scope.launch { acceptLoop(socket) }
                heartbeatJob = scope.launch { heartbeatLoop() }

                Log.i(tag, "Host listening on port ${socket.localPort} session=$sessionId")
                StartResult(port = socket.localPort, sessionId = sessionId)
            } catch (t: Throwable) {
                running.set(false)
                _isRunning.value = false
                throw t
            }
        }

    fun stop() {
        if (!running.getAndSet(false) && serverSocket == null && clients.isEmpty()) return
        _isRunning.value = false
        activeAudioSession = null

        clients.values.forEach { session ->
            runCatching { session.close() }
        }
        clients.clear()
        publishDevices()

        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        heartbeatJob?.cancel()
        scope.cancel()
        runCatching { clientPool.close() }
        Log.i(tag, "Host server stopped")
    }

    /**
     * Announces a live UDP audio session to every connected client (and to future joiners).
     */
    fun broadcastAudioSession(session: ProtocolMessage.AudioSession) {
        activeAudioSession = session
        scope.launch {
            clients.values.forEach { client ->
                runCatching { client.send(session) }
            }
        }
    }

    fun broadcastAudioStop(reason: String = "host_stopped") {
        activeAudioSession = null
        val stop = ProtocolMessage.AudioStop(reason = reason)
        scope.launch {
            clients.values.forEach { client ->
                runCatching { client.send(stop) }
            }
        }
    }

    fun broadcast(message: ProtocolMessage) {
        scope.launch {
            clients.values.forEach { client ->
                runCatching { client.send(message) }
            }
        }
    }

    private fun tryBind(preferredPort: Int): ServerSocket {
        return try {
            ServerSocket(preferredPort).apply {
                reuseAddress = true
                soTimeout = 0
            }
        } catch (_: Exception) {
            // Fall back to an ephemeral port if 9090 is taken.
            ServerSocket(0).apply {
                reuseAddress = true
                soTimeout = 0
            }
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (running.get() && scope.isActive) {
            try {
                if (clients.size >= NetworkConstants.MAX_CLIENTS) {
                    delay(200)
                    continue
                }
                val clientSocket = withContext(ioDispatcher) { server.accept() }
                scope.launch(clientPool) { handleClient(clientSocket) }
            } catch (_: SocketException) {
                if (!running.get()) break
            } catch (t: Throwable) {
                if (!running.get()) break
                Log.e(tag, "Accept loop error", t)
                delay(100)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = NetworkConstants.SOCKET_SO_TIMEOUT_MS

        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val writeMutex = Mutex()

        val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
        val remotePort = socket.port

        try {
            val helloLine = withContext(ioDispatcher) { reader.readLine() }
                ?: throw IllegalStateException("Client closed before HELLO")
            val hello = ProtocolCodec.decode(helloLine) as? ProtocolMessage.Hello
                ?: throw IllegalStateException("Expected HELLO, got: $helloLine")

            val session = ClientSession(
                deviceId = hello.deviceId,
                deviceName = hello.deviceName,
                ipAddress = remoteIp,
                port = remotePort,
                socket = socket,
                reader = reader,
                writer = writer,
                writeMutex = writeMutex,
            )
            clients[hello.deviceId] = session
            publishDevices()

            session.send(
                ProtocolMessage.Welcome(
                    hostId = hostId,
                    hostName = hostName,
                    sessionId = sessionId,
                )
            )

            // If audio is already streaming, push the session config to late joiners.
            activeAudioSession?.let { audio ->
                runCatching { session.send(audio) }
            }

            Log.i(tag, "Client connected: ${hello.deviceName} ($remoteIp)")

            while (running.get() && socket.isConnected && !socket.isClosed) {
                val line = withContext(ioDispatcher) {
                    try {
                        reader.readLine()
                    } catch (_: java.net.SocketTimeoutException) {
                        null // keep looping; heartbeat job owns liveness
                    }
                } ?: continue

                when (val message = ProtocolCodec.decode(line)) {
                    is ProtocolMessage.Pong -> {
                        val rtt = System.currentTimeMillis() - message.sentAtEpochMs
                        session.lastHeartbeatEpochMs = System.currentTimeMillis()
                        session.roundTripMs = rtt
                        publishDevices()
                    }
                    is ProtocolMessage.Ping -> {
                        session.send(
                            ProtocolMessage.Pong(
                                sequence = message.sequence,
                                sentAtEpochMs = message.sentAtEpochMs,
                            )
                        )
                        session.lastHeartbeatEpochMs = System.currentTimeMillis()
                        publishDevices()
                    }
                    is ProtocolMessage.SyncReq -> {
                        // Stamp T2 on receipt; T3 is stamped inside sendSyncRes immediately before flush.
                        val t2 = System.currentTimeMillis()
                        session.sendSyncRes(
                            syncId = message.syncId,
                            t1 = message.t1,
                            t2 = t2,
                        )
                        session.lastHeartbeatEpochMs = System.currentTimeMillis()
                    }
                    is ProtocolMessage.Disconnect -> {
                        Log.i(tag, "Client ${hello.deviceName} disconnected: ${message.reason}")
                        break
                    }
                    else -> Log.d(tag, "Unhandled message from ${hello.deviceName}: $message")
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.w(tag, "Client session ended ($remoteIp): ${t.message}")
            }
        } finally {
            val removed = clients.entries.firstOrNull { it.value.socket === socket }?.key
            if (removed != null) {
                clients.remove(removed)?.close()
                publishDevices()
            } else {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun heartbeatLoop() {
        val sequence = AtomicLong(0)
        while (running.get() && scope.isActive) {
            delay(NetworkConstants.HEARTBEAT_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val snapshot = clients.values.toList()

            snapshot.forEach { session ->
                val age = now - session.lastHeartbeatEpochMs
                if (age > NetworkConstants.HEARTBEAT_TIMEOUT_MS) {
                    Log.w(tag, "Heartbeat timeout for ${session.deviceName}; dropping")
                    clients.remove(session.deviceId)
                    session.close()
                    publishDevices()
                    return@forEach
                }
                runCatching {
                    session.send(
                        ProtocolMessage.Ping(
                            sequence = sequence.incrementAndGet(),
                            sentAtEpochMs = System.currentTimeMillis(),
                        )
                    )
                }.onFailure {
                    clients.remove(session.deviceId)
                    session.close()
                    publishDevices()
                }
            }
        }
    }

    private fun publishDevices() {
        _connectedDevices.value = clients.values
            .map {
                ConnectedDevice(
                    deviceId = it.deviceId,
                    deviceName = it.deviceName,
                    ipAddress = it.ipAddress,
                    port = it.port,
                    connectedAtEpochMs = it.connectedAtEpochMs,
                    lastHeartbeatEpochMs = it.lastHeartbeatEpochMs,
                    roundTripMs = it.roundTripMs,
                )
            }
            .sortedBy { it.deviceName }
    }

    private class ClientSession(
        val deviceId: String,
        val deviceName: String,
        val ipAddress: String,
        val port: Int,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: BufferedWriter,
        private val writeMutex: Mutex,
        val connectedAtEpochMs: Long = System.currentTimeMillis(),
        @Volatile var lastHeartbeatEpochMs: Long = System.currentTimeMillis(),
        @Volatile var roundTripMs: Long? = null,
    ) {
        suspend fun send(message: ProtocolMessage) {
            writeMutex.withLock {
                writer.write(ProtocolCodec.encode(message))
                writer.newLine()
                writer.flush()
            }
        }

        /**
         * Low-latency NTP response: acquire the write lock, stamp T3, then flush immediately.
         */
        suspend fun sendSyncRes(syncId: Long, t1: Long, t2: Long) {
            writeMutex.withLock {
                val t3 = System.currentTimeMillis()
                writer.write(
                    ProtocolCodec.encode(
                        ProtocolMessage.SyncRes(
                            syncId = syncId,
                            t1 = t1,
                            t2 = t2,
                            t3 = t3,
                        )
                    )
                )
                writer.newLine()
                writer.flush()
            }
        }

        fun close() {
            runCatching { writer.close() }
            runCatching { reader.close() }
            runCatching { socket.close() }
        }
    }
}
