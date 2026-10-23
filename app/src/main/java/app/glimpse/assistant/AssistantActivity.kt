package app.glimpse.assistant

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
    private var imageId: String? = null
    private var exiting = false
    private val model by
        viewModels<GlimpseViewModel> {
            viewModelFactory {
                initializer {
                    GlimpseViewModel(
                        application,
                        assistantSession = true,
                        assistantImageId = imageId,
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setWindowAnimations(0)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        imageId =
            when {
                savedInstanceState?.containsKey(AssistantEntry.IMAGE_ID) == true ->
                    savedInstanceState.getString(AssistantEntry.IMAGE_ID)
                intent.hasExtra(AssistantEntry.IMAGE_ID) ->
                    intent.getStringExtra(AssistantEntry.IMAGE_ID)
                // Direct launches adopt a current assistant capture once, never a later one.
                else -> ImageSession.image.value?.takeIf { it.assistantOnly }?.id
            }
        if (savedInstanceState?.getBoolean("assistant_exiting") == true) {
            exiting = true
            imageId?.let(ImageSession::clear)
            finishImmediately()
            return
        }
        val initialAction =
            intent.getStringExtra(AssistantEntry.ACTION)?.let { name ->
                AssistantAction.entries.firstOrNull { it.name == name }
            }
        intent.getStringExtra(AssistantEntry.CAPTURE_ERROR)?.let(model::message)
        setContent {
            val preferences = model.preferences.collectAsStateWithLifecycle().value
            GlimpseTheme(preferences) {
                AssistantOverlay(
                    model,
                    dismiss = ::finish,
                    openApp = { action ->
                        leaveFor(Intent(this, MainActivity::class.java).setAction(action))
                    },
                    translateScreen = {
                        leaveFor(
                            Intent(this, CapturePermissionActivity::class.java)
                                .setAction(CapturePermissionActivity.LIVE_TRANSLATE),
                        )
                    },
                    initialAction = initialAction,
                    initialQuery = intent.getStringExtra(AssistantEntry.QUERY).orEmpty(),
                    submitInitialSearch =
                        savedInstanceState == null &&
                            intent.getBooleanExtra(AssistantEntry.SUBMIT_SEARCH, false),
                )
            }
        }
    }

    private fun leaveFor(intent: Intent) {
        if (exiting || isFinishing) return
        exiting = true
        imageId?.let(ImageSession::clear)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finishImmediately()
    }

    override fun finish() {
        if (exiting || isFinishing) return
        exiting = true
        imageId?.let(ImageSession::clear)
        window.decorView.fadeAssistantOut(::finishImmediately)
    }

    private fun finishImmediately() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(AssistantEntry.IMAGE_ID, imageId)
        outState.putBoolean("assistant_exiting", exiting)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        window.decorView.animate().cancel()
        if (isFinishing) imageId?.let(ImageSession::clear)
        super.onDestroy()
    }
}
