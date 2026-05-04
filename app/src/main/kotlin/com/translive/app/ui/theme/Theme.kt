package com.translive.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand colors
val DeepBlue = Color(0xFF0A2463)
val Teal = Color(0xFF1DE9B6)
val DarkSurface = Color(0xFF0D1117)
val DarkSurfaceVariant = Color(0xFF161B22)
val AccentBlue = Color(0xFF3D85C6)
val SoftWhite = Color(0xFFF0F6FC)

private val DarkColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = DarkSurface,
    primaryContainer = Color(0xFF004D40),
    secondary = AccentBlue,
    onSecondary = Color.White,
    background = DarkSurface,
    surface = DarkSurfaceVariant,
    surfaceVariant = Color(0xFF1C2128),
    onBackground = SoftWhite,
    onSurface = SoftWhite,
    outline = Color(0xFF30363D),
)

private val LightColorScheme = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary = Teal,
    onSecondary = DarkSurface,
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF2F6),
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
    outline = Color(0xFFD0D7DE),
)

@Composable
fun TransLiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(LocalContext.current).copy(
                background = DarkSurface,
                surface = DarkSurfaceVariant
            )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme ->
            dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

val Typography = Typography(
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)
