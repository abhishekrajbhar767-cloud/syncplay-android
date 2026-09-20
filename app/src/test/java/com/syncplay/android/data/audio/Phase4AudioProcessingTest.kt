package com.syncplay.android.data.audio

import com.syncplay.android.data.model.SpeakerChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Phase4AudioProcessingTest {

    @Test
    fun channelRouter_leftCopiesToBoth() {
        val pcm = stereoFrame(left = 1000, right = -2000)
        PcmChannelRouter.applyInPlace(pcm, channel = SpeakerChannel.LEFT_CHANNEL)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000.toShort(), buf.getShort(0))
        assertEquals(1000.toShort(), buf.getShort(2))
    }

    @Test
    fun channelRouter_rightCopiesToBoth() {
        val pcm = stereoFrame(left = 1000, right = -2000)
        PcmChannelRouter.applyInPlace(pcm, channel = SpeakerChannel.RIGHT_CHANNEL)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((-2000).toShort(), buf.getShort(0))
        assertEquals((-2000).toShort(), buf.getShort(2))
    }

    @Test
    fun eightD_disabledLeavesPcmUnchanged() {
        val original = stereoFrame(left = 1234, right = 5678)
        val copy = original.copyOf()
        val processor = Spatial8DProcessor().apply { enabled = false }
        processor.processInPlace(copy, syncedNowMs = 0L)
        assertTrue(original.contentEquals(copy))
    }

    @Test
    fun eightD_enabledChangesEnergy() {
        val pcm = stereoFrame(left = 10_000, right = 10_000)
        val processor = Spatial8DProcessor(cycleMs = 8_000L, depth = 1.0).apply { enabled = true }
        processor.processInPlace(pcm, syncedNowMs = 0L)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val left = buf.getShort(0)
        val right = buf.getShort(2)
        // At phase 0: cos=1 → left high, sin=0 → right near 0 with depth=1.
        assertTrue(left > right)
    }

    @Test
    fun calibrationTone_producesNonSilentBeepRegion() {
        val generator = CalibrationToneGenerator()
        val frame = ByteArray(AudioStreamConfig.frameByteSize)
        // PTS = syncedNow + 200; choose syncedNow so PTS lands at period start (beep).
        generator.fillFrame(frame, syncedNowMs = 800L)
        assertTrue(frame.any { it != 0.toByte() })
    }

    private fun stereoFrame(left: Short, right: Short): ByteArray {
        val bytes = ByteArray(4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(left)
        buf.putShort(right)
        return bytes
    }
}
