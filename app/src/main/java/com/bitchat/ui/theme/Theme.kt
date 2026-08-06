package com.bitchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    background = TealBackground,
    surface = TealSurface,
    error = Error,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4D1),
    onPrimary = Color(0xFF003736),
    primaryContainer = Color(0xFF004F4E),
    onPrimaryContainer = Color(0xFF9CF1ED),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3434),
    secondaryContainer = Color(0xFF324B4A),
    onSecondaryContainer = Color(0xFFCCE8E7),
    background = Color(0xFF0F1515),
    surface = Color(0xFF0F1515),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
