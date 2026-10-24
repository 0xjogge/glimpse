package app.glimpse.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import app.glimpse.data.AppPreferences

/** ARGB colors for native controls; container colors are opaque and have paired foregrounds. */
data class NativeThemeColors(
    val surface: Int,
    val foreground: Int,
    val secondary: Int,
    val selection: Int,
    val onSelection: Int,
    val accent: Int,
    val disabled: Int,
    val ripple: Int,
)

fun nativeThemeColors(context: Context, preferences: AppPreferences): NativeThemeColors =
    appColorScheme(context, preferences).toNativeThemeColors()

/** Also used by AndroidView so a locally supplied MaterialTheme is respected. */
fun ColorScheme.toNativeThemeColors(): NativeThemeColors =
    NativeThemeColors(
        surface = surface.toArgb(),
        foreground = onSurface.toArgb(),
        secondary = onSurfaceVariant.toArgb(),
        selection = secondaryContainer.toArgb(),
        onSelection = onSecondaryContainer.toArgb(),
        accent = primary.toArgb(),
        disabled = onSurface.copy(alpha = .38f).toArgb(),
        ripple = onSurface.copy(alpha = .12f).toArgb(),
    )

/** Retains the activity/window context while overriding an opposite OS night mode. */
fun nativeThemeContext(context: Context, preferences: AppPreferences): Context {
    val dark = appUsesDarkTheme(context, preferences)
    return ContextThemeWrapper(
            context,
            if (dark) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert,
        )
        .apply {
            applyOverrideConfiguration(
                Configuration(context.resources.configuration).apply {
                    uiMode =
                        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES
                            else Configuration.UI_MODE_NIGHT_NO
                },
            )
        }
}

/** Platform dialogs keep their standard button IDs, layout and accessibility behavior. */
internal fun AlertDialog.applyNativeTheme(colors: NativeThemeColors) {
    window?.setBackgroundDrawable(
        GradientDrawable().apply {
            setColor(colors.surface)
            cornerRadius = 28 * context.resources.displayMetrics.density
        },
    )
    fun tint(view: View) {
        if (view is TextView) setTextColors(view, colors)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) tint(view.getChildAt(index))
        }
    }
    window?.decorView?.let(::tint)
    for (id in
        listOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL,
        )) {
        getButton(id)?.setTextColor(colors.accent)
    }
}

private fun setTextColors(view: TextView, colors: NativeThemeColors) {
    view.setTextColor(colors.foreground)
    view.setHintTextColor(colors.secondary)
    view.setLinkTextColor(colors.accent)
}
