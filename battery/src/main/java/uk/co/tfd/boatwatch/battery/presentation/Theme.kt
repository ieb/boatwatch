package uk.co.tfd.boatwatch.battery.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val WatchColors = Colors(
    primary = Color(0xFF4CAF50),
    primaryVariant = Color(0xFF388E3C),
    secondary = Color(0xFFFFB74D),
    secondaryVariant = Color(0xFFF57C00),
    error = Color(0xFFEF5350),
    background = Color.Black,
    surface = Color(0xFF1A1A1A),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onError = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBDBDBD),
)

@Composable
fun BatteryWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WatchColors,
        content = content,
    )
}
