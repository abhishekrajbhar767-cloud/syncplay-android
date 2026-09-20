package com.syncplay.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncplay.android.data.model.SpeakerChannel
import com.syncplay.android.ui.theme.Ink
import com.syncplay.android.ui.theme.InkElevated
import com.syncplay.android.ui.theme.Line
import com.syncplay.android.ui.theme.Mist
import com.syncplay.android.ui.theme.Sand
import com.syncplay.android.ui.theme.TealBright

@Composable
fun HostToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = Sand)
            if (supporting != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = supporting, style = MaterialTheme.typography.bodyMedium, color = Mist)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = TealBright,
                uncheckedThumbColor = Sand,
                uncheckedTrackColor = Line,
            ),
        )
    }
}

@Composable
fun ConnectedClientRow(
    title: String,
    subtitle: String,
    rttLabel: String?,
    channel: SpeakerChannel,
    onChannelSelected: (SpeakerChannel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Sand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = Mist)
            }
            if (rttLabel != null) {
                Text(text = rttLabel, style = MaterialTheme.typography.bodyMedium, color = TealBright)
            }
        }

        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(InkElevated)
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mist,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = channelLabel(channel),
                    style = MaterialTheme.typography.labelLarge,
                    color = TealBright,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(InkElevated),
            ) {
                SpeakerChannel.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = channelLabel(option),
                                color = if (option == channel) TealBright else Sand,
                            )
                        },
                        onClick = {
                            expanded = false
                            onChannelSelected(option)
                        },
                    )
                }
            }
        }
    }
}

fun channelLabel(channel: SpeakerChannel): String = when (channel) {
    SpeakerChannel.STEREO -> "Stereo (L+R)"
    SpeakerChannel.LEFT_CHANNEL -> "Left only"
    SpeakerChannel.RIGHT_CHANNEL -> "Right only"
}
