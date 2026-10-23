package app.glimpse

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.assistant.CaptureActivity
import app.glimpse.ui.GlimpseApp
import app.glimpse.ui.GlimpseTheme

class MainActivity : ComponentActivity() {
    private val model by viewModels<GlimpseViewModel>()
    private val roleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = model.preferences.collectAsStateWithLifecycle().value
            GlimpseTheme(preferences) {
                GlimpseApp(model, ::requestAssistant, ::captureScreen)
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            SETTINGS -> model.page(Page.SETTINGS)
            MODELS -> model.page(Page.MODELS)
            CAMERA -> {
                model.home()
                model.page(Page.CAMERA)
            }
            SCREEN -> captureScreen()
            SETUP -> {
                model.page(Page.SETTINGS)
                model.message("Choose Glimpse as your digital assistant to scan other apps")
            }
            ASSIST ->
                intent.getStringExtra("capture_error")?.let {
                    model.home()
                    model.message(it)
                }
            Intent.ACTION_SEND ->
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(model::load)
            Intent.ACTION_PROCESS_TEXT ->
                intent
                    .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.toString()
                    ?.let(model::receiveText)
        }
    }

    private fun requestAssistant() {
        val manager = getSystemService(RoleManager::class.java)
        if (
            manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        ) {
            roleRequest.launch(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
        } else {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }
    }

    private fun captureScreen() {
        startActivity(Intent(this, CaptureActivity::class.java))
        moveTaskToBack(true)
    }

    companion object {
        const val CAMERA = "app.glimpse.CAMERA"
        const val SCREEN = "app.glimpse.SCREEN"
        const val ASSIST = "app.glimpse.ASSIST"
        const val SETUP = "app.glimpse.SETUP"
        const val SETTINGS = "app.glimpse.SETTINGS"
        const val MODELS = "app.glimpse.MODELS"
    }
}
