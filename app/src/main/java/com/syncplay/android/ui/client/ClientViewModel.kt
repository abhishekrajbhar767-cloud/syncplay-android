package com.syncplay.android.ui.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.data.model.DiscoveredHost
import com.syncplay.android.data.model.SpeakerChannel
import com.syncplay.android.data.repository.PartyRepository
import com.syncplay.android.data.sync.TimeSyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ClientUiState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val deviceName: String = "",
    val errorMessage: String? = null,
    val timeSync: TimeSyncManager.SyncState = TimeSyncManager.SyncState(),
    val manualOffsetMs: Int = 0,
    val playbackActive: Boolean = false,
    val audioStatusMessage: String? = null,
    val speakerChannel: SpeakerChannel = SpeakerChannel.STEREO,
    val calibrationHint: Boolean = false,
)

class ClientViewModel(
    private val repository: PartyRepository,
) : ViewModel() {

    private val connection = combine(
        repository.status,
        repository.discoveredHosts,
        repository.timeSyncState,
    ) { status, hosts, timeSync ->
        ConnectionSlice(status, hosts, timeSync)
    }

    private val playback = combine(
        repository.manualOffsetMs,
        repository.clientPlaybackActive,
        repository.audioStatusMessage,
        repository.clientSpeakerChannel,
        repository.clientCalibrationHint,
    ) { offset, active, audioMsg, channel, calib ->
        PlaybackSlice(offset, active, audioMsg, channel, calib)
    }

    val uiState: StateFlow<ClientUiState> = combine(connection, playback) { conn, play ->
        ClientUiState(
            status = conn.status,
            discoveredHosts = conn.hosts,
            deviceName = repository.deviceName,
            errorMessage = (conn.status as? ConnectionStatus.Failed)?.message,
            timeSync = conn.timeSync,
            manualOffsetMs = play.offset,
            playbackActive = play.active,
            audioStatusMessage = play.audioMsg,
            speakerChannel = play.channel,
            calibrationHint = play.calibration,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ClientUiState(deviceName = repository.deviceName),
    )

    fun startJoining() {
        repository.startDiscovery()
    }

    fun leaveParty() {
        repository.leaveParty()
    }

    fun setManualOffsetMs(offsetMs: Int) {
        repository.setManualOffsetMs(offsetMs)
    }

    private data class ConnectionSlice(
        val status: ConnectionStatus,
        val hosts: List<DiscoveredHost>,
        val timeSync: TimeSyncManager.SyncState,
    )

    private data class PlaybackSlice(
        val offset: Int,
        val active: Boolean,
        val audioMsg: String?,
        val channel: SpeakerChannel,
        val calibration: Boolean,
    )

    companion object {
        fun factory(repository: PartyRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ClientViewModel(repository) as T
                }
            }
    }
}
