package com.google.android.desktop.apprestorer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
  primary = Color(0xFF1A73E8),
  secondary = Color(0xFF188038),
  tertiary = Color(0xFFE37400),
)
private val Dark = darkColorScheme(
  primary = Color(0xFF8AB4F8),
  secondary = Color(0xFF81C995),
  tertiary = Color(0xFFFDD663),
)

@Composable
fun RestorerTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val ctx = LocalContext.current
  val scheme = when {
    Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    dark -> Dark
    else -> Light
  }
  MaterialTheme(colorScheme = scheme, content = content)
}
