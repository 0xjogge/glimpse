package app.glimpse.assistant

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import app.glimpse.MainActivity
import app.glimpse.R
import app.glimpse.data.CapturedImage
import app.glimpse.data.ImageSession

class GlimpseVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        active = this
    }

    override fun onShutdown() {
        if (active === this) active = null
        super.onShutdown()
    }

    companion object {
        private var active: GlimpseVoiceService? = null

        fun capture(): Boolean {
            val service = active ?: return false
            return runCatching {
                    service.showSession(
                        Bundle(),
                        VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
                            VoiceInteractionSession.SHOW_WITH_ASSIST,
                    )
                    true
                }
                .getOrDefault(false)
        }
    }
}

class GlimpseSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = GlimpseSession(this)
}

private class GlimpseSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private enum class Stage {
        WAITING,
        HANDED_OFF,
        CLOSED,
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.WAITING
    private var root: FrameLayout? = null
    private var strip: AssistantActionStrip? = null
    private var selected: AssistantAction? = null
    private var imageId: String? = null
    private val timeout = Runnable {
        openOverlay("Screen access is unavailable. Search, music and camera are still available.")
    }

    init {
        setTheme(R.style.Theme_Glimpse_Overlay)
    }

    // Native controls are immediately usable while the system is still delivering its screenshot.
    // Only the eventual workspace needs Compose's activity lifecycle and permission handling.
    override fun onCreateContentView(): View {
        val density = context.resources.displayMetrics.density
        val margin = (16 * density).toInt()
        val actions =
            AssistantActionStrip(context).apply {
                selectedAction = selected
                onAction = ::selectAction
                onSearch = { openOverlay(null, submitSearch = true) }
            }
        strip = actions
        return FrameLayout(context).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            setBackgroundColor(Color.argb(20, 0, 0, 0))
            addView(
                EdgeGlowView(context),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                actions,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM,
                    )
                    .apply { setMargins(margin, 0, margin, margin) },
            )
            setOnApplyWindowInsetsListener { _, insets ->
                val safe =
                    insets.getInsets(
                        WindowInsets.Type.navigationBars() or WindowInsets.Type.ime(),
                    )
                actions.layoutParams =
                    (actions.layoutParams as FrameLayout.LayoutParams).apply {
                        setMargins(margin + safe.left, 0, margin + safe.right, margin + safe.bottom)
                    }
                insets
            }
            root = this
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (stage != Stage.WAITING) return
        window?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setWindowAnimations(0)
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        root?.requestApplyInsets()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 1600)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (stage != Stage.WAITING) return
        if (screenshot != null) {
            val image = CapturedImage(screenshot, "Your screen", assistantOnly = true)
            imageId = image.id
            ImageSession.image.value = image
        }
        openOverlay(
            if (screenshot == null)
                "This screen cannot be captured. Search, music and camera are still available."
            else null,
        )
    }

    private fun selectAction(action: AssistantAction) {
        if (stage != Stage.WAITING) return
        selected = action
        strip?.selectedAction = action
        when (action) {
            AssistantAction.MARK ->
                strip?.stateDescription = "Mark to search selected. Waiting for screen."
            AssistantAction.MUSIC -> openOverlay(null)
            AssistantAction.TRANSLATE ->
                handOff(
                    Intent(context, CapturePermissionActivity::class.java)
                        .setAction(CapturePermissionActivity.LIVE_TRANSLATE),
                )
            AssistantAction.CAMERA ->
                handOff(Intent(context, MainActivity::class.java).setAction(MainActivity.CAMERA))
        }
    }

    private fun openOverlay(error: String?, submitSearch: Boolean = false) {
        if (stage != Stage.WAITING) return
        handOff(
            Intent(context, AssistantActivity::class.java)
                .putExtra(AssistantEntry.CAPTURE_ERROR, error)
                .putExtra(AssistantEntry.IMAGE_ID, imageId)
                .putExtra(AssistantEntry.ACTION, selected?.name)
                .putExtra(AssistantEntry.QUERY, strip?.query.orEmpty())
                .putExtra(AssistantEntry.SUBMIT_SEARCH, submitSearch),
        )
    }

    private fun handOff(intent: Intent) {
        if (stage != Stage.WAITING) return
        stage = Stage.HANDED_OFF
        handler.removeCallbacks(timeout)
        try {
            startAssistantActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: RuntimeException) {
            imageId?.let(ImageSession::clear)
        }
        finish()
    }

    override fun onBackPressed() {
        if (stage != Stage.WAITING) return
        stage = Stage.CLOSED
        handler.removeCallbacks(timeout)
        imageId?.let(ImageSession::clear)
        root?.fadeAssistantOut { finish() } ?: finish()
    }

    override fun onHide() {
        val abandoned = stage == Stage.WAITING
        if (abandoned) {
            stage = Stage.CLOSED
            imageId?.let(ImageSession::clear)
        }
        handler.removeCallbacks(timeout)
        root?.animate()?.cancel()
        super.onHide()
        // Hidden sessions can be reused by Android. Abandoned capture requests must terminate so
        // the next gesture creates a fresh session instead of reusing a CLOSED bridge.
        if (abandoned) finish()
    }

    override fun onDestroy() {
        if (stage != Stage.HANDED_OFF) imageId?.let(ImageSession::clear)
        stage = Stage.CLOSED
        root?.animate()?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** Finish this transparent entry point before requesting a screenshot of the underlying app. */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    if (!GlimpseVoiceService.capture())
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .setAction(MainActivity.SETUP)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                },
                250,
            )
    }
}

class CameraTileService : TileService() {
    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).setAction(MainActivity.CAMERA),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }
}

class ScreenTileService : TileService() {
    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                2,
                Intent(this, CaptureActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }
}
