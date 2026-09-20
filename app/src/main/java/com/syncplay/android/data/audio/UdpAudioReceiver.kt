package com.syncplay.android.data.audio

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client-side UDP listener that feeds decoded [UdpAudioPacket]s into [onPacket].
 */
class UdpAudioReceiver(
    private val port: Int = AudioStreamConfig.UDP_PORT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onPacket: (UdpAudioPacket) -> Unit,
) {
    private val tag = "SyncPlayUdpRx"
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Receiver already started" }
        val sock = DatagramSocket(port).apply {
            reuseAddress = true
            soTimeout = 1_000
            receiveBufferSize = maxOf(receiveBufferSize, 256 * 1024)
        }
        socket = sock
        job = scope.launch { receiveLoop(sock) }
        Log.i(tag, "UDP receiver listening on :$port")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
        Log.i(tag, "UDP receiver stopped")
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(UdpAudioPacket.HEADER_SIZE + UdpAudioPacket.MAX_PAYLOAD)
        while (running.get() && scope.isActive) {
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                sock.receive(datagram)
                val packet = UdpAudioPacket.decode(datagram.data, datagram.length) ?: continue
                onPacket(packet)
            } catch (_: SocketTimeoutException) {
                // keep polling so stop() can interrupt
            } catch (t: Throwable) {
                if (running.get()) {
                    Log.w(tag, "Receive error: ${t.message}")
                } else {
                    break
                }
            }
        }
    }
}
