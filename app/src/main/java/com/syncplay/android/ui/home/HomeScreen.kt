package com.syncplay.android.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.syncplay.android.ui.components.PrimaryActionButton
import com.syncplay.android.ui.components.SyncPlayBackground
import com.syncplay.android.ui.theme.Mist
import com.syncplay.android.ui.theme.Sand
import com.syncplay.android.ui.theme.TealBright
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onHostParty: () -> Unit,
    onJoinParty: () -> Unit,
) {
    val brandAlpha = remember { Animatable(0f) }
    val brandOffset = remember { Animatable(18f) }
    val actionsAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            brandAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
        launch {
            brandOffset.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        }
        launch {
            actionsAlpha.animateTo(1f, tween(650, delayMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    SyncPlayBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .alpha(brandAlpha.value)
                    .graphicsLayer { translationY = brandOffset.value },
            ) {
                Text(
                    text = "SyncPlay",
                    style = MaterialTheme.typography.displayLarge,
                    color = Sand,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "One host. Many phones. Shared sound on the local network.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Mist,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(actionsAlpha.value),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Same Wi‑Fi or hotspot. No internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TealBright.copy(alpha = 0.9f),
                )
                PrimaryActionButton(label = "Host Party", onClick = onHostParty)
                PrimaryActionButton(
                    label = "Join Party",
                    onClick = onJoinParty,
                    emphasized = false,
                )
            }
        }
    }
}
