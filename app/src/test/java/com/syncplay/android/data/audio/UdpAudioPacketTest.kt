package com.syncplay.android.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpAudioPacketTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val pcm = ByteArray(AudioStreamConfig.frameByteSize) { (it % 64).toByte() }
        val original = UdpAudioPacket(
            sequence = 99,
            presentationTimestampMs = 1_700_000_123L,
            sampleRate = AudioStreamConfig.SAMPLE_RATE_HZ,
            channelCount = AudioStreamConfig.CHANNEL_COUNT,
            pcm = pcm,
        )
        val encoded = UdpAudioPacket.encode(original)
        val decoded = UdpAudioPacket.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun rejectsBadMagic() {
        val pcm = ByteArray(64)
        val encoded = UdpAudioPacket.encode(
            UdpAudioPacket(1, 2, 48_000, 2, pcm),
        )
        encoded[0] = 0
        assertTrue(UdpAudioPacket.decode(encoded) == null)
    }

    @Test
    fun presentationBufferConstant() {
        assertEquals(200L, AudioStreamConfig.PRESENTATION_BUFFER_MS)
        assertTrue(AudioStreamConfig.frameByteSize > 0)
    }
}
