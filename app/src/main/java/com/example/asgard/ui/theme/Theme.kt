package com.example.asgard.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = VibrantRed,
    onPrimary = Color.Black,
    primaryContainer = DeepDarkSurfaceVariant,
    onPrimaryContainer = VibrantRed,
    secondary = VibrantBlue,
    onSecondary = Color.White,
    secondaryContainer = DeepDarkSurfaceVariant,
    onSecondaryContainer = VibrantBlue,
    tertiary = VibrantGreen,
    onTertiary = Color.Black,
    background = DeepDarkBase,
    surface = DeepDarkSurface,
    onSurface = TextHighEmphasis,
    surfaceVariant = DeepDarkSurfaceVariant,
    onSurfaceVariant = TextMediumEmphasis,
    outline = DeepDarkSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = VibrantRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDE8E8),
    onPrimaryContainer = VibrantRed,
    secondary = VibrantBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F0FE),
    onSecondaryContainer = VibrantBlue,
    tertiary = VibrantGreen,
    onTertiary = Color.White,
    background = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color.DarkGray,
    outline = Color(0xFFE0E0E0)
)

@Composable
fun AsgardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}