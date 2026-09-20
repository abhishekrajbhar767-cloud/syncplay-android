package com.syncplay.android.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSyncManagerTest {

    @Test
    fun computeSample_zeroDelaySymmetricPath() {
        // Client and host share the same clock; 10ms one-way delay each direction.
        // T1=1000, arrive host at 1010 (T2), reply at 1010 (T3), arrive client 1020 (T4).
        val sample = TimeSyncManager.computeSample(
            t1 = 1_000L,
            t2 = 1_010L,
            t3 = 1_010L,
            t4 = 1_020L,
        )
        assertEquals(20L, sample.rttMs)
        assertEquals(0L, sample.offsetMs)
    }

    @Test
    fun computeSample_clientBehindHost() {
        // Host clock is 500ms ahead of client. One-way delay = 10ms.
        // Client T1=1000 → host sees T2=1510 (1000+500+10), replies T3=1510,
        // client receives T4=1020.
        val sample = TimeSyncManager.computeSample(
            t1 = 1_000L,
            t2 = 1_510L,
            t3 = 1_510L,
            t4 = 1_020L,
        )
        assertEquals(20L, sample.rttMs)
        assertEquals(500L, sample.offsetMs)
        // synced client time ≈ local + offset
        assertEquals(1_520L, 1_020L + sample.offsetMs)
    }

    @Test
    fun computeSample_clientAheadOfHost() {
        // Host clock is 200ms behind client. One-way = 5ms.
        // T1=1000 → T2=805 (1000-200+5), T3=805 → T4=1010.
        val sample = TimeSyncManager.computeSample(
            t1 = 1_000L,
            t2 = 805L,
            t3 = 805L,
            t4 = 1_010L,
        )
        assertEquals(10L, sample.rttMs)
        assertEquals(-200L, sample.offsetMs)
    }

    @Test
    fun formulas_matchSpec() {
        val t1 = 10L
        val t2 = 40L
        val t3 = 50L
        val t4 = 90L
        val sample = TimeSyncManager.computeSample(t1, t2, t3, t4)
        val expectedRtt = (t4 - t1) - (t3 - t2)
        val expectedOffset = ((t2 - t1) + (t3 - t4)) / 2L
        assertEquals(expectedRtt, sample.rttMs)
        assertEquals(expectedOffset, sample.offsetMs)
        assertEquals(70L, sample.rttMs)
        assertEquals(-5L, sample.offsetMs)
    }

    @Test
    fun hostGetSyncedTime_matchesSystemClock() {
        TimeSyncManager.reset()
        TimeSyncManager.becomeHost()
        val before = System.currentTimeMillis()
        val synced = TimeSyncManager.getSyncedTimeMs()
        val after = System.currentTimeMillis()
        assertTrue(synced in before..after)
        TimeSyncManager.reset()
    }
}
