package app.glimpse

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView

/**
 * A genuinely different app/task for whole-display capture, without restricted Settings windows.
 */
class TranslationFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "さようなら"
                textSize = 36f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(32, 40, 50))
                setPadding(60, 180, 60, 60)
            },
        )
    }
}
