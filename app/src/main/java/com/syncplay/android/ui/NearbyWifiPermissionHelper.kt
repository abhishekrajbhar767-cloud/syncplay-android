package com.syncplay.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Requests the platform-specific nearby Wi-Fi / location permissions needed for NSD
 * on Android 12 and below (location) vs Android 13+ (NEARBY_WIFI_DEVICES).
 */
class NearbyWifiPermissionHelper(private val activity: ComponentActivity) {
    private var onResult: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it } || hasPermission()
        onResult?.invoke(granted)
        onResult = null
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }

    fun hasPermission(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun ensurePermission(onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            onResult(true)
            return
        }
        this.onResult = onResult
        launcher.launch(requiredPermissions())
    }
}
