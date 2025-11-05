package com.example.geminichat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.runtime.Composable

@Composable
fun MiraEdgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    Material3Theme(
        colorScheme = colorScheme,
        typography = MiraEdgeTypography,
        content = content
    )
}
