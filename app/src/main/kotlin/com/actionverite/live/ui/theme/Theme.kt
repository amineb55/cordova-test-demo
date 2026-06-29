package com.actionverite.live.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Magenta300,
    onPrimary = Ink900,
    secondary = Indigo300,
    onSecondary = Ink900,
    tertiary = Teal400,
    background = Ink900,
    onBackground = Cloud50,
    surface = Ink800,
    onSurface = Cloud50,
    surfaceVariant = Ink700,
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Magenta500,
    onPrimary = Cloud50,
    secondary = Indigo500,
    onSecondary = Cloud50,
    tertiary = Teal400,
    background = Cloud50,
    onBackground = Ink900,
    surface = Cloud100,
    onSurface = Ink900,
    surfaceVariant = Cloud100,
    error = Danger,
)

/**
 * App theme honouring the spec's "mode clair/sombre" with Material You dynamic
 * colour on Android 12+ and a curated brand palette as the fallback.
 */
@Composable
fun ActionVeriteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
