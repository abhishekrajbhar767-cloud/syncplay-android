package com.syncplay.android.data.sync

import android.util.Log
import com.syncplay.android.data.network.NetworkConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Centralized millisecond clock synchronization (Phase 2).
 *
 * Host role: [getSyncedTimeMs] returns wall-clock [System.currentTimeMillis].
 * Client role: applies Cristian/NTP offset so [getSyncedTimeMs] ≈ host time.
 *
 * NTP sample (client clock for T1/T4, host clock for T2/T3):
 * ```
 * RTT    = (T4 - T1) - (T3 - T2)
 * Offset = ((T2 - T1) + (T3 - T4)) / 2
 * ```
 * Synced client time = local time + Offset.
 */
object TimeSyncManager {
    private const val TAG = "SyncPlayTimeSync"

    enum class Role {
        NONE,
        HOST,
        CLIENT,
    }

    data class NtpSample(
        val t1: Long,
        val t2: Long,
        val t3: Long,
        val t4: Long,
        val rttMs: Long,
        val offsetMs: Long,
    )

    data class SyncState(
        val role: Role = Role.NONE,
        val offsetMs: Long = 0L,
        val rttMs: Long? = null,
        val lastSyncAtEpochMs: Long? = null,
        val sampleCount: Int = 0,
        val isSynced: Boolean = false,
    )

    fun interface SyncRequestSender {
        /** Sends a [com.syncplay.android.data.model.ProtocolMessage.SyncReq] on the TCP channel. */
        suspend fun send(syncId: Long, t1: Long)
    }

    @Volatile
    private var role: Role = Role.NONE

    @Volatile
    private var offsetMs: Long = 0L

    private val syncIdSeq = AtomicLong(0)
    private val stateMutex = Mutex()
    private val recentSamples = ArrayDeque<NtpSample>()

    private var syncLoopJob: Job? = null
    private var syncSender: SyncRequestSender? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Host is the time authority — no offset applied.
     */
    fun becomeHost() {
        stopSyncLoop()
        role = Role.HOST
        offsetMs = 0L
        recentSamples.clear()
        syncSender = null
        publishState(rttMs = null, lastSyncAt = null, sampleCount = 0, synced = true)
        logI("Role=HOST (authority clock)")
    }

    /**
     * Client will run periodic NTP exchanges via [sender].
     */
    fun becomeClient(sender: SyncRequestSender) {
        stopSyncLoop()
        role = Role.CLIENT
        offsetMs = 0L
        recentSamples.clear()
        syncSender = sender
        publishState(rttMs = null, lastSyncAt = null, sampleCount = 0, synced = false)
        logI("Role=CLIENT (offset pending first sample)")
    }

    fun reset() {
        stopSyncLoop()
        role = Role.NONE
        offsetMs = 0L
        recentSamples.clear()
        syncSender = null
        _state.value = SyncState()
        logI("Time sync reset")
    }

    /**
     * Host: system wall clock.
     * Client: system wall clock + estimated offset toward the host.
     */
    fun getSyncedTimeMs(): Long {
        return when (role) {
            Role.HOST, Role.NONE -> System.currentTimeMillis()
            Role.CLIENT -> System.currentTimeMillis() + offsetMs
        }
    }

    fun currentOffsetMs(): Long = offsetMs

    fun isClientSynced(): Boolean = role == Role.CLIENT && _state.value.isSynced

    /**
     * Starts the background sync loop (every 3–5s with jitter). No-op unless role is CLIENT.
     */
    fun startPeriodicSync(scope: CoroutineScope) {
        if (role != Role.CLIENT) return
        stopSyncLoop()
        syncLoopJob = scope.launch {
            // Immediate first sample so UI / playback can sync quickly after connect.
            runCatching { performSyncExchange() }
            while (isActive && role == Role.CLIENT) {
                delay(nextIntervalMs())
                runCatching { performSyncExchange() }
                    .onFailure { logW("Sync exchange failed: ${it.message}") }
            }
        }
        logI("Periodic NTP sync started")
    }

    fun stopSyncLoop() {
        syncLoopJob?.cancel()
        syncLoopJob = null
    }

    /**
     * Pure NTP math — exposed for unit tests and for applying a completed 4-timestamp exchange.
     */
    fun computeSample(t1: Long, t2: Long, t3: Long, t4: Long): NtpSample {
        val rtt = (t4 - t1) - (t3 - t2)
        val offset = ((t2 - t1) + (t3 - t4)) / 2L
        return NtpSample(
            t1 = t1,
            t2 = t2,
            t3 = t3,
            t4 = t4,
            rttMs = rtt,
            offsetMs = offset,
        )
    }

    /**
     * Called by [com.syncplay.android.data.network.TcpClient] when a SyncRes arrives.
     * Stamps T4 immediately for accuracy.
     */
    suspend fun onSyncResponse(syncId: Long, t1: Long, t2: Long, t3: Long) {
        val t4 = System.currentTimeMillis()
        if (role != Role.CLIENT) return
        val sample = computeSample(t1 = t1, t2 = t2, t3 = t3, t4 = t4)
        applySample(sample)
        logD(
            "SYNC#$syncId rtt=${sample.rttMs}ms offset=${sample.offsetMs}ms " +
                "t1=$t1 t2=$t2 t3=$t3 t4=$t4",
        )
    }

    private suspend fun performSyncExchange() {
        val sender = syncSender ?: return
        val syncId = syncIdSeq.incrementAndGet()
        val t1 = System.currentTimeMillis()
        sender.send(syncId, t1)
    }

    private suspend fun applySample(sample: NtpSample) {
        if (sample.rttMs < 0) {
            logW("Discarding sample with negative RTT=${sample.rttMs}")
            return
        }
        if (sample.rttMs > NetworkConstants.TIME_SYNC_MAX_RTT_MS) {
            logW("Discarding high-RTT sample rtt=${sample.rttMs}ms")
            return
        }

        stateMutex.withLock {
            recentSamples.addLast(sample)
            while (recentSamples.size > NetworkConstants.TIME_SYNC_SAMPLE_WINDOW) {
                recentSamples.removeFirst()
            }
            // Prefer the lowest-RTT sample in the window (classic practical NTP filter).
            val best = recentSamples.minByOrNull { it.rttMs } ?: sample
            offsetMs = best.offsetMs
            publishState(
                rttMs = best.rttMs,
                lastSyncAt = System.currentTimeMillis(),
                sampleCount = recentSamples.size,
                synced = true,
            )
        }
    }

    private fun publishState(
        rttMs: Long?,
        lastSyncAt: Long?,
        sampleCount: Int,
        synced: Boolean,
    ) {
        _state.value = SyncState(
            role = role,
            offsetMs = offsetMs,
            rttMs = rttMs,
            lastSyncAtEpochMs = lastSyncAt,
            sampleCount = sampleCount,
            isSynced = synced,
        )
    }

    private fun nextIntervalMs(): Long {
        val min = NetworkConstants.TIME_SYNC_INTERVAL_MIN_MS
        val max = NetworkConstants.TIME_SYNC_INTERVAL_MAX_MS
        return if (max <= min) min else Random.nextLong(min, max + 1)
    }

    /** JVM unit tests have no Android Looper — never let logging crash the sync path. */
    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }
}
