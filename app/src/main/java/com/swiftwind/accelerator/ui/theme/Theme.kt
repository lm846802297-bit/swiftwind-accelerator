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

val Ink = Color(0xFF0B0710)
val Deep = Color(0xFF150B1B)
val Surface = Color(0xFF1B1023)
val SurfaceHi = Color(0xFF2A1733)
val Pink = Color(0xFFFF83BA)
val PinkDeep = Color(0xFFD94C8B)
val Rose = Color(0xFFFFB7D3)
val Gold = Color(0xFFFFD37A)
val Mint = Color(0xFF62E6C2)
val MintDeep = Color(0xFF18B990)
val Sky = Color(0xFFB79CFF)
val TextPrimary = Color(0xFFFFF4FA)
val TextSecondary = Color(0xFFB89BAD)
val Stroke = Color(0x33FFC1DC)
val Danger = Color(0xFFFF718E)

private val DarkColors = darkColorScheme(
    primary = Pink,
    onPrimary = Color(0xFF2A0B1B),
    primaryContainer = Color(0xFF4A1733),
    onPrimaryContainer = Rose,
    secondary = Rose,
    onSecondary = Color(0xFF2A0B1B),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = TextSecondary,
    outline = Color(0x55FFC1DC),
    error = Danger,
    onError = Color(0xFF2A0009),
    errorContainer = Color(0xFF44121F)
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
