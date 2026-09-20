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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.syncplay.android.MainActivity
import com.syncplay.android.R
import com.syncplay.android.SyncPlayApp
import com.syncplay.android.data.audio.AudioStreamConfig
import com.syncplay.android.data.audio.CalibrationToneGenerator
import com.syncplay.android.data.audio.SystemAudioCapturer
import com.syncplay.android.data.audio.UdpAudioBroadcaster
import com.syncplay.android.data.sync.TimeSyncManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service for Phase 3–4 audio:
 * - System capture via MediaProjection, and/or
 * - Calibration beep generation
 * with per-client channel routing and optional 8D pan on the UDP path.
 */
class AudioCaptureService : Service() {
    private val tag = "SyncPlayAudioSvc"
    private val running = AtomicBoolean(false)
    private val calibrationMode = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var capturer: SystemAudioCapturer? = null
    private var broadcaster: UdpAudioBroadcaster? = null
    private var captureThread: Thread? = null
    private val toneGenerator = CalibrationToneGenerator()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(tag, "MediaProjection stopped by system")
            // If we still need calibration-only streaming, keep UDP alive.
            if (calibrationMode.get() && running.get()) {
                runCatching { capturer?.stop() }
                capturer = null
                mediaProjection = null
                return
            }
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
                refreshTargets()
                return START_STICKY
            }
            ACTION_SET_CALIBRATION -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                calibrationMode.set(enabled)
                broadcaster?.let { /* keep streaming */ }
                if (enabled && !running.get()) {
                    startCalibrationOnly()
                }
                (application as SyncPlayApp).partyRepository.onCalibrationModeChanged(enabled)
                return START_STICKY
            }
            ACTION_SET_EIGHT_D -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                broadcaster?.setEightDEnabled(enabled)
                (application as SyncPlayApp).partyRepository.onEightDModeChanged(enabled)
                return START_STICKY
            }
            ACTION_START_CALIBRATION_ONLY -> {
                startForegroundWithNotification(calibration = true)
                startCalibrationOnly()
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
                startForegroundWithNotification(calibration = false)
                startSystemCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCaptureInternal()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(calibration: Boolean) {
        ensureChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.audio_service_title))
            .setContentText(
                if (calibration) getString(R.string.audio_service_calibration_text)
                else getString(R.string.audio_service_text),
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (calibration && mediaProjection == null) {
                // Calibration-only: mediaPlayback is sufficient on API 29+.
                if (Build.VERSION.SDK_INT >= 34) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCalibrationOnly() {
        if (!running.compareAndSet(false, true)) {
            calibrationMode.set(true)
            return
        }
        calibrationMode.set(true)
        try {
            val broadcaster = UdpAudioBroadcaster()
            broadcaster.start()
            broadcaster.setEightDEnabled(
                (application as SyncPlayApp).partyRepository.eightDEnabled.value,
            )
            this.broadcaster = broadcaster
            refreshTargets()
            (application as SyncPlayApp).partyRepository.onHostAudioStreamingStarted()
            (application as SyncPlayApp).partyRepository.onCalibrationModeChanged(true)

            captureThread = thread(name = "syncplay-calib", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                streamLoop()
            }
            Log.i(tag, "Calibration-only UDP streaming started")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start calibration stream", t)
            (application as SyncPlayApp).partyRepository.onHostAudioStreamingFailed(t.message)
            stopCaptureInternal()
            stopSelf()
        }
    }

    private fun startSystemCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        // If calibration-only was running, stop the loop and rebuild with capturer.
        if (running.get() && capturer == null) {
            running.set(false)
            captureThread?.interrupt()
            captureThread = null
            runCatching { broadcaster?.stop() }
            broadcaster = null
        }

        if (!running.compareAndSet(false, true) && capturer != null) return
        running.set(true)
        calibrationMode.set(false)

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
                ?: error("getMediaProjection returned null")
            projection.registerCallback(projectionCallback, null)
            mediaProjection = projection

            val capturer = SystemAudioCapturer(projection)
            capturer.start()
            this.capturer = capturer

            if (broadcaster == null) {
                val broadcaster = UdpAudioBroadcaster()
                broadcaster.start()
                this.broadcaster = broadcaster
            }
            broadcaster?.setEightDEnabled(
                (application as SyncPlayApp).partyRepository.eightDEnabled.value,
            )
            refreshTargets()

            (application as SyncPlayApp).partyRepository.onHostAudioStreamingStarted()
            (application as SyncPlayApp).partyRepository.onCalibrationModeChanged(false)

            if (captureThread?.isAlive != true) {
                captureThread = thread(name = "syncplay-capture", isDaemon = true) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    streamLoop()
                }
            }
            Log.i(tag, "System capture + UDP streaming started")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start capture", t)
            (application as SyncPlayApp).partyRepository.onHostAudioStreamingFailed(t.message)
            stopCaptureInternal()
            stopSelf()
        }
    }

    private fun streamLoop() {
        val frame = ByteArray(AudioStreamConfig.frameByteSize)
        var nextFrameAt = SystemClock.elapsedRealtime()
        while (running.get() && !Thread.currentThread().isInterrupted) {
            refreshTargets()
            val broadcaster = this.broadcaster ?: break

            if (calibrationMode.get()) {
                toneGenerator.fillFrame(frame, TimeSyncManager.getSyncedTimeMs())
                broadcaster.sendPcmFrame(frame, frame.size)
                nextFrameAt += AudioStreamConfig.FRAME_DURATION_MS
                val sleep = nextFrameAt - SystemClock.elapsedRealtime()
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    nextFrameAt = SystemClock.elapsedRealtime()
                }
            } else {
                val activeCapturer = capturer
                if (activeCapturer == null) {
                    // Calibration-only session ended — wait for stop or system capture.
                    try {
                        Thread.sleep(AudioStreamConfig.FRAME_DURATION_MS.toLong())
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val read = activeCapturer.read(frame)
                if (read <= 0) {
                    if (read < 0) {
                        Log.w(tag, "AudioRecord read error: $read")
                        break
                    }
                    continue
                }
                if (read < frame.size) {
                    frame.fill(0, read, frame.size)
                }
                broadcaster.sendPcmFrame(frame, frame.size)
            }
        }
    }

    private fun refreshTargets() {
        val repo = (application as SyncPlayApp).partyRepository
        val devices = repo.connectedDevices.value
        val channels = repo.channelAssignments.value
        broadcaster?.setTargets(
            devices.map { device ->
                UdpAudioBroadcaster.ClientTarget(
                    deviceId = device.deviceId,
                    ipAddress = device.ipAddress,
                    channel = channels[device.deviceId]
                        ?: device.speakerChannel,
                )
            },
        )
        broadcaster?.setEightDEnabled(repo.eightDEnabled.value)
    }

    private fun stopCaptureInternal() {
        if (!running.getAndSet(false) && capturer == null && broadcaster == null) return
        calibrationMode.set(false)
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
        const val ACTION_START_CALIBRATION_ONLY = "com.syncplay.android.action.START_CALIBRATION_ONLY"
        const val ACTION_STOP = "com.syncplay.android.action.STOP_AUDIO_CAPTURE"
        const val ACTION_UPDATE_TARGETS = "com.syncplay.android.action.UPDATE_AUDIO_TARGETS"
        const val ACTION_SET_CALIBRATION = "com.syncplay.android.action.SET_CALIBRATION"
        const val ACTION_SET_EIGHT_D = "com.syncplay.android.action.SET_EIGHT_D"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ENABLED = "enabled"

        private const val CHANNEL_ID = "syncplay_audio_capture"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            startFg(context, intent)
        }

        fun startCalibrationOnly(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START_CALIBRATION_ONLY
            }
            startFg(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            )
        }

        fun refreshTargets(context: Context) {
            context.startService(
                Intent(context, AudioCaptureService::class.java).apply {
                    action = ACTION_UPDATE_TARGETS
                },
            )
        }

        fun setCalibrationMode(context: Context, enabled: Boolean) {
            context.startService(
                Intent(context, AudioCaptureService::class.java).apply {
                    action = ACTION_SET_CALIBRATION
                    putExtra(EXTRA_ENABLED, enabled)
                },
            )
        }

        fun setEightDMode(context: Context, enabled: Boolean) {
            context.startService(
                Intent(context, AudioCaptureService::class.java).apply {
                    action = ACTION_SET_EIGHT_D
                    putExtra(EXTRA_ENABLED, enabled)
                },
            )
        }

        private fun startFg(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
