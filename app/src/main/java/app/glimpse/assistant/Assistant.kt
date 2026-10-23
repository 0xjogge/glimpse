package app.glimpse.assistant

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import app.glimpse.MainActivity
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
        openOverlay("Screen access is unavailable. Search, music and camera are still available.")
    }

    // The system session is only the capture bridge. Its unbranded edge light hands off to
    // a translucent activity with a real Compose lifecycle, keyboard and permission handling.
    override fun onCreateContentView(): View =
        object : View(context) {
            private val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 8 * resources.displayMetrics.density
                    alpha = 140
                }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                paint.shader =
                    LinearGradient(
                        0f,
                        0f,
                        w.toFloat(),
                        h.toFloat(),
                        Color.rgb(102, 226, 206),
                        Color.rgb(185, 149, 255),
                        Shader.TileMode.CLAMP,
                    )
            }

            override fun onDraw(canvas: Canvas) {
                canvas.drawRoundRect(5f, 5f, width - 5f, height - 5f, 48f, 48f, paint)
            }
        }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launched = false
        ImageSession.clear()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 1600)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (launched) return
        if (screenshot != null) ImageSession.offer(screenshot, "Your screen", assistantOnly = true)
        openOverlay(
            if (screenshot == null)
                "This screen cannot be captured. Search, music and camera are still available."
            else null,
        )
    }

    private fun openOverlay(error: String?) {
        if (launched) return
        launched = true
        handler.removeCallbacks(timeout)
        startAssistantActivity(
            Intent(context, AssistantActivity::class.java).putExtra("capture_error", error),
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
