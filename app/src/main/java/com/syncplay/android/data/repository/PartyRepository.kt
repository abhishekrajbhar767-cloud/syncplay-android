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
import com.syncplay.android.data.model.SpeakerChannel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates Phases 1–4: networking, clock sync, UDP audio, calibration, and spatial routing.
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

    private val _channelAssignments = MutableStateFlow<Map<String, SpeakerChannel>>(emptyMap())
    val channelAssignments: StateFlow<Map<String, SpeakerChannel>> = _channelAssignments.asStateFlow()

    private val _calibrationEnabled = MutableStateFlow(false)
    val calibrationEnabled: StateFlow<Boolean> = _calibrationEnabled.asStateFlow()

    private val _eightDEnabled = MutableStateFlow(false)
    val eightDEnabled: StateFlow<Boolean> = _eightDEnabled.asStateFlow()

    private val _clientSpeakerChannel = MutableStateFlow(SpeakerChannel.STEREO)
    val clientSpeakerChannel: StateFlow<SpeakerChannel> = _clientSpeakerChannel.asStateFlow()

    private val _clientCalibrationHint = MutableStateFlow(false)
    val clientCalibrationHint: StateFlow<Boolean> = _clientCalibrationHint.asStateFlow()

    /** True once MediaProjection system capture has been started this session. */
    private val _hasSystemCapture = MutableStateFlow(false)

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
                    val channels = _channelAssignments.value
                    _connectedDevices.value = devices.map { device ->
                        device.copy(
                            speakerChannel = channels[device.deviceId] ?: SpeakerChannel.STEREO,
                        )
                    }
                    // Drop assignments for disconnected peers.
                    _channelAssignments.update { map ->
                        map.filterKeys { id -> devices.any { it.deviceId == id } }
                    }
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
                    is TcpClient.ClientEvent.ChannelAssigned -> {
                        val channel = SpeakerChannel.fromWire(event.channel)
                        _clientSpeakerChannel.value = channel
                        audioPlayer?.setSpeakerChannel(channel)
                    }
                    is TcpClient.ClientEvent.CalibrationModeChanged -> {
                        _clientCalibrationHint.value = event.enabled
                        _audioStatusMessage.value = if (event.enabled) {
                            "Calibration beep — align speakers with the latency slider"
                        } else {
                            "Playing UDP audio"
                        }
                    }
                    is TcpClient.ClientEvent.EightDModeChanged -> {
                        // Informational; pan is applied on the host.
                    }
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
        _hasSystemCapture.value = true
        AudioCaptureService.start(appContext, resultCode, data)
    }

    fun stopHostAudioStreaming() {
        AudioCaptureService.stop(appContext)
    }

    fun setCalibrationBeepEnabled(enabled: Boolean) {
        if (_role.value != PartyRole.HOST) return
        if (enabled) {
            if (!_audioStreaming.value) {
                AudioCaptureService.startCalibrationOnly(appContext)
            } else {
                AudioCaptureService.setCalibrationMode(appContext, true)
            }
        } else {
            AudioCaptureService.setCalibrationMode(appContext, false)
            if (!_hasSystemCapture.value) {
                AudioCaptureService.stop(appContext)
            }
        }
    }

    fun setEightDEnabled(enabled: Boolean) {
        if (_role.value != PartyRole.HOST) return
        _eightDEnabled.value = enabled
        AudioCaptureService.setEightDMode(appContext, enabled)
        hostServer?.broadcast(ProtocolMessage.EightDMode(enabled = enabled))
    }

    fun assignSpeakerChannel(deviceId: String, channel: SpeakerChannel) {
        if (_role.value != PartyRole.HOST) return
        _channelAssignments.update { it + (deviceId to channel) }
        _connectedDevices.update { list ->
            list.map { device ->
                if (device.deviceId == deviceId) device.copy(speakerChannel = channel) else device
            }
        }
        hostServer?.sendTo(
            deviceId,
            ProtocolMessage.ChannelAssign(deviceId = deviceId, channel = channel.name),
        )
        if (_audioStreaming.value) {
            AudioCaptureService.refreshTargets(appContext)
        }
    }

    fun onHostAudioStreamingStarted() {
        _audioStreaming.value = true
        val status = if (_calibrationEnabled.value) {
            "Calibration beep streaming (UDP :${AudioStreamConfig.UDP_PORT})"
        } else {
            "Streaming system audio (UDP :${AudioStreamConfig.UDP_PORT})"
        }
        _audioStatusMessage.value = status
        val session = ProtocolMessage.AudioSession(
            udpPort = AudioStreamConfig.UDP_PORT,
            sampleRate = AudioStreamConfig.SAMPLE_RATE_HZ,
            channelCount = AudioStreamConfig.CHANNEL_COUNT,
            presentationBufferMs = AudioStreamConfig.PRESENTATION_BUFFER_MS,
        )
        hostServer?.broadcastAudioSession(session)
        // Re-push channel assignments to late joiners / reconnects.
        _channelAssignments.value.forEach { (id, channel) ->
            hostServer?.sendTo(id, ProtocolMessage.ChannelAssign(deviceId = id, channel = channel.name))
        }
        if (_eightDEnabled.value) {
            hostServer?.broadcast(ProtocolMessage.EightDMode(enabled = true))
        }
        AudioCaptureService.refreshTargets(appContext)
    }

    fun onHostAudioStreamingStopped() {
        if (_audioStreaming.value) {
            hostServer?.broadcastAudioStop()
        }
        _audioStreaming.value = false
        _calibrationEnabled.value = false
        _audioStatusMessage.value = null
        _hasSystemCapture.value = false
    }

    fun onHostAudioStreamingFailed(message: String?) {
        _audioStreaming.value = false
        _calibrationEnabled.value = false
        _hasSystemCapture.value = false
        _audioStatusMessage.value = message ?: "Audio capture failed"
    }

    fun onCalibrationModeChanged(enabled: Boolean) {
        _calibrationEnabled.value = enabled
        hostServer?.broadcast(ProtocolMessage.CalibrationMode(enabled = enabled))
        if (_audioStreaming.value) {
            _audioStatusMessage.value = if (enabled) {
                "Calibration beep — clients adjust latency until clicks align"
            } else {
                "Streaming system audio (UDP :${AudioStreamConfig.UDP_PORT})"
            }
        }
    }

    fun onEightDModeChanged(enabled: Boolean) {
        _eightDEnabled.value = enabled
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
            it.setSpeakerChannel(_clientSpeakerChannel.value)
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
        _clientCalibrationHint.value = false
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
        _channelAssignments.value = emptyMap()
        _calibrationEnabled.value = false
        _eightDEnabled.value = false
        _hasSystemCapture.value = false
        _clientSpeakerChannel.value = SpeakerChannel.STEREO
        _role.value = PartyRole.NONE
        if (clearStatus) {
            _status.value = ConnectionStatus.Idle
        }
    }
}
