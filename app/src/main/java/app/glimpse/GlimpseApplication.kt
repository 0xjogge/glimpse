package app.glimpse

import android.app.Application
import app.glimpse.core.EngineRegistry
import app.glimpse.data.Preferences
import app.glimpse.engines.mlkit.MlKitOcr
import app.glimpse.engines.mlkit.MlKitTranslation

class GlimpseApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

class AppGraph(application: Application) {
    val preferences = Preferences(application)
    val engines by lazy { EngineRegistry(listOf(MlKitOcr()), listOf(MlKitTranslation())) }
}
