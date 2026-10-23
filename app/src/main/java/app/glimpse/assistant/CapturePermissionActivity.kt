package app.glimpse.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.glimpse.GlimpseApplication
import app.glimpse.core.Language
import app.glimpse.music.MusicCaptureService
import app.glimpse.music.MusicSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            if (Settings.canDrawOverlays(this)) requestProjection() else finish()
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
                        .setAction(
                            if (intent.action == LIVE_TRANSLATE) ScreenTranslationService.START
                            else null,
                        )
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
                "screen" -> showTranslationSetup()
                "overlay" -> explainOverlayAccess()
            }
            return
        }
        if (intent.action == LIVE_TRANSLATE) {
            showTranslationSetup()
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

    private fun showTranslationSetup() {
        explanationKind = "screen"
        lifecycleScope.launch {
            val graph = (application as GlimpseApplication).graph
            val prefs = graph.preferences.values.first()
            if (isFinishing) return@launch
            val engine = graph.engines.translator(prefs.translationEngine)
            val toLanguages =
                engine.languages.sortedWith(
                    compareBy<Language> {
                            when (it.tag) {
                                "en" -> 0
                                "ja" -> 1
                                else -> 2
                            }
                        }
                        .thenBy { it.name },
                )
            val fromLanguages = listOf(Language("auto", "Detect language")) + toLanguages
            val content =
                LinearLayout(this@CapturePermissionActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (24 * resources.displayMetrics.density).toInt()
                    setPadding(padding, 0, padding, padding / 2)
                }
            fun picker(label: String, languages: List<Language>, selected: String): Spinner {
                content.addView(TextView(this@CapturePermissionActivity).apply { text = label })
                return Spinner(this@CapturePermissionActivity).apply {
                    adapter =
                        ArrayAdapter(
                            this@CapturePermissionActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            languages.map { it.name },
                        )
                    setSelection(languages.indexOfFirst { it.tag == selected }.coerceAtLeast(0))
                    content.addView(this)
                }
            }
            val from = picker("From", fromLanguages, prefs.source)
            val to = picker("To", toLanguages, prefs.target)
            val active = ScreenTranslationSession.state.value.active
            val builder =
                AlertDialog.Builder(this@CapturePermissionActivity)
                    .setTitle("Translate your screen")
                    .setMessage(
                        if (active) "Change languages for the current session."
                        else
                            "Translate text over any app after you stop scrolling. Android will ask to share your entire screen. Translations stay on this device.",
                    )
                    .setView(content)
                    .setPositiveButton(if (active) "Apply" else "Start translating") { _, _ ->
                        lifecycleScope.launch {
                            graph.preferences.set(
                                "source",
                                fromLanguages[from.selectedItemPosition].tag,
                            )
                            graph.preferences.set(
                                "target",
                                toLanguages[to.selectedItemPosition].tag,
                            )
                            explanationKind = null
                            if (ScreenTranslationSession.state.value.active) {
                                startService(
                                    Intent(
                                            this@CapturePermissionActivity,
                                            ScreenTranslationService::class.java,
                                        )
                                        .setAction(ScreenTranslationService.REFRESH),
                                )
                                finish()
                            } else if (Settings.canDrawOverlays(this@CapturePermissionActivity))
                                requestProjection()
                            else explainOverlayAccess()
                        }
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
            if (active)
                builder.setNeutralButton("Stop") { _, _ ->
                    stopService(
                        Intent(
                            this@CapturePermissionActivity,
                            ScreenTranslationService::class.java,
                        ),
                    )
                    finish()
                }
            explanation = builder.show()
        }
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

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection.launch(
            if (intent.action == LIVE_TRANSLATE)
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay(),
                )
            else manager.createScreenCaptureIntent(),
        )
    }

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
