package com.syncplay.android.data.audio

import com.syncplay.android.data.model.SpeakerChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits / remaps interleaved stereo PCM16 frames for speaker roles.
 *
 * - [SpeakerChannel.STEREO]: unchanged
 * - [SpeakerChannel.LEFT_CHANNEL]: L → both outputs (R discarded)
 * - [SpeakerChannel.RIGHT_CHANNEL]: R → both outputs (L discarded)
 */
object PcmChannelRouter {
    fun applyInPlace(pcm: ByteArray, length: Int = pcm.size, channel: SpeakerChannel) {
        if (channel == SpeakerChannel.STEREO || length < 4) return
        val buffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 3 < length) {
            val left = buffer.getShort(i)
            val right = buffer.getShort(i + 2)
            val chosen = when (channel) {
                SpeakerChannel.LEFT_CHANNEL -> left
                SpeakerChannel.RIGHT_CHANNEL -> right
                SpeakerChannel.STEREO -> left // unreachable
            }
            buffer.putShort(i, chosen)
            buffer.putShort(i + 2, chosen)
            i += 4
        }
    }

    fun applyCopy(pcm: ByteArray, length: Int = pcm.size, channel: SpeakerChannel): ByteArray {
        val copy = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
        applyInPlace(copy, copy.size, channel)
        return copy
    }
}
