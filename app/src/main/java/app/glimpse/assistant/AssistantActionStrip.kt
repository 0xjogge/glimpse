package app.glimpse.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.Shape
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import app.glimpse.GlimpseApplication
import app.glimpse.data.AppPreferences
import app.glimpse.ui.NativeThemeColors
import app.glimpse.ui.nativeThemeColors

enum class AssistantAction(val label: String) {
    MARK("Mark to search"),
    TRANSLATE("Translate"),
    MUSIC("Music"),
    CAMERA("Camera"),
}

/** The capture bridge and the activity use the very same controls, including the search field. */
class AssistantActionStrip(context: Context) : LinearLayout(context) {
    var onAction: (AssistantAction) -> Unit = {}
    var onSearch: (String) -> Unit = {}
    var onQueryChanged: (String) -> Unit = {}
    private val buttons = mutableMapOf<AssistantAction, TextView>()
    private val search = EditText(context)
    private val submit = ImageButton(context)
    private var colors =
        nativeThemeColors(
            context,
            (context.applicationContext as? GlimpseApplication)?.graph?.currentPreferences?.value
                ?: AppPreferences(),
        )

    var selectedAction: AssistantAction? = null
        set(value) {
            field = value
            buttons.forEach { (action, button) ->
                button.isSelected = action == value
                button.background =
                    ripple(if (action == value) colors.selection else Color.TRANSPARENT, 20f)
            }
        }

    var query: String
        get() = search.text.toString()
        set(value) {
            if (query != value) {
                search.setText(value)
                search.setSelection(value.length)
            }
        }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        elevation = dp(6).toFloat()
        val searchRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        search.apply {
            hint = "Search this screen…"
            contentDescription = "Search this screen"
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(dp(12), 0, dp(4), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    onSearch(query)
                    true
                } else false
            }
            doAfterTextChanged { onQueryChanged(it.toString()) }
        }
        searchRow.addView(search, LayoutParams(0, dp(48), 1f))
        submit.apply {
            // The platform's legacy bitmap has translucent gray ink even after tinting.
            setImageDrawable(
                ShapeDrawable(
                        object : Shape() {
                            override fun draw(canvas: Canvas, paint: Paint) {
                                paint.isAntiAlias = true
                                paint.style = Paint.Style.STROKE
                                paint.strokeWidth = width * .1f
                                paint.strokeCap = Paint.Cap.ROUND
                                canvas.drawCircle(width * .42f, height * .42f, width * .27f, paint)
                                canvas.drawLine(
                                    width * .62f,
                                    height * .62f,
                                    width * .87f,
                                    height * .87f,
                                    paint,
                                )
                            }
                        },
                    )
                    .apply {
                        intrinsicWidth = dp(24)
                        intrinsicHeight = dp(24)
                    },
            )
            contentDescription = "Search"
            background = ripple(Color.TRANSPARENT, 24f)
            setOnClickListener { onSearch(query) }
        }
        searchRow.addView(submit, LayoutParams(dp(48), dp(48)))
        addView(searchRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        AssistantAction.entries.forEach { action ->
            val button =
                TextView(context).apply {
                    text = action.label
                    contentDescription = action.label
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    minHeight = dp(48)
                    isClickable = true
                    isFocusable = true
                    accessibilityDelegate =
                        object : android.view.View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: android.view.View,
                                info: android.view.accessibility.AccessibilityNodeInfo,
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = android.widget.Button::class.java.name
                            }
                        }
                    background = ripple(Color.TRANSPARENT, 20f)
                    setOnClickListener { onAction(action) }
                }
            buttons[action] = button
            actions.addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The bridge should not summon the keyboard just by becoming visible.
        isFocusableInTouchMode = true
        applyColors()
    }

    /** Recolors existing controls without replacing the EditText or disturbing focus/the IME. */
    fun setColors(colors: NativeThemeColors) {
        if (this.colors == colors) return
        this.colors = colors
        applyColors()
    }

    private fun applyColors() {
        background = shape(colors.surface, 28f)
        val foreground =
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(),
                ),
                intArrayOf(colors.disabled, colors.onSelection, colors.foreground),
            )
        search.setTextColor(foreground)
        search.setHintTextColor(colors.secondary)
        search.setLinkTextColor(colors.accent)
        search.highlightColor =
            androidx.core.graphics.ColorUtils.setAlphaComponent(colors.accent, 80)
        search.textCursorDrawable =
            search.textCursorDrawable?.mutate()?.apply { setTint(colors.accent) }
        search.textSelectHandleLeft?.mutate()?.setTint(colors.accent)
        search.textSelectHandleRight?.mutate()?.setTint(colors.accent)
        search.textSelectHandle?.mutate()?.setTint(colors.accent)
        submit.imageTintList = foreground
        submit.background = ripple(Color.TRANSPARENT, 24f)
        buttons.values.forEach { it.setTextColor(foreground) }
        selectedAction = selectedAction
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
        }

    private fun ripple(color: Int, radius: Float) =
        RippleDrawable(
            ColorStateList.valueOf(colors.ripple),
            shape(color, radius),
            shape(Color.WHITE, radius),
        )
}
