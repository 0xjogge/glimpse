package app.glimpse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import app.glimpse.data.AppPreferences

@Composable
fun GlimpseTheme(preferences: AppPreferences, content: @Composable () -> Unit) {
    val dark =
        when (preferences.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val context = LocalContext.current
    ConfigureSystemBars(dark)
    val colors =
        if (preferences.dynamicColor) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) {
            darkColorScheme(
                primary = Color(0xFFA0D3AA),
                secondary = Color(0xFFBACCB9),
                tertiary = Color(0xFFA4CED3),
                background = Color(0xFF101510),
                surface = Color(0xFF101510),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF345F40),
                secondary = Color(0xFF53634F),
                tertiary = Color(0xFF38656B),
                background = Color(0xFFF6F9F1),
                surface = Color(0xFFF6F9F1),
            )
        }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun ConfigureSystemBars(dark: Boolean) {
    val context = LocalContext.current
    SideEffect {
        var current = context
        while (
            current is android.content.ContextWrapper && current !is android.app.Activity
        ) current = current.baseContext
        val activity = current as? android.app.Activity
        activity?.window?.let { window ->
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
