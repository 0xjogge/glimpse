package app.glimpse.assistant

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

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
    private val dark =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    private val surface = if (dark) Color.rgb(27, 30, 30) else Color.rgb(248, 249, 246)
    private val foregroundColor = if (dark) Color.rgb(236, 239, 235) else Color.rgb(27, 32, 29)
    private val accent = if (dark) Color.rgb(59, 82, 74) else Color.rgb(212, 235, 222)

    var selectedAction: AssistantAction? = null
        set(value) {
            field = value
            buttons.forEach { (action, button) ->
                button.isSelected = action == value
                button.background = ripple(if (action == value) accent else Color.TRANSPARENT, 20f)
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
        background = shape(surface, 28f)
        elevation = dp(6).toFloat()
        val searchRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        search.apply {
            hint = "Search this screen…"
            contentDescription = "Search this screen"
            textSize = 16f
            setTextColor(foregroundColor)
            setHintTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
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
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList = android.content.res.ColorStateList.valueOf(foregroundColor)
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
                    setTextColor(foregroundColor)
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
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
        }

    private fun ripple(color: Int, radius: Float) =
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x20808080),
            shape(color, radius),
            shape(Color.WHITE, radius),
        )
}
