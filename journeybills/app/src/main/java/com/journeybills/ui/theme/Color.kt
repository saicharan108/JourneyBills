package com.journeybills.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

val BrandBackground = Color(0xFF0F0F13) // Deeper, more luxurious dark
val BrandText = Color(0xFFF1F1F3) // Slightly off-white for less eye strain
val BrandPrimaryContainer = Color(0xFF1E1E24)
val BrandOnPrimaryContainer = Color(0xFFE0E0E0)
val BrandPrimary = Color(0xFFC7FF8E) // Striking neon muted green
val BrandSurface = Color(0xFF16161B) // Slightly lighter than background
val BrandBorder = Color(0xFF2C2C35)

val LightBackground = Color(0xFFF7F7F8)
val LightText = Color(0xFF1A1A1F)
val LightPrimaryContainer = Color(0xFFE1E1E6)
val LightOnPrimaryContainer = Color(0xFF2E2E36)
val LightPrimary = Color(0xFF4DBB3A)
val LightSurface = Color(0xFFFFFFFF)
val LightBorder = Color(0xFFE0E0E5)

// Theme-specific colors: Lighter/Pastel for Dark Theme; Darker/Richer for Light Theme
val CardGreen = Color(0xFFB4F8C8)
val CardYellow = Color(0xFFFCE181)
val CardPurple = Color(0xFFCBAACB)
val CardPink = Color(0xFFFFB2E6)

val CardGreenDark = Color(0xFF2E7D32)
val CardYellowDark = Color(0xFFE65100)
val CardPurpleDark = Color(0xFF6A1B9A)
val CardPinkDark = Color(0xFFC2185B)

val RedNegative = Color(0xFFFF8989)
val GreenPositive = Color(0xFFA5FF90)
val GreenPositiveLight = Color(0xFF2E7D32)

@Composable
fun getCardGreen(): Color = if (LocalThemeIsDark.current) CardGreen else CardGreenDark

@Composable
fun getGreenPositive(): Color = if (LocalThemeIsDark.current) GreenPositive else GreenPositiveLight

@Composable
fun getCardYellow(): Color = if (LocalThemeIsDark.current) CardYellow else CardYellowDark

@Composable
fun getCardPurple(): Color = if (LocalThemeIsDark.current) CardPurple else CardPurpleDark

@Composable
fun getCardPink(): Color = if (LocalThemeIsDark.current) CardPink else CardPinkDark

