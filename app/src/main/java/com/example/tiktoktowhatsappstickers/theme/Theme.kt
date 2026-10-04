package com.example.tiktoktowhatsappstickers.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = WhatsAppGreen,
    onPrimary = Color.White,
    primaryContainer = WhatsAppDarkGreen,
    onPrimaryContainer = Color.White,
    secondary = TikTokPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A101C),
    onSecondaryContainer = Color.White,
    tertiary = TikTokCyan,
    onTertiary = Color.Black,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface1,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = ErrorRed,
    errorContainer = Color(0xFF3B1218),
    onErrorContainer = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = WhatsAppDarkGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F6DC),
    onPrimaryContainer = Color(0xFF00391C),
    secondary = TikTokPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DF),
    onSecondaryContainer = Color(0xFF3F0010),
    tertiary = Color(0xFF00838F),
    background = Color(0xFFF8F9FD),
    onBackground = Color(0xFF191C1E),
    surface = Color.White,
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFFD2D5DC),
    error = ErrorRed
)

@Composable
fun TikTokToWhatsAppStickersTheme(
    darkTheme: Boolean = true, // Default to sleek dark mode
    dynamicColor: Boolean = false, // Keep our brand identity
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
