package com.syncplay.android.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.syncplay.android.data.model.DiscoveredHost
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around Android [NsdManager] for advertising and discovering
 * `_audio_sync._tcp.` services on the local LAN / hotspot.
 */
class NsdHelper(
    context: Context,
    private val multicastLockManager: MulticastLockManager,
) {
    private val tag = "SyncPlayNsd"
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registeredServiceName: String? = null

    /**
     * Registers a SyncPlay host service. Suspends until registration succeeds or fails.
     * Call [unregister] when hosting stops.
     */
    suspend fun registerService(
        serviceName: String = NetworkConstants.NSD_SERVICE_NAME,
        port: Int,
        attributes: Map<String, String> = emptyMap(),
    ): String = suspendCancellableCoroutine { cont ->
        multicastLockManager.acquire()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = NetworkConstants.NSD_SERVICE_TYPE
            this.port = port
            attributes.forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(tag, "Registered NSD service: ${info.serviceName} on port $port")
                if (cont.isActive) cont.resume(info.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD registration failed: $errorCode")
                if (cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("NSD registration failed (code=$errorCode)")
                    )
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(tag, "Unregistered NSD service: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(tag, "NSD unregistration failed: $errorCode")
            }
        }

        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        cont.invokeOnCancellation {
            unregister()
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        registeredServiceName = null
        runCatching { nsdManager.unregisterService(listener) }
            .onFailure { Log.w(tag, "unregisterService threw", it) }
        multicastLockManager.release()
    }

    /**
     * Continuously discovers SyncPlay hosts. Emits the current set of resolved hosts
     * whenever a service is found, lost, or updated.
     */
    fun discoverHosts(): Flow<List<DiscoveredHost>> = callbackFlow {
        multicastLockManager.acquire()

        val discovered = ConcurrentHashMap<String, DiscoveredHost>()
        val resolving = ConcurrentHashMap.newKeySet<String>()
        val discoveryActive = AtomicBoolean(true)

        fun emitSnapshot() {
            trySend(discovered.values.sortedBy { it.serviceName })
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(tag, "NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!discoveryActive.get()) return
                if (serviceInfo.serviceType != NetworkConstants.NSD_SERVICE_TYPE &&
                    !serviceInfo.serviceType.contains("audio_sync")
                ) {
                    return
                }
                val key = serviceInfo.serviceName
                if (!resolving.add(key)) return
                resolveService(serviceInfo) { resolved ->
                    resolving.remove(key)
                    if (resolved != null && discoveryActive.get()) {
                        discovered[key] = resolved
                        emitSnapshot()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discovered.remove(serviceInfo.serviceName)
                emitSnapshot()
                Log.i(tag, "NSD service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(tag, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "Start discovery failed: $errorCode")
                close(IllegalStateException("NSD discovery failed to start (code=$errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "Stop discovery failed: $errorCode")
            }
        }

        nsdManager.discoverServices(
            NetworkConstants.NSD_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )

        awaitClose {
            discoveryActive.set(false)
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            multicastLockManager.release()
        }
    }

    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        onResolved: (DiscoveredHost?) -> Unit,
    ) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(tag, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                onResolved(null)
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = extractHostAddress(resolvedInfo) ?: run {
                    onResolved(null)
                    return
                }
                val deviceName = resolvedInfo.attributes
                    ?.get(NetworkConstants.TXT_ATTR_DEVICE_NAME)
                    ?.toString(Charsets.UTF_8)
                    ?: resolvedInfo.serviceName

                onResolved(
                    DiscoveredHost(
                        serviceName = resolvedInfo.serviceName,
                        hostName = deviceName,
                        hostAddress = host,
                        port = resolvedInfo.port,
                    )
                )
            }
        }

        // Prefer serviceInfo.resolve() path on API 34+ when available; keep classic API for minSdk 26.
        @Suppress("DEPRECATION")
        nsdManager.resolveService(serviceInfo, listener)
    }

    private fun extractHostAddress(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addresses = info.hostAddresses
            if (!addresses.isNullOrEmpty()) {
                return addresses.first().hostAddress
            }
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }
}
