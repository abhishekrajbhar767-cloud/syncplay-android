package com.syncplay.android.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary UDP audio datagram.
 *
 * ```
 * 0..3   magic "SPAU"
 * 4      version
 * 5      flags
 * 6..7   reserved
 * 8..11  sequence (u32)
 * 12..19 presentationTimestampMs (i64, synced host clock)
 * 20..23 sampleRate
 * 24..27 channelCount
 * 28..31 payloadBytes
 * 32..   PCM 16-bit LE interleaved samples
 * ```
 */
data class UdpAudioPacket(
    val sequence: Long,
    val presentationTimestampMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val pcm: ByteArray,
) {
    companion object {
        const val MAGIC = 0x53504155 // "SPAU"
        const val VERSION: Byte = 1
        const val HEADER_SIZE = 32
        const val MAX_PAYLOAD = 8 * 1024

        fun encode(packet: UdpAudioPacket): ByteArray {
            require(packet.pcm.size <= MAX_PAYLOAD) { "PCM payload too large" }
            val buffer = ByteBuffer.allocate(HEADER_SIZE + packet.pcm.size)
                .order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(MAGIC)
            buffer.put(VERSION)
            buffer.put(0) // flags
            buffer.putShort(0) // reserved
            buffer.putInt(packet.sequence.toInt())
            buffer.putLong(packet.presentationTimestampMs)
            buffer.putInt(packet.sampleRate)
            buffer.putInt(packet.channelCount)
            buffer.putInt(packet.pcm.size)
            buffer.put(packet.pcm)
            return buffer.array()
        }

        fun decode(bytes: ByteArray, length: Int = bytes.size): UdpAudioPacket? {
            if (length < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.int
            if (magic != MAGIC) return null
            val version = buffer.get()
            if (version != VERSION) return null
            buffer.get() // flags
            buffer.short // reserved
            val sequence = buffer.int.toLong() and 0xFFFF_FFFFL
            val pts = buffer.long
            val sampleRate = buffer.int
            val channelCount = buffer.int
            val payloadBytes = buffer.int
            if (payloadBytes < 0 || payloadBytes > MAX_PAYLOAD) return null
            if (buffer.remaining() < payloadBytes) return null
            val pcm = ByteArray(payloadBytes)
            buffer.get(pcm)
            return UdpAudioPacket(
                sequence = sequence,
                presentationTimestampMs = pts,
                sampleRate = sampleRate,
                channelCount = channelCount,
                pcm = pcm,
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpAudioPacket) return false
        return sequence == other.sequence &&
            presentationTimestampMs == other.presentationTimestampMs &&
            sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + presentationTimestampMs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + pcm.contentHashCode()
        return result
    }
}
