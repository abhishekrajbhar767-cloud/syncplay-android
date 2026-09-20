package com.syncplay.android.data.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Stable-enough local identity for Phase 1 handshakes and NSD TXT records.
 */
object DeviceIdentity {
    fun deviceId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()
        return androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    }

    fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val model = Build.MODEL.orEmpty()
        return when {
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isBlank() -> model.ifBlank { "Android Device" }
            else -> "$manufacturer $model".trim()
        }
    }

    /**
     * Best-effort IPv4 used for Host UI diagnostics (clients still connect via NSD address).
     */
    fun localIpv4Addresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
