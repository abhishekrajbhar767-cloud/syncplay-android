package com.syncplay.android.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.syncplay.android.data.audio.AudioStreamConfig
import com.syncplay.android.data.audio.ScheduledAudioPlayer
import com.syncplay.android.data.audio.UdpAudioReceiver
import com.syncplay.android.data.model.ConnectedDevice
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.data.model.DiscoveredHost
import com.syncplay.android.data.model.PartyRole
import com.syncplay.android.data.model.ProtocolMessage
import com.syncplay.android.data.network.DeviceIdentity
import com.syncplay.android.data.network.MulticastLockManager
import com.syncplay.android.data.network.NetworkConstants
import com.syncplay.android.data.network.NsdHelper
import com.syncplay.android.data.network.TcpClient
import com.syncplay.android.data.network.TcpHostServer
import com.syncplay.android.data.sync.TimeSyncManager
import com.syncplay.android.service.AudioCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates Phase 1 networking, Phase 2 clock sync, and Phase 3 UDP audio streaming.
 */
class PartyRepository(context: Context) {
    private val tag = "SyncPlayRepo"
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val multicastLockManager = MulticastLockManager(appContext)
    private val nsdHelper = NsdHelper(appContext, multicastLockManager)

    val deviceId: String = DeviceIdentity.deviceId(appContext)
    val deviceName: String = DeviceIdentity.deviceName()

    private var hostServer: TcpHostServer? = null
    private var tcpClient: TcpClient? = null
    private var discoveryJob: Job? = null
    private var hostDevicesJob: Job? = null
    private var clientStateJob: Job? = null
    private var clientEventJob: Job? = null

    private var udpReceiver: UdpAudioReceiver? = null
    private var audioPlayer: ScheduledAudioPlayer? = null

    private val _role = MutableStateFlow(PartyRole.NONE)
    val role: StateFlow<PartyRole> = _role.asStateFlow()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private val _localAddresses = MutableStateFlow(DeviceIdentity.localIpv4Addresses())
    val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    private val _hostPort = MutableStateFlow<Int?>(null)
    val hostPort: StateFlow<Int?> = _hostPort.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    val timeSyncState: StateFlow<TimeSyncManager.SyncState> = TimeSyncManager.state

    private val _audioStreaming = MutableStateFlow(false)
    val audioStreaming: StateFlow<Boolean> = _audioStreaming.asStateFlow()

    private val _audioStatusMessage = MutableStateFlow<String?>(null)
    val audioStatusMessage: StateFlow<String?> = _audioStatusMessage.asStateFlow()

    private val _manualOffsetMs = MutableStateFlow(0)
    val manualOffsetMs: StateFlow<Int> = _manualOffsetMs.asStateFlow()

    private val _clientPlaybackActive = MutableStateFlow(false)
    val clientPlaybackActive: StateFlow<Boolean> = _clientPlaybackActive.asStateFlow()

    suspend fun startHosting() {
        stopAllInternal(clearStatus = false)
        _role.value = PartyRole.HOST
        _status.value = ConnectionStatus.Starting
        _localAddresses.value = DeviceIdentity.localIpv4Addresses()
        TimeSyncManager.becomeHost()

        val server = TcpHostServer(hostId = deviceId, hostName = deviceName)
        hostServer = server

        try {
            val started = server.start(NetworkConstants.DEFAULT_TCP_PORT)
            _hostPort.value = started.port
            _sessionId.value = started.sessionId

            hostDevicesJob = scope.launch {
                server.connectedDevices.collect { devices ->
                    _connectedDevices.value = devices
                    if (_audioStreaming.value) {
                        AudioCaptureService.refreshTargets(appContext)
                    }
                }
            }

            val registeredName = nsdHelper.registerService(
                serviceName = NetworkConstants.NSD_SERVICE_NAME,
                port = started.port,
                attributes = mapOf(
                    NetworkConstants.TXT_ATTR_DEVICE_NAME to deviceName,
                    NetworkConstants.TXT_ATTR_DEVICE_ID to deviceId,
                    NetworkConstants.TXT_ATTR_SESSION_ID to started.sessionId,
                ),
            )

            _status.value = ConnectionStatus.Hosting(
                serviceName = registeredName,
                port = started.port,
            )
            Log.i(tag, "Hosting as $registeredName on port ${started.port}")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start hosting", t)
            stopAllInternal(clearStatus = false)
            _status.value = ConnectionStatus.Failed(t.message ?: "Failed to host party")
            throw t
        }
    }

