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
)

class HostViewModel(
    private val repository: PartyRepository,
) : ViewModel() {

    val uiState: StateFlow<HostUiState> = combine(
        repository.status,
        repository.connectedDevices,
        repository.localAddresses,
        repository.hostPort,
    ) { status, devices, addresses, port ->
        HostUiState(
            status = status,
            devices = devices,
            localAddresses = addresses,
            port = port,
            hostName = repository.deviceName,
            isBusy = status is ConnectionStatus.Starting,
            errorMessage = (status as? ConnectionStatus.Failed)?.message,
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
        repository.stopHosting()
    }

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
