package com.journeybills.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.journeybills.R

// Configure Google Fonts
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val displayFont = FontFamily(Font(googleFont = GoogleFont("Nunito"), fontProvider = provider))
val bodyFont = FontFamily(Font(googleFont = GoogleFont("Nunito"), fontProvider = provider))

val Typography =
  Typography(
    displayLarge = TextStyle(
      fontFamily = displayFont,
      fontWeight = FontWeight.Bold,
      fontSize = 57.sp,
      lineHeight = 64.sp,
      letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
      fontFamily = displayFont,
      fontWeight = FontWeight.Bold,
      fontSize = 45.sp,
      lineHeight = 52.sp,
      letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
      fontFamily = displayFont,
      fontWeight = FontWeight.Bold,
      fontSize = 36.sp,
      lineHeight = 44.sp,
      letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
      fontFamily = displayFont,
      fontWeight = FontWeight.Bold,
      fontSize = 22.sp,
      lineHeight = 28.sp,
      letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
      fontFamily = displayFont,
      fontWeight = FontWeight.SemiBold,
      fontSize = 16.sp,
      lineHeight = 24.sp,
      letterSpacing = 0.15.sp,
    ),
    bodyLarge =
      TextStyle(
        fontFamily = bodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
      ),
    bodyMedium = TextStyle(
      fontFamily = bodyFont,
      fontWeight = FontWeight.Normal,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
      fontFamily = displayFont,
      fontWeight = FontWeight.Medium,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.1.sp,
    )
  )
