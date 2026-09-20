package com.syncplay.android.data.audio

import android.util.Log
import com.syncplay.android.data.sync.TimeSyncManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side UDP sender. Each PCM frame is wrapped with a presentation timestamp:
 * `TimeSyncManager.getSyncedTimeMs() + PRESENTATION_BUFFER_MS`.
 */
class UdpAudioBroadcaster(
    private val udpPort: Int = AudioStreamConfig.UDP_PORT,
) {
    private val tag = "SyncPlayUdpTx"
    private val running = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val destinations = CopyOnWriteArraySet<InetSocketAddress>()

    @Volatile
    private var socket: DatagramSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Broadcaster already started" }
        socket = DatagramSocket().apply {
            broadcast = true
            reuseAddress = true
        }
        Log.i(tag, "UDP broadcaster ready → :$udpPort")
    }

    fun setDestinations(addresses: Collection<String>) {
        destinations.clear()
        addresses.forEach { ip ->
            if (ip.isNotBlank() && ip != "unknown") {
                destinations.add(InetSocketAddress(ip, udpPort))
            }
        }
    }

    fun addDestination(ip: String) {
        if (ip.isNotBlank() && ip != "unknown") {
            destinations.add(InetSocketAddress(ip, udpPort))
        }
    }

    fun removeDestination(ip: String) {
        destinations.removeIf { it.hostString == ip }
    }

    /**
     * Builds a packet for [pcm] and unicasts it to every known client.
     * @return presentation timestamp attached to the packet
     */
    fun sendPcmFrame(pcm: ByteArray, length: Int = pcm.size): Long {
        val sock = socket ?: return -1L
        if (!running.get() || destinations.isEmpty()) return -1L

        val payload = if (length == pcm.size) pcm else pcm.copyOf(length)
        val pts = TimeSyncManager.getSyncedTimeMs() + AudioStreamConfig.PRESENTATION_BUFFER_MS
        val packet = UdpAudioPacket(
            sequence = sequence.incrementAndGet(),
            presentationTimestampMs = pts,
            sampleRate = AudioStreamConfig.SAMPLE_RATE_HZ,
            channelCount = AudioStreamConfig.CHANNEL_COUNT,
            pcm = payload,
        )
        val bytes = UdpAudioPacket.encode(packet)
        destinations.forEach { dest ->
            runCatching {
                sock.send(DatagramPacket(bytes, bytes.size, dest))
            }.onFailure {
                Log.w(tag, "UDP send failed → $dest: ${it.message}")
            }
        }
        return pts
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        destinations.clear()
        Log.i(tag, "UDP broadcaster stopped")
    }
}
