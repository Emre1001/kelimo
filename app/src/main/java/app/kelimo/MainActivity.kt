package app.kelimo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.learn.Progress
import app.kelimo.ui.Root
import app.kelimo.ui.theme.KelimoTheme
import app.kelimo.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = application as KelimoApp
        Progress.applyFreeze(app.store)
        if (app.store.value.onboarded) app.scope.launch { Updater.check(app) }
        setContent {
            val state by app.store.state.collectAsStateWithLifecycle()
            KelimoTheme(state.settings.theme, state.settings.reduceMotion) {
                Root(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as KelimoApp).let { if (it.store.value.onboarded) it.warmUpModel() }
    }

    override fun onStop() {
        super.onStop()
        (application as KelimoApp).store.flush()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Background + memory pressure: free the model's RAM; it reloads on return.
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_MODERATE) (application as KelimoApp).let { a -> a.scope.launch { a.llm.unload() } }
    }
}
