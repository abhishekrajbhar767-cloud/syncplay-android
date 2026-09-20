package com.syncplay.android.data.model

/**
 * Wire protocol for the SyncPlay control channel (Phases 1–4).
 *
 * Framing: one UTF-8 JSON object per line (`\n` delimited) over a persistent TCP socket.
 * Phase 3 audio PCM rides UDP; Phase 4 adds per-client [ChannelAssign] and calibration mode signaling.
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

    data class SyncReq(
        val syncId: Long,
        val t1: Long,
        override val type: String = TYPE_SYNC_REQ,
    ) : ProtocolMessage

    data class SyncRes(
        val syncId: Long,
        val t1: Long,
        val t2: Long,
        val t3: Long,
        override val type: String = TYPE_SYNC_RES,
    ) : ProtocolMessage

    data class AudioSession(
        val udpPort: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val presentationBufferMs: Long,
        val encoding: String = "PCM_16BIT",
        override val type: String = TYPE_AUDIO_SESSION,
    ) : ProtocolMessage

    data class AudioStop(
        val reason: String = "host_stopped",
        override val type: String = TYPE_AUDIO_STOP,
    ) : ProtocolMessage

    /**
     * Host → Client: assign spatial speaker role for this device.
     */
    data class ChannelAssign(
        val deviceId: String,
        val channel: String,
        override val type: String = TYPE_CHANNEL_ASSIGN,
    ) : ProtocolMessage

    /**
     * Host → all clients: calibration beep mode enabled/disabled (UI hint; audio is in the UDP stream).
     */
    data class CalibrationMode(
        val enabled: Boolean,
        override val type: String = TYPE_CALIBRATION_MODE,
    ) : ProtocolMessage

    /**
     * Host → all clients: 8D experience toggle (informational; pan applied on host before UDP).
     */
    data class EightDMode(
        val enabled: Boolean,
        override val type: String = TYPE_EIGHT_D_MODE,
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
        const val TYPE_AUDIO_SESSION = "AUDIO_SESSION"
        const val TYPE_AUDIO_STOP = "AUDIO_STOP"
        const val TYPE_CHANNEL_ASSIGN = "CHANNEL_ASSIGN"
        const val TYPE_CALIBRATION_MODE = "CALIBRATION_MODE"
        const val TYPE_EIGHT_D_MODE = "EIGHT_D_MODE"
        const val TYPE_DISCONNECT = "DISCONNECT"
        const val TYPE_ERROR = "ERROR"
    }
}
