package com.syncplay.android.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures internal (system) playback audio via [AudioPlaybackCaptureConfiguration].
 * Requires Android 10+ and an active [MediaProjection] grant.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapturer(
    private val mediaProjection: MediaProjection,
) {
    private val tag = "SyncPlayCapture"
    private val running = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    fun start(): AudioRecord {
        check(running.compareAndSet(false, true)) { "Capturer already started" }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioStreamConfig.audioFormat()
        val minBuf = AudioRecord.getMinBufferSize(
            AudioStreamConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioStreamConfig.ENCODING,
        )
        val bufferSize = maxOf(minBuf, AudioStreamConfig.frameByteSize * 4)

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false)
            record.release()
            error("AudioRecord failed to initialize for playback capture")
        }

        record.startRecording()
        audioRecord = record
        Log.i(tag, "System audio capture started (buf=$bufferSize)")
        return record
    }

    fun read(buffer: ByteArray, offset: Int = 0, size: Int = buffer.size): Int {
        val record = audioRecord ?: return 0
        return record.read(buffer, offset, size)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        Log.i(tag, "System audio capture stopped")
    }
}
