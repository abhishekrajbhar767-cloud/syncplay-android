package com.syncplay.android.data.audio

import android.media.AudioFormat

/**
 * Shared PCM stream parameters for Phase 3 capture / UDP / playback.
 */
object AudioStreamConfig {
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE = 2

    /** UDP listen / send port for audio datagrams. */
    const val UDP_PORT = 9_091

    /**
     * Network + jitter buffer ahead of wall-clock play time.
     * Presentation timestamp = syncedNow + this value.
     */
    const val PRESENTATION_BUFFER_MS = 200L

    /** Capture / packetize in ~10 ms frames. */
    const val FRAME_DURATION_MS = 10

    val frameSampleCount: Int
        get() = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000

    val frameByteSize: Int
        get() = frameSampleCount * CHANNEL_COUNT * BYTES_PER_SAMPLE

    val bytesPerMs: Int
        get() = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE / 1_000

    fun audioFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(ENCODING)
        .setSampleRate(SAMPLE_RATE_HZ)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    fun playbackAudioFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(ENCODING)
        .setSampleRate(SAMPLE_RATE_HZ)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()
}
