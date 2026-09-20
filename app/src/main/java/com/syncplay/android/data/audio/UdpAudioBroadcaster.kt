package com.syncplay.android.data.audio

import android.util.Log
import com.syncplay.android.data.model.SpeakerChannel
import com.syncplay.android.data.sync.TimeSyncManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side UDP sender with per-client spatial routing.
 *
 * Each PCM frame is stamped with
 * `TimeSyncManager.getSyncedTimeMs() + PRESENTATION_BUFFER_MS`, then optionally
 * processed by [Spatial8DProcessor] and [PcmChannelRouter] before unicast.
 */
class UdpAudioBroadcaster(
    private val udpPort: Int = AudioStreamConfig.UDP_PORT,
    private val spatial8D: Spatial8DProcessor = Spatial8DProcessor(),
) {
    private val tag = "SyncPlayUdpTx"
    private val running = AtomicBoolean(false)
    private val sequence = AtomicLong(0)

    data class ClientTarget(
        val deviceId: String,
        val ipAddress: String,
        val channel: SpeakerChannel = SpeakerChannel.STEREO,
    )

    private val targets = ConcurrentHashMap<String, ClientTarget>()

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

    fun setEightDEnabled(enabled: Boolean) {
        spatial8D.enabled = enabled
    }

    fun setTargets(clients: Collection<ClientTarget>) {
        targets.clear()
        clients.forEach { target ->
            if (target.ipAddress.isNotBlank() && target.ipAddress != "unknown") {
                targets[target.deviceId] = target
            }
        }
    }

    /**
     * Builds a packet for [pcm] and unicasts a (possibly remapped) copy to each client.
     * @return presentation timestamp attached to the packets
     */
    fun sendPcmFrame(pcm: ByteArray, length: Int = pcm.size): Long {
        val sock = socket ?: return -1L
        if (!running.get() || targets.isEmpty()) return -1L

        val syncedNow = TimeSyncManager.getSyncedTimeMs()
        val pts = syncedNow + AudioStreamConfig.PRESENTATION_BUFFER_MS
        val seq = sequence.incrementAndGet()

        // Apply 8D once on a shared working buffer, then branch per channel role.
        val base = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
        spatial8D.processInPlace(base, base.size, syncedNow)

        val byChannel = targets.values.groupBy { it.channel }
        byChannel.forEach { (channel, group) ->
            val payload = PcmChannelRouter.applyCopy(base, base.size, channel)
            val packetBytes = UdpAudioPacket.encode(
                UdpAudioPacket(
                    sequence = seq,
                    presentationTimestampMs = pts,
                    sampleRate = AudioStreamConfig.SAMPLE_RATE_HZ,
                    channelCount = AudioStreamConfig.CHANNEL_COUNT,
                    pcm = payload,
                )
            )
            group.forEach { target ->
                val dest = InetSocketAddress(target.ipAddress, udpPort)
                runCatching {
                    sock.send(DatagramPacket(packetBytes, packetBytes.size, dest))
                }.onFailure {
                    Log.w(tag, "UDP send failed → $dest: ${it.message}")
                }
            }
        }
        return pts
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        targets.clear()
        Log.i(tag, "UDP broadcaster stopped")
    }
}
