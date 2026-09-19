package com.syncplay.android.data.model

/**
 * Wire protocol for Phase 1 control channel.
 *
 * Framing: one UTF-8 JSON object per line (`\n` delimited) over a persistent TCP socket.
 * Future phases can extend [ProtocolMessage] without breaking line framing.
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
        const val TYPE_DISCONNECT = "DISCONNECT"
        const val TYPE_ERROR = "ERROR"
    }
}
