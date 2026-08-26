package com.ohljx.fanfan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FanFanColorScheme = lightColorScheme(
    primary = Color(0xFF204C3D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF3E9),
    onPrimaryContainer = Color(0xFF13251E),
    secondary = Color(0xFF527365),
    onSecondary = Color.White,
    background = Color(0xFFF1F4F1),
    onBackground = Color(0xFF13251E),
    surface = Color(0xFFF6F3EC),
    onSurface = Color(0xFF13251E),
    surfaceVariant = Color(0xFFE7ECE8),
    onSurfaceVariant = Color(0xFF59675F),
    outline = Color(0xFF89958F),
    error = Color(0xFFD84C58),
    onError = Color.White,
)

@Composable
fun FanFanTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FanFanColorScheme,
        typography = Typography,
        content = content,
    )
}
