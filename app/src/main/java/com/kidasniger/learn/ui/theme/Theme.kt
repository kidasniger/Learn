package com.kidasniger.learn.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ABEFF), onPrimary = Color(0xFF002F65), secondary = Color(0xFF8BD8C5),
    tertiary = Color(0xFFFFC27A), background = Color(0xFF0B1019), surface = Color(0xFF111927),
    surfaceVariant = Color(0xFF202B3A)
)
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF245CC5), onPrimary = Color.White, secondary = Color(0xFF176B59),
    tertiary = Color(0xFF9B5700), background = Color(0xFFF6F8FC), surface = Color.White,
    surfaceVariant = Color(0xFFE8EDF6)
)
@Composable
fun WhisperLearnTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