    fun startDiscovery() {
        stopAllInternal(clearStatus = false)
        _role.value = PartyRole.CLIENT
        _status.value = ConnectionStatus.Searching
        _discoveredHosts.value = emptyList()

        discoveryJob = scope.launch {
            try {
                nsdHelper.discoverHosts().collect { hosts ->
                    _discoveredHosts.value = hosts
                    if (tcpClient == null && hosts.isNotEmpty()) {
                        connectToHost(hosts.first())
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Discovery failed", t)
                _status.value = ConnectionStatus.Failed(t.message ?: "Discovery failed")
            }
        }
    }

    suspend fun connectToHost(host: DiscoveredHost) {
        if (tcpClient != null) return
        discoveryJob?.cancel()
        discoveryJob = null

        _status.value = ConnectionStatus.Connecting(host.hostName, host.hostAddress)
        val client = TcpClient(deviceId = deviceId, deviceName = deviceName)
        tcpClient = client

        clientStateJob = scope.launch {
            client.connectionState.collect { state ->
                when (state) {
                    is TcpClient.ClientConnectionState.Connected -> {
                        _sessionId.value = state.sessionId
                        _status.value = ConnectionStatus.Connected(
                            peerName = state.hostName,
                            peerAddress = state.hostAddress,
                            sessionId = state.sessionId,
                        )
                    }
                    is TcpClient.ClientConnectionState.Failed -> {
                        stopClientAudio()
                        _status.value = ConnectionStatus.Failed(state.message)
                    }
                    is TcpClient.ClientConnectionState.Disconnected -> {
                        stopClientAudio()
                        if (_role.value == PartyRole.CLIENT) {
                            _status.value = ConnectionStatus.Stopped
                        }
                    }
                    is TcpClient.ClientConnectionState.Connecting -> {
                        _status.value = ConnectionStatus.Connecting(
                            hostName = host.hostName,
                            hostAddress = state.hostAddress,
                        )
                    }
                }
            }
        }

        clientEventJob = scope.launch {
            client.events.collect { event ->
                when (event) {
                    is TcpClient.ClientEvent.AudioSessionStarted -> startClientAudio(event.session)
                    is TcpClient.ClientEvent.AudioSessionStopped -> stopClientAudio()
                    else -> Unit
                }
            }
        }

        try {
            client.connect(host.hostAddress, host.port)
        } catch (t: Throwable) {
            tcpClient = null
            _status.value = ConnectionStatus.Failed(t.message ?: "Could not connect to host")
            startDiscovery()
        }
    }

    /**
     * Starts Phase 3 host capture after the user grants MediaProjection.
     */
    fun startHostAudioStreaming(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _audioStatusMessage.value = "System audio capture requires Android 10+"
            return
        }
        if (_role.value != PartyRole.HOST) {
            _audioStatusMessage.value = "Host a party before streaming"
            return
        }
        _audioStatusMessage.value = "Starting capture…"
        AudioCaptureService.start(appContext, resultCode, data)
    }

    fun stopHostAudioStreaming() {
        AudioCaptureService.stop(appContext)
    }

    fun onHostAudioStreamingStarted() {
        _audioStreaming.value = true
        _audioStatusMessage.value = "Streaming system audio (UDP :${AudioStreamConfig.UDP_PORT})"
        val session = ProtocolMessage.AudioSession(
            udpPort = AudioStreamConfig.UDP_PORT,
            sampleRate = AudioStreamConfig.SAMPLE_RATE_HZ,
            channelCount = AudioStreamConfig.CHANNEL_COUNT,
            presentationBufferMs = AudioStreamConfig.PRESENTATION_BUFFER_MS,
        )
        hostServer?.broadcastAudioSession(session)
        AudioCaptureService.refreshTargets(appContext)
    }

    fun onHostAudioStreamingStopped() {
        if (_audioStreaming.value) {
            hostServer?.broadcastAudioStop()
        }
        _audioStreaming.value = false
        _audioStatusMessage.value = null
    }

    fun onHostAudioStreamingFailed(message: String?) {
        _audioStreaming.value = false
        _audioStatusMessage.value = message ?: "Audio capture failed"
    }

    fun setManualOffsetMs(offsetMs: Int) {
        val coerced = offsetMs.coerceIn(-200, 800)
        _manualOffsetMs.value = coerced
        audioPlayer?.setManualOffsetMs(coerced)
    }

    fun stopHosting() {
        stopAllInternal(clearStatus = false)
        _status.value = ConnectionStatus.Stopped
    }

    fun leaveParty() {
        stopAllInternal(clearStatus = false)
        _status.value = ConnectionStatus.Stopped
    }

    fun resetToIdle() {
        stopAllInternal(clearStatus = false)
        _status.value = ConnectionStatus.Idle
    }

    private fun startClientAudio(session: ProtocolMessage.AudioSession) {
        stopClientAudio()
        val player = ScheduledAudioPlayer().also {
            it.setManualOffsetMs(_manualOffsetMs.value)
            it.start()
        }
        audioPlayer = player

        val receiver = UdpAudioReceiver(port = session.udpPort) { packet ->
            player.enqueue(packet)
        }
        runCatching { receiver.start() }
            .onFailure {
                Log.e(tag, "UDP receiver failed", it)
                _audioStatusMessage.value = "UDP listen failed: ${it.message}"
                player.stop()
                audioPlayer = null
                return
            }
        udpReceiver = receiver
        _clientPlaybackActive.value = true
        _audioStatusMessage.value =
            "Playing UDP audio · PTS buffer ${session.presentationBufferMs} ms"
        Log.i(tag, "Client audio pipeline started on :${session.udpPort}")
    }

    private fun stopClientAudio() {
        runCatching { udpReceiver?.stop() }
        udpReceiver = null
        runCatching { audioPlayer?.stop() }
        audioPlayer = null
        _clientPlaybackActive.value = false
    }

    private fun stopAllInternal(clearStatus: Boolean) {
        discoveryJob?.cancel()
        discoveryJob = null
        hostDevicesJob?.cancel()
        hostDevicesJob = null
        clientStateJob?.cancel()
        clientStateJob = null
        clientEventJob?.cancel()
        clientEventJob = null

        if (_audioStreaming.value || _role.value == PartyRole.HOST) {
            runCatching { AudioCaptureService.stop(appContext) }
        }
        stopClientAudio()

        runCatching { nsdHelper.unregister() }
        runCatching { hostServer?.stop() }
        hostServer = null
        runCatching { tcpClient?.disconnect() }
        tcpClient = null
        TimeSyncManager.reset()

        _connectedDevices.value = emptyList()
        _discoveredHosts.value = emptyList()
        _hostPort.value = null
        _sessionId.value = null
        _audioStreaming.value = false
        _audioStatusMessage.value = null
        _role.value = PartyRole.NONE
        if (clearStatus) {
            _status.value = ConnectionStatus.Idle
        }
    }
}
