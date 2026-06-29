package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SophisticatedCyan,
    secondary = CyberBlue,
    tertiary = SophisticatedCyan,
    background = SophisticatedBg,
    surface = SophisticatedPanel,
    onPrimary = SophisticatedBg,
    onSecondary = SophisticatedBg,
    onBackground = SophisticatedText,
    onSurface = SophisticatedText
  )

private val LightColorScheme = DarkColorScheme // Force high-tech dark theme for best pilot UX


@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme


  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
