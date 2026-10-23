package app.glimpse.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.glimpse.music.MusicCaptureService
import app.glimpse.music.MusicSession

/** All capture grants originate in a visible activity; no persisted or reused projection tokens. */
class CapturePermissionActivity : ComponentActivity() {
    private var explanation: AlertDialog? = null
    private var explanationKind: String? = null
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMusic()
            else {
                MusicSession.error("Microphone/audio capture permission was denied")
                finish()
            }
        }
    private val overlay =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) explainScreenCapture() else finish()
        }
    private val projection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val service =
                    if (intent.action == LIVE_TRANSLATE) ScreenTranslationService::class.java
                    else MusicCaptureService::class.java
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, service)
                        .putExtra("result_code", result.resultCode)
                        .putExtra("result_data", data)
                        .putExtra("input", intent.getStringExtra("input") ?: "both"),
                )
            } else if (intent.action != LIVE_TRANSLATE)
                MusicSession.error(
                    "Phone audio capture was cancelled. You can choose Microphone instead.",
                )
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            when (savedInstanceState.getString("explanation")) {
                "screen" -> explainScreenCapture()
                "overlay" -> explainOverlayAccess()
            }
            return
        }
        if (intent.action == LIVE_TRANSLATE) {
            stopService(Intent(this, ScreenTranslationService::class.java))
            if (Settings.canDrawOverlays(this)) explainScreenCapture() else explainOverlayAccess()
        } else if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
            startMusic()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun explainOverlayAccess() {
        explanationKind = "overlay"
        explanation =
            AlertDialog.Builder(this)
                .setTitle("Live screen translate")
                .setMessage(
                    "Allow display over other apps so translations and stop controls can stay above the app you are using.",
                )
                .setPositiveButton("Continue") { _, _ ->
                    explanationKind = null
                    overlay.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
    }

    private fun explainScreenCapture() {
        explanationKind = "screen"
        explanation =
            AlertDialog.Builder(this)
                .setTitle("Choose a single app")
                .setMessage(
                    "In Android's sharing dialog, choose one app to translate. You can scroll and use that app normally. Entire-screen sharing also captures the translated labels and is not supported.",
                )
                .setPositiveButton("Choose app") { _, _ ->
                    explanationKind = null
                    requestProjection()
                }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
    }

    private fun startMusic() {
        if (intent.getStringExtra("input") == "microphone") {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MusicCaptureService::class.java).putExtra("input", "microphone"),
            )
            finish()
        } else requestProjection()
    }

    private fun requestProjection() =
        projection.launch(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
        )

    override fun onDestroy() {
        explanation?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("explanation", explanationKind)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val LIVE_TRANSLATE = "app.glimpse.LIVE_TRANSLATE"
        const val MUSIC = "app.glimpse.MUSIC"
    }
}
