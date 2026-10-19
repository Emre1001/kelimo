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
    // Unknown enum values or nulls from another app version fall back to defaults instead of failing.
    coerceInputValues = true
}

/**
 * Whole app state as one JSON file in private storage (no cloud backup, see manifest).
 * Writes are debounced and atomic, so a crash mid-write never corrupts progress.
 */
class Store(context: Context, private val scope: CoroutineScope) {
    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, "state.json"))
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
        // Never overwrite unreadable progress: park it next to the new file so it can be recovered.
        Log.e("Store", "state unreadable, keeping a copy and starting fresh", e)
        runCatching { file.baseFile.copyTo(File(dir, "state.unreadable-${System.currentTimeMillis()}.json")) }
        AppState()
    }

    /**
     * Called once per launch with the installed version. After an update the previous state file is
     * copied to state.backup-v<old>.json first, so an update can never cost progress.
     */
    fun backupOnUpgrade(versionCode: Long) {
        val marker = File(dir, "version")
        // 1.0.0 wrote no marker: an existing state without one comes from version 1.
        val old = runCatching { marker.readText().trim().toLong() }.getOrNull() ?: if (file.baseFile.exists()) 1L else null
        if (old != null && old != versionCode && file.baseFile.exists()) {
            runCatching { file.baseFile.copyTo(File(dir, "state.backup-v$old.json"), overwrite = true) }
            // Keep the three newest backups.
            dir.listFiles { f -> f.name.startsWith("state.backup-v") }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        }
        runCatching { marker.writeText(versionCode.toString()) }
    }

    /** Newest automatic backup, if any. */
    fun latestBackup(): File? = dir.listFiles { f -> f.name.startsWith("state.backup-v") }?.maxByOrNull { it.lastModified() }

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
