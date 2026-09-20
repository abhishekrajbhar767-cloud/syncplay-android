package com.syncplay.android.ui.client

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.syncplay.android.data.model.ConnectionStatus
import com.syncplay.android.ui.components.PrimaryActionButton
import com.syncplay.android.ui.components.StatusPulse
import com.syncplay.android.ui.components.SyncPlayBackground
import com.syncplay.android.ui.theme.Coral
import com.syncplay.android.ui.theme.Mist
import com.syncplay.android.ui.theme.Sand
import com.syncplay.android.ui.theme.TealBright

@Composable
fun ClientScreen(
    state: ClientUiState,
    onStart: () -> Unit,
    onLeave: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state.status !is ConnectionStatus.Connected &&
            state.status !is ConnectionStatus.Connecting &&
            state.status !is ConnectionStatus.Searching
        ) {
            onStart()
        }
    }

    val sweep = rememberInfiniteTransition(label = "searchSweep")
    val sweepOffset by sweep.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweepOffset",
    )

    val statusLabel = when (val status = state.status) {
        is ConnectionStatus.Searching -> "Searching for Host…"
        is ConnectionStatus.Connecting -> "Connecting to ${status.hostName}…"
        is ConnectionStatus.Connected -> "Connected to ${status.peerName}"
        is ConnectionStatus.Failed -> "Connection issue"
        is ConnectionStatus.Stopped -> "Disconnected"
        else -> "Preparing…"
    }
    val isConnected = state.status is ConnectionStatus.Connected
    val isSearching = state.status is ConnectionStatus.Searching

    SyncPlayBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "SyncPlay",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Sand,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Client · ${state.deviceName}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Mist,
                )
                Spacer(modifier = Modifier.height(28.dp))

                Column(
                    modifier = Modifier.graphicsLayer {
                        translationX = if (isSearching) sweepOffset else 0f
                    }
                ) {
                    StatusPulse(active = isConnected || isSearching, label = statusLabel)
                }

                when (val status = state.status) {
                    is ConnectionStatus.Connected -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = status.peerAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TealBright,
                        )
                        status.sessionId?.let { session ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Session ${session.take(8)}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Mist,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        val sync = state.timeSync
                        Text(
                            text = if (sync.isSynced) {
                                val rtt = sync.rttMs?.let { "$it ms RTT" } ?: "—"
                                "Clock synced · offset ${sync.offsetMs} ms · $rtt"
                            } else {
                                "Synchronizing clocks…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sync.isSynced) TealBright else Mist,
                        )
                    }
                    is ConnectionStatus.Searching -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (state.discoveredHosts.isEmpty()) {
                                "Scanning local network for _audio_sync._tcp"
                            } else {
                                "Found ${state.discoveredHosts.size} host(s) — connecting…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mist,
                        )
                    }
                    is ConnectionStatus.Failed -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Coral,
                        )
                    }
                    else -> Unit
                }
            }

            PrimaryActionButton(
                label = "Leave Party",
                onClick = {
                    onLeave()
                    onBack()
                },
                emphasized = false,
            )
        }
    }
}
