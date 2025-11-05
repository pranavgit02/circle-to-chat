package com.example.geminichat.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val MiraGreen = Color(0xFF1BA33F)
private val MiraGreenLight = Color(0xFFD1F5DA)
private val MiraWhite = Color(0xFFFFFFFF)

val LightColors = lightColorScheme(
    primary = MiraGreen,
    onPrimary = MiraWhite,
    primaryContainer = MiraGreenLight,
    onPrimaryContainer = Color(0xFF052712),
    secondary = Color(0xFF3C4F3F),
    onSecondary = MiraWhite,
    secondaryContainer = Color(0xFFE2E7E3),
    onSecondaryContainer = Color(0xFF1E2A22),
    tertiary = Color(0xFF4F6354),
    onTertiary = MiraWhite,
    background = MiraWhite,
    onBackground = Color(0xFF0F2113),
    surface = Color(0xFFF6FBF6),
    onSurface = Color(0xFF0F2113),
    surfaceVariant = Color(0xFFE1E8E2),
    onSurfaceVariant = Color(0xFF424A43),
    outline = Color(0xFF707970),
    outlineVariant = Color(0xFFC4C9C4),
    error = Color(0xFFB3261E),
    onError = MiraWhite
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF63D58E),
    onPrimary = Color(0xFF003918),
    primaryContainer = Color(0xFF005325),
    onPrimaryContainer = Color(0xFF8FF4AE),
    secondary = Color(0xFFB7CCBB),
    onSecondary = Color(0xFF243627),
    secondaryContainer = Color(0xFF3A4C3C),
    onSecondaryContainer = Color(0xFFCFE4CF),
    tertiary = Color(0xFF9BD0A0),
    onTertiary = Color(0xFF10391C),
    background = Color(0xFF05150B),
    onBackground = Color(0xFFD6E8D9),
    surface = Color(0xFF0A2212),
    onSurface = Color(0xFFD6E8D9),
    surfaceVariant = Color(0xFF3B4F3F),
    onSurfaceVariant = Color(0xFFBACCB5),
    outline = Color(0xFF829683),
    outlineVariant = Color(0xFF3B4F3F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)
