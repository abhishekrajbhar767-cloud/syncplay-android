package com.syncplay.android.data.model

/**
 * Wire protocol for the SyncPlay control channel (Phases 1–2).
 *
 * Framing: one UTF-8 JSON object per line (`\n` delimited) over a persistent TCP socket.
 *
 * Phase 2 adds Cristian/NTP-style clock sync via [SyncReq] / [SyncRes]
 * (timestamps T1–T4).
 */
sealed interface ProtocolMessage {
    val type: String

    data class Hello(
        val deviceId: String,
        val deviceName: String,
        override val type: String = TYPE_HELLO,
    ) : ProtocolMessage

    data class Welcome(
        val hostId: String,
        val hostName: String,
        val sessionId: String,
        override val type: String = TYPE_WELCOME,
    ) : ProtocolMessage

    data class Ping(
        val sequence: Long,
        val sentAtEpochMs: Long,
        override val type: String = TYPE_PING,
    ) : ProtocolMessage

    data class Pong(
        val sequence: Long,
        val sentAtEpochMs: Long,
        val receivedAtEpochMs: Long = System.currentTimeMillis(),
        override val type: String = TYPE_PONG,
    ) : ProtocolMessage

    /**
     * Client → Host clock-sync request.
     *
     * [t1] is the client send timestamp (ms since epoch, client clock).
     */
    data class SyncReq(
        val syncId: Long,
        val t1: Long,
        override val type: String = TYPE_SYNC_REQ,
    ) : ProtocolMessage

    /**
     * Host → Client clock-sync response.
     *
     * [t1] is echoed from the request; [t2] is host receive time; [t3] is host send time.
     * Client stamps [t4] locally on receipt.
     */
    data class SyncRes(
        val syncId: Long,
        val t1: Long,
        val t2: Long,
        val t3: Long,
        override val type: String = TYPE_SYNC_RES,
    ) : ProtocolMessage

    data class Disconnect(
        val reason: String = "bye",
        override val type: String = TYPE_DISCONNECT,
    ) : ProtocolMessage

    data class Error(
        val code: String,
        val message: String,
        override val type: String = TYPE_ERROR,
    ) : ProtocolMessage

    companion object {
        const val TYPE_HELLO = "HELLO"
        const val TYPE_WELCOME = "WELCOME"
        const val TYPE_PING = "PING"
        const val TYPE_PONG = "PONG"
        const val TYPE_SYNC_REQ = "SYNC_REQ"
        const val TYPE_SYNC_RES = "SYNC_RES"
        const val TYPE_DISCONNECT = "DISCONNECT"
        const val TYPE_ERROR = "ERROR"
    }
}
