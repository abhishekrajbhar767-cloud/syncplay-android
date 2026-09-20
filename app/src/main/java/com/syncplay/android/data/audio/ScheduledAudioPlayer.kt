package com.syncplay.android.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.syncplay.android.data.sync.TimeSyncManager
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Plays UDP PCM chunks exactly when
 * `TimeSyncManager.getSyncedTimeMs() + manualOffsetMs >= presentationTimestampMs`.
 */
class ScheduledAudioPlayer {
    private val tag = "SyncPlayPlayer"

    private val running = AtomicBoolean(false)
    private val manualOffsetMs = AtomicInteger(0)
    private val playedCount = AtomicLong(0)
    private val droppedLate = AtomicLong(0)

    private val queue = PriorityBlockingQueue<TimedChunk>(64, compareBy { it.presentationTimestampMs })

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var worker: Thread? = null

    data class TimedChunk(
        val presentationTimestampMs: Long,
        val sequence: Long,
        val pcm: ByteArray,
    )

    data class Stats(
        val queued: Int,
        val played: Long,
        val droppedLate: Long,
        val manualOffsetMs: Int,
    )

    fun setManualOffsetMs(offsetMs: Int) {
        manualOffsetMs.set(offsetMs.coerceIn(-200, 800))
    }

    fun getManualOffsetMs(): Int = manualOffsetMs.get()

    fun start() {
        check(running.compareAndSet(false, true)) { "Player already started" }

        val minBuf = AudioTrack.getMinBufferSize(
            AudioStreamConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioStreamConfig.ENCODING,
        )
        val bufferSize = maxOf(minBuf, AudioStreamConfig.frameByteSize * 8)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(AudioStreamConfig.playbackAudioFormat())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        track.play()
        audioTrack = track

        worker = thread(name = "syncplay-audio-player", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            playLoop(track)
        }
        Log.i(tag, "Scheduled player started (buf=$bufferSize)")
    }

    fun enqueue(packet: UdpAudioPacket) {
        if (!running.get()) return
        // Bound queue depth (~1s of 10ms frames).
        while (queue.size > 100) {
            queue.poll()
            droppedLate.incrementAndGet()
        }
        queue.offer(
            TimedChunk(
                presentationTimestampMs = packet.presentationTimestampMs,
                sequence = packet.sequence,
                pcm = packet.pcm,
            )
        )
    }

    fun stats(): Stats = Stats(
        queued = queue.size,
        played = playedCount.get(),
        droppedLate = droppedLate.get(),
        manualOffsetMs = manualOffsetMs.get(),
    )

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        queue.clear()
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
        Log.i(tag, "Scheduled player stopped")
    }

    private fun playLoop(track: AudioTrack) {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val chunk = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }

            val target = chunk.presentationTimestampMs + manualOffsetMs.get()
            val now = TimeSyncManager.getSyncedTimeMs()
            val waitMs = target - now

            when {
                waitMs > 1_000 -> {
                    // Far future — likely bad clock; drop.
                    droppedLate.incrementAndGet()
                    continue
                }
                waitMs > 2 -> {
                    preciseWaitUntil(target)
                }
                waitMs < -AudioStreamConfig.PRESENTATION_BUFFER_MS -> {
                    // Too late to play without echo/desync — drop.
                    droppedLate.incrementAndGet()
                    continue
                }
                // else: slightly late or on time — play immediately
            }

            // Final spin for sub-ms alignment when close.
            while (running.get()) {
                val remaining = target - TimeSyncManager.getSyncedTimeMs()
                if (remaining <= 0) break
                if (remaining > 2) {
                    Thread.sleep(1)
                } else {
                    // Busy-wait the last ~2ms for tighter sync.
                    Thread.yield()
                }
            }

            if (!running.get()) break
            var offset = 0
            while (offset < chunk.pcm.size && running.get()) {
                val written = track.write(chunk.pcm, offset, chunk.pcm.size - offset)
                if (written < 0) break
                offset += written
            }
            playedCount.incrementAndGet()
        }
    }

    private fun preciseWaitUntil(targetSyncedMs: Long) {
        while (running.get()) {
            val remaining = targetSyncedMs - TimeSyncManager.getSyncedTimeMs()
            if (remaining <= 2) return
            val sleep = (remaining - 2).coerceAtMost(20)
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
