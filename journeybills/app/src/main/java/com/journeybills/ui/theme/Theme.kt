package com.journeybills.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalThemeIsDark = staticCompositionLocalOf { false }

private val DarkColorScheme =
  darkColorScheme(
      primary = BrandPrimary,
      onPrimary = Color.Black,
      primaryContainer = BrandPrimaryContainer,
      onPrimaryContainer = BrandOnPrimaryContainer,
      secondaryContainer = BrandSurface,
      background = BrandBackground,
      surface = BrandSurface,
      onBackground = BrandText,
      onSurface = BrandText,
      surfaceVariant = Color(0xFF1E1E24),
      onSurfaceVariant = Color(0xFFC4C4CD),
      outline = Color(0xFF2C2C35),
      error = RedNegative,
      onError = Color.Black
  )

private val LightColorScheme =
  lightColorScheme(
      primary = LightPrimary,
      onPrimary = Color.White,
      primaryContainer = LightPrimaryContainer,
      onPrimaryContainer = LightOnPrimaryContainer,
      secondaryContainer = LightSurface,
      background = LightBackground,
      surface = LightSurface,
      onBackground = LightText,
      onSurface = LightText,
      surfaceVariant = Color(0xFFEEEEF2),
      onSurfaceVariant = Color(0xFF5A5A64),
      outline = Color(0xFFE0E0E5),
      error = Color(0xFFBA1A1A),
      onError = Color.White
  )

@Composable
fun animateColorScheme(targetColorScheme: ColorScheme): ColorScheme {
    val duration = 600 // 600ms transition time
    val animSpec = tween<Color>(durationMillis = duration)
    
    return ColorScheme(
        primary = animateColorAsState(targetColorScheme.primary, animSpec, label = "primary").value,
        onPrimary = animateColorAsState(targetColorScheme.onPrimary, animSpec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(targetColorScheme.primaryContainer, animSpec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(targetColorScheme.onPrimaryContainer, animSpec, label = "onPrimaryContainer").value,
        inversePrimary = animateColorAsState(targetColorScheme.inversePrimary, animSpec, label = "inversePrimary").value,
        secondary = animateColorAsState(targetColorScheme.secondary, animSpec, label = "secondary").value,
        onSecondary = animateColorAsState(targetColorScheme.onSecondary, animSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(targetColorScheme.secondaryContainer, animSpec, label = "secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(targetColorScheme.onSecondaryContainer, animSpec, label = "onSecondaryContainer").value,
        tertiary = animateColorAsState(targetColorScheme.tertiary, animSpec, label = "tertiary").value,
        onTertiary = animateColorAsState(targetColorScheme.onTertiary, animSpec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(targetColorScheme.tertiaryContainer, animSpec, label = "tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(targetColorScheme.onTertiaryContainer, animSpec, label = "onTertiaryContainer").value,
        background = animateColorAsState(targetColorScheme.background, animSpec, label = "background").value,
        onBackground = animateColorAsState(targetColorScheme.onBackground, animSpec, label = "onBackground").value,
        surface = animateColorAsState(targetColorScheme.surface, animSpec, label = "surface").value,
        onSurface = animateColorAsState(targetColorScheme.onSurface, animSpec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(targetColorScheme.surfaceVariant, animSpec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(targetColorScheme.onSurfaceVariant, animSpec, label = "onSurfaceVariant").value,
        surfaceTint = animateColorAsState(targetColorScheme.surfaceTint, animSpec, label = "surfaceTint").value,
        inverseSurface = animateColorAsState(targetColorScheme.inverseSurface, animSpec, label = "inverseSurface").value,
        inverseOnSurface = animateColorAsState(targetColorScheme.inverseOnSurface, animSpec, label = "inverseOnSurface").value,
        error = animateColorAsState(targetColorScheme.error, animSpec, label = "error").value,
        onError = animateColorAsState(targetColorScheme.onError, animSpec, label = "onError").value,
        errorContainer = animateColorAsState(targetColorScheme.errorContainer, animSpec, label = "errorContainer").value,
        onErrorContainer = animateColorAsState(targetColorScheme.onErrorContainer, animSpec, label = "onErrorContainer").value,
        outline = animateColorAsState(targetColorScheme.outline, animSpec, label = "outline").value,
        outlineVariant = animateColorAsState(targetColorScheme.outlineVariant, animSpec, label = "outlineVariant").value,
        scrim = animateColorAsState(targetColorScheme.scrim, animSpec, label = "scrim").value
    )
}

@Composable
fun JourneyBillsTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false, // Force green branding
  content: @Composable () -> Unit,
) {
  val baseColorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  val animatedColorScheme = animateColorScheme(baseColorScheme)

  CompositionLocalProvider(LocalThemeIsDark provides darkTheme) {
    MaterialTheme(colorScheme = animatedColorScheme, typography = Typography, content = content)
  }
}
