package com.syncplay.android.data.network

import com.syncplay.android.data.model.ProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {
    @Test
    fun helloRoundTrip() {
        val original = ProtocolMessage.Hello(deviceId = "abc", deviceName = "Pixel 8")
        val encoded = ProtocolCodec.encode(original)
        val decoded = ProtocolCodec.decode(encoded)
        assertTrue(decoded is ProtocolMessage.Hello)
        decoded as ProtocolMessage.Hello
        assertEquals("abc", decoded.deviceId)
        assertEquals("Pixel 8", decoded.deviceName)
    }

    @Test
    fun pingPongRoundTrip() {
        val ping = ProtocolMessage.Ping(sequence = 7, sentAtEpochMs = 123L)
        val decodedPing = ProtocolCodec.decode(ProtocolCodec.encode(ping)) as ProtocolMessage.Ping
        assertEquals(7L, decodedPing.sequence)

        val pong = ProtocolMessage.Pong(sequence = 7, sentAtEpochMs = 123L, receivedAtEpochMs = 150L)
        val decodedPong = ProtocolCodec.decode(ProtocolCodec.encode(pong)) as ProtocolMessage.Pong
        assertEquals(150L, decodedPong.receivedAtEpochMs)
    }
}
