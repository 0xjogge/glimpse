package app.glimpse.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.glimpse.GlimpseViewModel
import app.glimpse.MainActivity
import app.glimpse.data.ImageSession
import app.glimpse.ui.GlimpseTheme

class AssistantActivity : ComponentActivity() {
    private val model by
        viewModels<GlimpseViewModel> {
            viewModelFactory {
                initializer { GlimpseViewModel(application, assistantSession = true) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra("capture_error")?.let(model::message)
        setContent {
            val preferences = model.preferences.collectAsStateWithLifecycle().value
            GlimpseTheme(preferences) {
                AssistantOverlay(model, ::finish) { action ->
                    startActivity(Intent(this, MainActivity::class.java).setAction(action))
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && ImageSession.image.value?.assistantOnly == true) ImageSession.clear()
        super.onDestroy()
    }
}
