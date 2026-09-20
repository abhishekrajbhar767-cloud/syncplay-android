package com.syncplay.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.syncplay.android.MainActivity
import com.syncplay.android.R
import com.syncplay.android.SyncPlayApp
import com.syncplay.android.data.audio.AudioStreamConfig
import com.syncplay.android.data.audio.SystemAudioCapturer
import com.syncplay.android.data.audio.UdpAudioBroadcaster
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service that holds the [MediaProjection] grant, captures system audio,
 * and streams PCM frames over UDP to connected clients.
 */
class AudioCaptureService : Service() {
    private val tag = "SyncPlayAudioSvc"
    private val running = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var capturer: SystemAudioCapturer? = null
    private var broadcaster: UdpAudioBroadcaster? = null
    private var captureThread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(tag, "MediaProjection stopped by system")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_TARGETS -> {
                refreshDestinations()
                return START_STICKY
            }
            ACTION_START -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Log.e(tag, "AudioPlaybackCapture requires API 29+")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    Log.e(tag, "Missing MediaProjection result data")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                startCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCaptureInternal()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        ensureChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.audio_service_title))
            .setContentText(getString(R.string.audio_service_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (!running.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
                ?: error("getMediaProjection returned null")
            projection.registerCallback(projectionCallback, null)
            mediaProjection = projection

            val capturer = SystemAudioCapturer(projection)
            capturer.start()
            this.capturer = capturer

            val broadcaster = UdpAudioBroadcaster()
            broadcaster.start()
            this.broadcaster = broadcaster
            refreshDestinations()

            (application as SyncPlayApp).partyRepository.onHostAudioStreamingStarted()

            captureThread = thread(name = "syncplay-capture", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                captureLoop(capturer, broadcaster)
            }
            Log.i(tag, "Capture + UDP streaming started")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start capture", t)
            (application as SyncPlayApp).partyRepository.onHostAudioStreamingFailed(t.message)
            stopCaptureInternal()
            stopSelf()
        }
    }

    private fun captureLoop(capturer: SystemAudioCapturer, broadcaster: UdpAudioBroadcaster) {
        val frame = ByteArray(AudioStreamConfig.frameByteSize)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val read = capturer.read(frame)
            if (read <= 0) {
                if (read < 0) {
                    Log.w(tag, "AudioRecord read error: $read")
                    break
                }
                continue
            }
            // Pad short reads to a full frame for stable PTS spacing.
            if (read < frame.size) {
                frame.fill(0, read, frame.size)
            }
            refreshDestinations()
            broadcaster.sendPcmFrame(frame, frame.size)
        }
    }

    private fun refreshDestinations() {
        val repo = (application as SyncPlayApp).partyRepository
        val ips = repo.connectedDevices.value.map { it.ipAddress }
        broadcaster?.setDestinations(ips)
    }

    private fun stopCaptureInternal() {
        if (!running.getAndSet(false) && capturer == null && broadcaster == null) return
        captureThread?.interrupt()
        captureThread = null
        runCatching { capturer?.stop() }
        capturer = null
        runCatching { broadcaster?.stop() }
        broadcaster = null
        runCatching {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null
        runCatching {
            (application as SyncPlayApp).partyRepository.onHostAudioStreamingStopped()
        }
        Log.i(tag, "Capture stopped")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.audio_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.audio_service_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.syncplay.android.action.START_AUDIO_CAPTURE"
        const val ACTION_STOP = "com.syncplay.android.action.STOP_AUDIO_CAPTURE"
        const val ACTION_UPDATE_TARGETS = "com.syncplay.android.action.UPDATE_AUDIO_TARGETS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "syncplay_audio_capture"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun refreshTargets(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_UPDATE_TARGETS
            }
            context.startService(intent)
        }
    }
}
