package com.syncplay.android.data.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Holds a Wi-Fi multicast lock for the duration of NSD discovery / advertising.
 * Without this, many devices drop mDNS packets when the screen is off or Doze is active.
 */
class MulticastLockManager(context: Context) {
    private val appContext = context.applicationContext
    private val tag = "SyncPlayMulticast"

    @Volatile
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    fun acquire() {
        if (lock?.isHeld == true) return
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            Log.w(tag, "WifiManager unavailable; NSD multicast may be unreliable")
            return
        }
        val created = wifi.createMulticastLock("syncplay_nsd_multicast").apply {
            setReferenceCounted(false)
            acquire()
        }
        lock = created
        Log.i(tag, "Multicast lock acquired")
    }

    @Synchronized
    fun release() {
        runCatching {
            lock?.takeIf { it.isHeld }?.release()
        }
        lock = null
        Log.i(tag, "Multicast lock released")
    }
}
