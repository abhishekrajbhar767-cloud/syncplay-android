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

    @Test
    fun syncReqResRoundTrip() {
        val req = ProtocolMessage.SyncReq(syncId = 42, t1 = 1_000L)
        val decodedReq = ProtocolCodec.decode(ProtocolCodec.encode(req)) as ProtocolMessage.SyncReq
        assertEquals(42L, decodedReq.syncId)
        assertEquals(1_000L, decodedReq.t1)

        val res = ProtocolMessage.SyncRes(syncId = 42, t1 = 1_000L, t2 = 1_010L, t3 = 1_011L)
        val decodedRes = ProtocolCodec.decode(ProtocolCodec.encode(res)) as ProtocolMessage.SyncRes
        assertEquals(1_010L, decodedRes.t2)
        assertEquals(1_011L, decodedRes.t3)
    }
}
