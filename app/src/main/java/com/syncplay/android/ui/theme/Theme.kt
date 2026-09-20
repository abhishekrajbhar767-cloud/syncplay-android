package com.syncplay.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SyncPlayColorScheme = darkColorScheme(
    primary = TealBright,
    onPrimary = Ink,
    secondary = Sand,
    onSecondary = Ink,
    tertiary = Coral,
    background = Ink,
    onBackground = Sand,
    surface = InkElevated,
    onSurface = Sand,
    onSurfaceVariant = Mist,
    outline = Line,
    error = Coral,
)

@Composable
fun SyncPlayTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
        onDispose { }
    }

    MaterialTheme(
        colorScheme = SyncPlayColorScheme,
        typography = SyncPlayTypography,
        content = content,
    )
}
