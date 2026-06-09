package com.timer.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.timer.app.data.AccentPalettes
import com.timer.app.data.AppPreferencesSnapshot
import com.timer.app.data.ThemeModes

private data class Palette(val primary: Color, val secondary: Color, val tertiary: Color)

private val bluePalette = Palette(Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFF0F766E))
private val violetPalette = Palette(Color(0xFF7C3AED), Color(0xFF2563EB), Color(0xFFD946EF))
private val emeraldPalette = Palette(Color(0xFF059669), Color(0xFF0284C7), Color(0xFF0F766E))
private val sunsetPalette = Palette(Color(0xFFF97316), Color(0xFFDC2626), Color(0xFFCA8A04))

@Composable
fun TimerTheme(
    preferences: AppPreferencesSnapshot = AppPreferencesSnapshot(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (preferences.themeMode) {
        ThemeModes.LIGHT -> false
        ThemeModes.DARK -> true
        else -> systemDark
    }
    val palette = when (preferences.accentPalette) {
        AccentPalettes.VIOLET -> violetPalette
        AccentPalettes.EMERALD -> emeraldPalette
        AccentPalettes.SUNSET -> sunsetPalette
        else -> bluePalette
    }

    val fallbackLight = lightColorScheme(
        primary = palette.primary,
        secondary = palette.secondary,
        tertiary = palette.tertiary,
        background = Color(0xFFF8FAFC),
        surface = Color(0xFFFFFFFF)
    )
    val fallbackDark = darkColorScheme(
        primary = palette.primary.copy(alpha = 0.88f),
        secondary = palette.secondary.copy(alpha = 0.88f),
        tertiary = palette.tertiary.copy(alpha = 0.88f),
        background = Color(0xFF020617),
        surface = Color(0xFF0F172A)
    )

    val colorScheme = when {
        preferences.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> fallbackDark
        else -> fallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TimerTypography,
        content = content
    )
}
