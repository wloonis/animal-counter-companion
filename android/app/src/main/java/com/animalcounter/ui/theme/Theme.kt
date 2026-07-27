package com.animalcounter.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme. Dark theme is forced (no dayNight variant in the manifest
 * theme), using Material You [dynamicDarkColorScheme] on API 31+ (always
 * available since minSdk 33) and a fixed dark fallback palette otherwise
 * (previews / safety).
 */
@Composable
fun AnimalCounterTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        DarkColorFallback
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}