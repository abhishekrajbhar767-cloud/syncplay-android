package com.syncplay.android.data.model

/**
 * Snapshot of a remote peer visible to the Host UI.
 */
data class ConnectedDevice(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val connectedAtEpochMs: Long = System.currentTimeMillis(),
    val lastHeartbeatEpochMs: Long = System.currentTimeMillis(),
    val roundTripMs: Long? = null,
)

/**
 * A host discovered via NSD on the local network.
 */
data class DiscoveredHost(
    val serviceName: String,
    val hostName: String,
    val hostAddress: String,
    val port: Int,
)

enum class PartyRole {
    NONE,
    HOST,
    CLIENT,
}

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Starting : ConnectionStatus
    data class Hosting(val serviceName: String, val port: Int) : ConnectionStatus
    data object Searching : ConnectionStatus
    data class Connecting(val hostName: String, val hostAddress: String) : ConnectionStatus
    data class Connected(
        val peerName: String,
        val peerAddress: String,
        val sessionId: String? = null,
    ) : ConnectionStatus
    data class Failed(val message: String) : ConnectionStatus
    data object Stopped : ConnectionStatus
}
