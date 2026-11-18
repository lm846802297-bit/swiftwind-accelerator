package com.swiftwind.accelerator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF060B12)
val Deep = Color(0xFF0A1320)
val Surface = Color(0xFF111C2B)
val SurfaceHi = Color(0xFF17263A)
val Mint = Color(0xFF52E6C0)
val MintDeep = Color(0xFF19B98F)
val Sky = Color(0xFF68A9FF)
val TextPrimary = Color(0xFFEAF4FF)
val TextSecondary = Color(0xFF8FA4BD)
val Stroke = Color(0x1FFFFFFF)
val Danger = Color(0xFFFF6B7A)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00251D),
    primaryContainer = Color(0xFF0E3D33),
    onPrimaryContainer = Mint,
    secondary = Sky,
    onSecondary = Color(0xFF001A33),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = TextSecondary,
    outline = Color(0x33FFFFFF),
    error = Danger,
    onError = Color(0xFF2A0006),
    errorContainer = Color(0xFF3A1017)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun SwiftWindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
