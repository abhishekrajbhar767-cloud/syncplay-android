package com.syncplay.android.data.audio

import com.syncplay.android.data.sync.TimeSyncManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates a sharp calibration beep for multi-speaker latency alignment.
 *
 * Pattern: 1000 Hz sine, 50 ms burst, every 1000 ms (metronome), timed on the
 * synced host clock so every client hears the same presentation timestamps.
 */
class CalibrationToneGenerator(
    private val sampleRate: Int = AudioStreamConfig.SAMPLE_RATE_HZ,
    private val beepHz: Double = 1_000.0,
    private val beepDurationMs: Int = 50,
    private val periodMs: Int = 1_000,
) {
    private val samplesPerBeep = sampleRate * beepDurationMs / 1_000
    private val samplesPerPeriod = sampleRate * periodMs / 1_000

    /**
     * Fills [out] (interleaved stereo PCM16) for the next [frameSampleCount] frames
     * starting at absolute synced sample index derived from [syncedNowMs].
     */
    fun fillFrame(
        out: ByteArray,
        syncedNowMs: Long = TimeSyncManager.getSyncedTimeMs(),
        frameSampleCount: Int = AudioStreamConfig.frameSampleCount,
    ) {
        require(out.size >= frameSampleCount * 4)

        // Content is aligned to the presentation timeline clients will play.
        val pts = syncedNowMs + AudioStreamConfig.PRESENTATION_BUFFER_MS
        val startSampleAbs = (pts * sampleRate.toLong()) / 1_000L

        var o = 0
        for (i in 0 until frameSampleCount) {
            val sampleIndexInPeriod = ((startSampleAbs + i).mod(samplesPerPeriod.toLong())).toInt()
            val sample = if (sampleIndexInPeriod < samplesPerBeep) {
                val t = sampleIndexInPeriod.toDouble() / sampleRate
                val env = envelope(sampleIndexInPeriod, samplesPerBeep)
                val raw = sin(2.0 * PI * beepHz * t) * env
                (raw * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            } else {
                0
            }
            // Stereo identical (L = R) so LEFT/RIGHT routing still hears the beep.
            out[o++] = (sample.toInt() and 0xFF).toByte()
            out[o++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            out[o++] = (sample.toInt() and 0xFF).toByte()
            out[o++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun envelope(index: Int, length: Int): Double {
        val attack = (length * 0.08).toInt().coerceAtLeast(1)
        val release = (length * 0.15).toInt().coerceAtLeast(1)
        return when {
            index < attack -> index.toDouble() / attack
            index > length - release -> (length - index).toDouble() / release
            else -> 1.0
        }
    }
}
