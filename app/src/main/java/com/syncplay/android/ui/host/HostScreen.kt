package com.syncplay.android.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.ui.components.DeviceRow
import com.syncplay.android.ui.components.PrimaryActionButton
import com.syncplay.android.ui.components.SectionDivider
import com.syncplay.android.ui.components.StatusPulse
import com.syncplay.android.ui.components.SyncPlayBackground
import com.syncplay.android.ui.theme.Coral
import com.syncplay.android.ui.theme.Mist
import com.syncplay.android.ui.theme.Sand
import com.syncplay.android.ui.theme.TealBright

@Composable
fun HostScreen(
    state: HostUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state.status is ConnectionStatus.Idle ||
            state.status is ConnectionStatus.Stopped ||
            state.status is ConnectionStatus.Failed
        ) {
            onStart()
        }
    }

    val statusLabel = when (val status = state.status) {
        is ConnectionStatus.Starting -> "Starting host…"
        is ConnectionStatus.Hosting -> "Hosting · port ${status.port}"
        is ConnectionStatus.Failed -> "Failed: ${status.message}"
        is ConnectionStatus.Stopped -> "Stopped"
        else -> "Preparing…"
    }
    val isLive = state.status is ConnectionStatus.Hosting

    SyncPlayBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                text = "SyncPlay",
                style = MaterialTheme.typography.headlineMedium,
                color = Sand,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Host · ${state.hostName}",
                style = MaterialTheme.typography.bodyLarge,
                color = Mist,
            )
            Spacer(modifier = Modifier.height(20.dp))
            StatusPulse(active = isLive, label = statusLabel)

            if (state.localAddresses.isNotEmpty() && state.port != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.localAddresses.joinToString("  ·  ") { "$it:${state.port}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TealBright.copy(alpha = 0.85f),
                )
            }

            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Coral,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "Connected Devices",
                style = MaterialTheme.typography.titleLarge,
                color = Sand,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (state.devices.isEmpty()) {
                    "Waiting for clients to join…"
                } else {
                    "${state.devices.size} live · heartbeat every 2s"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SectionDivider()

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceRow(
                        title = device.deviceName,
                        subtitle = "${device.ipAddress}:${device.port}",
                        trailing = device.roundTripMs?.let { "${it} ms" },
                    )
                    SectionDivider()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            PrimaryActionButton(
                label = "Stop Hosting",
                onClick = {
                    onStop()
                    onBack()
                },
                emphasized = false,
            )
        }
    }
}
