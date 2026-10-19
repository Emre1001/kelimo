package app.kelimo.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/**
 * Whole app state as one JSON file in private storage (no cloud backup, see manifest).
 * Writes are debounced and atomic, so a crash mid-write never corrupts progress.
 */
class Store(context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "state.json"))
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state
    private var pending: Job? = null

    val value: AppState get() = _state.value

    fun update(block: (AppState) -> AppState) {
        _state.update(block)
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(400)
            save(_state.value)
        }
    }

    fun updateCourse(block: (CourseProgress) -> CourseProgress) = update { s ->
        s.copy(courses = s.courses + (s.profile.target to block(s.course)))
    }

    fun flush() = save(_state.value)

    fun exportJson(): String = AppJson.encodeToString(AppState.serializer(), _state.value.copy(models = emptyList(), activeModelId = null))

    /** Imports a backup but keeps the locally installed models. Throws on invalid input. */
    fun importJson(text: String) {
        val incoming = AppJson.decodeFromString(AppState.serializer(), text)
        update { cur -> incoming.copy(models = cur.models, activeModelId = cur.activeModelId) }
    }

    fun reset() = update { cur -> AppState(models = cur.models, activeModelId = cur.activeModelId) }

    private fun load(): AppState = try {
        if (!file.baseFile.exists()) AppState()
        else AppJson.decodeFromString(AppState.serializer(), file.readFully().decodeToString())
    } catch (e: Exception) {
        Log.e("Store", "state unreadable, starting fresh", e)
        AppState()
    }

    @Synchronized
    private fun save(s: AppState) {
        val out = file.startWrite()
        try {
            out.write(AppJson.encodeToString(AppState.serializer(), s).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e("Store", "save failed", e)
        }
    }
}
