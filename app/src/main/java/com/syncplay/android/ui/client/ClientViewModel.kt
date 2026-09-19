package com.syncplay.android.ui.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.data.model.DiscoveredHost
import com.syncplay.android.data.repository.PartyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ClientUiState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val deviceName: String = "",
    val errorMessage: String? = null,
)

class ClientViewModel(
    private val repository: PartyRepository,
) : ViewModel() {

    val uiState: StateFlow<ClientUiState> = combine(
        repository.status,
        repository.discoveredHosts,
    ) { status, hosts ->
        ClientUiState(
            status = status,
            discoveredHosts = hosts,
            deviceName = repository.deviceName,
            errorMessage = (status as? ConnectionStatus.Failed)?.message,
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
