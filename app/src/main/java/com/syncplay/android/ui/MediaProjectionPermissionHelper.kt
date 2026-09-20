package com.syncplay.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Handles RECORD_AUDIO / POST_NOTIFICATIONS and the MediaProjection capture consent dialog.
 */
class MediaProjectionPermissionHelper(private val activity: ComponentActivity) {
    private var onProjectionResult: ((resultCode: Int, data: android.content.Intent?) -> Unit)? = null
    private var onAudioPermissionResult: ((Boolean) -> Unit)? = null

    private val audioPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        onAudioPermissionResult?.invoke(granted)
        onAudioPermissionResult = null
    }

    private val projectionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        onProjectionResult?.invoke(result.resultCode, result.data)
        onProjectionResult = null
    }

    fun hasCapturePermissions(): Boolean {
        val record = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return record && notifications
    }

    fun ensureCapturePermissions(onResult: (Boolean) -> Unit) {
        if (hasCapturePermissions()) {
            onResult(true)
            return
        }
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        onAudioPermissionResult = onResult
        audioPermissionLauncher.launch(perms)
    }

    fun requestMediaProjection(onResult: (resultCode: Int, data: android.content.Intent?) -> Unit) {
        val mpm = activity.getSystemService(MediaProjectionManager::class.java)
            ?: run {
                onResult(0, null)
                return
            }
        onProjectionResult = onResult
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
