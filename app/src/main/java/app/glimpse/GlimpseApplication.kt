package app.glimpse

import android.app.Application
import app.glimpse.core.EngineRegistry
import app.glimpse.data.AppPreferences
import app.glimpse.data.Preferences
import app.glimpse.engines.mlkit.MlKitOcr
import app.glimpse.engines.mlkit.MlKitTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class GlimpseApplication : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Warm the snapshot asynchronously before native assistant controls are requested.
        graph.currentPreferences
    }
}

class AppGraph(application: Application) {
    val preferences = Preferences(application)
    val currentPreferences =
        preferences.values.stateIn(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            SharingStarted.Eagerly,
            AppPreferences(),
        )
    val engines by lazy { EngineRegistry(listOf(MlKitOcr()), listOf(MlKitTranslation())) }
}
