package app.glimpse.assistant

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.glimpse.MainActivity
import app.glimpse.R
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
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false
    private val timeout = Runnable {
        openWorkspace(
            "Screen capture is unavailable. You can still open the camera or choose a photo.",
        )
    }

    override fun onCreateContentView(): View {
        val dark =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val foreground = if (dark) Color.rgb(232, 240, 231) else Color.rgb(25, 36, 28)
        val root = FrameLayout(context)
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(28))
                background =
                    GradientDrawable().apply {
                        setColor(if (dark) Color.rgb(29, 37, 30) else Color.rgb(242, 247, 238))
                        cornerRadii =
                            floatArrayOf(
                                dp(28).toFloat(),
                                dp(28).toFloat(),
                                dp(28).toFloat(),
                                dp(28).toFloat(),
                                0f,
                                0f,
                                0f,
                                0f,
                            )
                    }
            }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            TextView(context).apply {
                text = "Glimpse"
                textSize = 24f
                setTextColor(foreground)
            },
            LinearLayout.LayoutParams(0, dp(56), 1f),
        )
        header.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_camera)
                imageTintList = android.content.res.ColorStateList.valueOf(foreground)
                setBackgroundResource(android.R.drawable.btn_default)
                contentDescription = context.getString(R.string.assistant_camera)
                setOnClickListener { openCamera() }
            },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        card.addView(header)
        val status = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        status.addView(ProgressBar(context), LinearLayout.LayoutParams(dp(22), dp(22)))
        status.addView(
            TextView(context).apply {
                text = "  Getting your screen…"
                textSize = 16f
                setTextColor(foreground)
            },
        )
        card.addView(status)
        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        handler.removeCallbacks(timeout)
        if (!launched) handler.postDelayed(timeout, 1600)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (launched) return
        if (screenshot != null) {
            ImageSession.offer(screenshot, "Your screen")
            openWorkspace(null)
        } else
            openWorkspace(
                "This screen cannot be captured. Try sharing an image, or open the camera.",
            )
    }

    private fun openWorkspace(error: String?) {
        if (launched) return
        launched = true
        handler.removeCallbacks(timeout)
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ASSIST)
                .putExtra("capture_error", error)
        startAssistantActivity(intent)
        finish()
    }

    private fun openCamera() {
        if (launched) return
        launched = true
        handler.removeCallbacks(timeout)
        startAssistantActivity(
            Intent(context, MainActivity::class.java).setAction(MainActivity.CAMERA),
        )
        finish()
    }

    override fun onHide() {
        handler.removeCallbacks(timeout)
        super.onHide()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

/** Finish this transparent entry point before requesting a screenshot of the underlying app. */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        Handler(Looper.getMainLooper())
            .postDelayed(
                {
                    if (!GlimpseVoiceService.capture()) {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .setAction(MainActivity.SETUP)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
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
