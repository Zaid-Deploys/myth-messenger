package com.myth.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color.Black,
    secondary = EmeraldGreen,
    onSecondary = Color.Black,
    tertiary = CrimsonAlert,
    background = MidnightDark,
    onBackground = TextPrimaryDark,
    surface = SlateDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = AccentCyan,
    onPrimary = Color.White,
    secondary = EmeraldGreen,
    onSecondary = Color.White,
    tertiary = CrimsonAlert,
    background = SlateLight,
    onBackground = TextPrimaryLight,
    surface = CardLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SlateLight,
    onSurfaceVariant = TextSecondaryLight
)

@Composable
fun MythTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
