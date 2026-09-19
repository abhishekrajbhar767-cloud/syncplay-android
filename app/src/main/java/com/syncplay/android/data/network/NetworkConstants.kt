package com.syncplay.android.data.network

/**
 * Shared constants for NSD advertising and the Phase 1 control TCP channel.
 */
object NetworkConstants {
    /** Zeroconf / NSD service type. Trailing protocol is required by Android NSD. */
    const val NSD_SERVICE_TYPE = "_audio_sync._tcp."

    /** Default advertised service base name; Android may append a suffix on conflict. */
    const val NSD_SERVICE_NAME = "SyncPlayParty"

    /** Preferred TCP listen port for the host control channel. */
    const val DEFAULT_TCP_PORT = 9090

    /** How often the host pings each client. */
    const val HEARTBEAT_INTERVAL_MS = 2_000L

    /** If no pong (or any traffic) within this window, the peer is considered dead. */
    const val HEARTBEAT_TIMEOUT_MS = 6_000L

    /** Socket read / connect timeouts. */
    const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
    const val SOCKET_SO_TIMEOUT_MS = 10_000

    /** Max concurrent client sockets a host will accept in Phase 1. */
    const val MAX_CLIENTS = 16

    const val TXT_ATTR_DEVICE_NAME = "deviceName"
    const val TXT_ATTR_DEVICE_ID = "deviceId"
    const val TXT_ATTR_SESSION_ID = "sessionId"
}
