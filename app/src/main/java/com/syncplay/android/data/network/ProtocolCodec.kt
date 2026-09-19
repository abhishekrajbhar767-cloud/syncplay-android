package com.syncplay.android.data.network

import com.syncplay.android.data.model.ProtocolMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Encodes / decodes line-framed [ProtocolMessage] JSON without requiring a sealed-class
 * polymorphism plugin setup beyond a small manual mapper (keeps the wire format explicit).
 */
object ProtocolCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encode(message: ProtocolMessage): String {
        val obj = when (message) {
            is ProtocolMessage.Hello -> buildJsonObject {
                put("type", message.type)
                put("deviceId", message.deviceId)
                put("deviceName", message.deviceName)
            }
            is ProtocolMessage.Welcome -> buildJsonObject {
                put("type", message.type)
                put("hostId", message.hostId)
                put("hostName", message.hostName)
                put("sessionId", message.sessionId)
            }
            is ProtocolMessage.Ping -> buildJsonObject {
                put("type", message.type)
                put("sequence", message.sequence)
                put("sentAtEpochMs", message.sentAtEpochMs)
            }
            is ProtocolMessage.Pong -> buildJsonObject {
                put("type", message.type)
                put("sequence", message.sequence)
                put("sentAtEpochMs", message.sentAtEpochMs)
                put("receivedAtEpochMs", message.receivedAtEpochMs)
            }
            is ProtocolMessage.Disconnect -> buildJsonObject {
                put("type", message.type)
                put("reason", message.reason)
            }
            is ProtocolMessage.Error -> buildJsonObject {
                put("type", message.type)
                put("code", message.code)
                put("message", message.message)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun decode(line: String): ProtocolMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = json.decodeFromString(JsonObject.serializer(), trimmed)
            when (obj.string("type")) {
                ProtocolMessage.TYPE_HELLO -> ProtocolMessage.Hello(
                    deviceId = obj.requireString("deviceId"),
                    deviceName = obj.requireString("deviceName"),
                )
                ProtocolMessage.TYPE_WELCOME -> ProtocolMessage.Welcome(
                    hostId = obj.requireString("hostId"),
                    hostName = obj.requireString("hostName"),
                    sessionId = obj.requireString("sessionId"),
                )
                ProtocolMessage.TYPE_PING -> ProtocolMessage.Ping(
                    sequence = obj.requireLong("sequence"),
                    sentAtEpochMs = obj.requireLong("sentAtEpochMs"),
                )
                ProtocolMessage.TYPE_PONG -> ProtocolMessage.Pong(
                    sequence = obj.requireLong("sequence"),
                    sentAtEpochMs = obj.requireLong("sentAtEpochMs"),
                    receivedAtEpochMs = obj.longOrNull("receivedAtEpochMs")
                        ?: System.currentTimeMillis(),
                )
                ProtocolMessage.TYPE_DISCONNECT -> ProtocolMessage.Disconnect(
                    reason = obj.string("reason") ?: "bye",
                )
                ProtocolMessage.TYPE_ERROR -> ProtocolMessage.Error(
                    code = obj.string("code") ?: "unknown",
                    message = obj.string("message") ?: "error",
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requireString(key: String): String =
        string(key) ?: error("Missing string field: $key")

    private fun JsonObject.requireLong(key: String): Long =
        longOrNull(key) ?: error("Missing long field: $key")

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
