package com.syncplay.android.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.syncplay.android.data.model.ConnectedDevice
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.data.repository.PartyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HostUiState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val devices: List<ConnectedDevice> = emptyList(),
    val localAddresses: List<String> = emptyList(),
    val port: Int? = null,
    val hostName: String = "",
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val audioStreaming: Boolean = false,
    val audioStatusMessage: String? = null,
)

class HostViewModel(
    private val repository: PartyRepository,
) : ViewModel() {

    private val networking = combine(
        repository.status,
        repository.connectedDevices,
        repository.localAddresses,
        repository.hostPort,
    ) { status, devices, addresses, port ->
        NetworkingSlice(status, devices, addresses, port)
    }

    val uiState: StateFlow<HostUiState> = combine(
        networking,
        repository.audioStreaming,
        repository.audioStatusMessage,
    ) { net, streaming, audioMsg ->
        HostUiState(
            status = net.status,
            devices = net.devices,
            localAddresses = net.addresses,
            port = net.port,
            hostName = repository.deviceName,
            isBusy = net.status is ConnectionStatus.Starting,
            errorMessage = (net.status as? ConnectionStatus.Failed)?.message,
            audioStreaming = streaming,
            audioStatusMessage = audioMsg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HostUiState(hostName = repository.deviceName),
    )

    fun startHosting() {
        viewModelScope.launch {
            runCatching { repository.startHosting() }
        }
    }

    fun stopHosting() {
        repository.stopHostAudioStreaming()
        repository.stopHosting()
    }

    fun startAudioStreaming(resultCode: Int, data: android.content.Intent) {
        repository.startHostAudioStreaming(resultCode, data)
    }

    fun stopAudioStreaming() {
        repository.stopHostAudioStreaming()
    }

    private data class NetworkingSlice(
        val status: ConnectionStatus,
        val devices: List<ConnectedDevice>,
        val addresses: List<String>,
        val port: Int?,
    )

    companion object {
        fun factory(repository: PartyRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HostViewModel(repository) as T
                }
            }
    }
}
