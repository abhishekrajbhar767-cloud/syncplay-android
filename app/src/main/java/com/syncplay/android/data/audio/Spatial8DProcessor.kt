package com.syncplay.android.data.audio

import com.syncplay.android.data.sync.TimeSyncManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-frequency oscillator (LFO) stereo panner for an "8D" rotating-room effect.
 *
 * At synced time [t], left/right gains follow sine/cosine so energy orbits the
 * stereo field ~once every [cycleMs].
 */
class Spatial8DProcessor(
    private val cycleMs: Long = 8_000L,
    private val depth: Double = 0.85,
) {
    @Volatile
    var enabled: Boolean = false

    /**
     * Applies pan gains in-place to interleaved stereo PCM16.
     */
    fun processInPlace(
        pcm: ByteArray,
        length: Int = pcm.size,
        syncedNowMs: Long = TimeSyncManager.getSyncedTimeMs(),
    ) {
        if (!enabled || length < 4) return
        val phase = ((syncedNowMs % cycleMs).toDouble() / cycleMs) * 2.0 * PI
        // Constant-power pan: L = cos(θ), R = sin(θ), scaled by depth toward center.
        val rawL = cos(phase)
        val rawR = sin(phase)
        val leftGain = ((1.0 - depth) + depth * ((rawL + 1.0) * 0.5)).coerceIn(0.0, 1.0)
        val rightGain = ((1.0 - depth) + depth * ((rawR + 1.0) * 0.5)).coerceIn(0.0, 1.0)

        val buffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 3 < length) {
            val left = buffer.getShort(i).toInt()
            val right = buffer.getShort(i + 2).toInt()
            buffer.putShort(i, (left * leftGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            buffer.putShort(
                i + 2,
                (right * rightGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
            )
            i += 4
        }
    }

    fun processCopy(pcm: ByteArray, length: Int = pcm.size, syncedNowMs: Long = TimeSyncManager.getSyncedTimeMs()): ByteArray {
        val copy = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
        processInPlace(copy, copy.size, syncedNowMs)
        return copy
    }
}
