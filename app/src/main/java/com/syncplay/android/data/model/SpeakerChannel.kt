package com.syncplay.android.data.model

/**
 * Per-client spatial / speaker role for Phase 4 routing.
 */
enum class SpeakerChannel {
    STEREO,
    LEFT_CHANNEL,
    RIGHT_CHANNEL,
    ;

    companion object {
        fun fromWire(value: String): SpeakerChannel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STEREO
    }
}
