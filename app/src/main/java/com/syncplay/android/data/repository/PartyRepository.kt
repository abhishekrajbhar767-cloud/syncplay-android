package com.syncplay.android.data.repository

import android.content.Context
import android.util.Log
import com.syncplay.android.data.model.ConnectedDevice
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.data.model.DiscoveredHost
import com.syncplay.android.data.model.PartyRole
import com.syncplay.android.data.network.DeviceIdentity
import com.syncplay.android.data.network.MulticastLockManager
import com.syncplay.android.data.network.NetworkConstants
import com.syncplay.android.data.network.NsdHelper
import com.syncplay.android.data.network.TcpClient
import com.syncplay.android.data.network.TcpHostServer
import com.syncplay.android.data.sync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for party networking (Phase 1) and clock sync (Phase 2).
 * Owns NSD advertise/discover lifecycle, TCP host/client instances, and [TimeSyncManager] role.
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

    /** Phase 2 clock-sync snapshot (offset / RTT / synced flag). */
    val timeSyncState: StateFlow<TimeSyncManager.SyncState> = TimeSyncManager.state

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
                    // Auto-connect to the first resolved host for Phase 1 UX.
                    if (tcpClient == null && hosts.isNotEmpty()) {
                        val target = hosts.first()
                        connectToHost(target)
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
                        _status.value = ConnectionStatus.Failed(state.message)
                    }
                    is TcpClient.ClientConnectionState.Disconnected -> {
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

        try {
            client.connect(host.hostAddress, host.port)
        } catch (t: Throwable) {
            tcpClient = null
            _status.value = ConnectionStatus.Failed(t.message ?: "Could not connect to host")
            // Resume discovery so the client can find another / retry.
            startDiscovery()
        }
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

    private fun stopAllInternal(clearStatus: Boolean) {
        discoveryJob?.cancel()
        discoveryJob = null
        hostDevicesJob?.cancel()
        hostDevicesJob = null
        clientStateJob?.cancel()
        clientStateJob = null

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
        _role.value = PartyRole.NONE
        if (clearStatus) {
            _status.value = ConnectionStatus.Idle
        }
    }
}
