package app.kelimo.ai

import android.content.Context
import android.util.Log
import app.kelimo.data.AppJson
import app.kelimo.data.Backend as PrefBackend
import app.kelimo.data.InstalledModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

data class Turn(val user: Boolean, val text: String)

data class LlmRequest(
    val system: String,
    val user: String,
    val history: List<Turn> = emptyList(),
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    /** JSON schema for constrained decoding. */
    val schema: String? = null,
    /** Hard cap on generated characters, protects against run-away output. */
    val maxChars: Int = 2400,
)

sealed interface EngineState {
    data object NoModel : EngineState
    data object Idle : EngineState
    data class Loading(val name: String) : EngineState
    data class Ready(val modelId: String, val backend: PrefBackend) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Single on-device LLM. One request at a time (mutex): phones have one model in RAM,
 * and queuing beats two half-speed generations.
 */
class LlmEngine(private val context: Context, private val files: ModelFiles) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var jsonModeWorks = true
    private var loadedModel: InstalledModel? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.NoModel)
    val state: StateFlow<EngineState> = _state

    val isReady: Boolean get() = _state.value is EngineState.Ready

    init {
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
    }

    fun markNoModel() { if (engine == null) _state.value = EngineState.NoModel }
    fun markIdle() { if (engine == null) _state.value = EngineState.Idle }

    suspend fun load(model: InstalledModel, backend: PrefBackend) = mutex.withLock {
        val key = "${model.fileName}|$backend"
        if (key == loadedKey && engine != null) return@withLock
        withContext(Dispatchers.IO) {
            closeEngine()
            val path = files.file(model).path
            if (!File(path).exists()) { _state.value = EngineState.Failed("missing"); return@withContext }
            _state.value = EngineState.Loading(model.name)
            val tryOrder = if (backend == PrefBackend.GPU) listOf(PrefBackend.GPU, PrefBackend.CPU) else listOf(PrefBackend.CPU)
            var lastError: Throwable? = null
            for (b in tryOrder) {
                try {
                    engine = createEngine(path, b)
                    loadedKey = "${model.fileName}|$backend"
                    loadedModel = model
                    jsonModeWorks = true
                    _state.value = EngineState.Ready(model.id, b)
                    return@withContext
                } catch (t: Throwable) {
                    Log.w("Llm", "init on $b failed", t)
                    lastError = t
                }
            }
            _state.value = EngineState.Failed(lastError?.message ?: "init")
        }
    }

    private fun createEngine(path: String, b: PrefBackend): Engine = Engine(
        EngineConfig(
            modelPath = path,
            backend = if (b == PrefBackend.GPU) Backend.GPU() else Backend.CPU(),
            maxNumTokens = 4096,
            cacheDir = context.cacheDir.path,
        )
    ).also { it.initialize() }

    suspend fun unload() = mutex.withLock { withContext(Dispatchers.IO) { closeEngine(); _state.value = EngineState.Idle } }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
        loadedModel = null
    }

    /**
     * Streams the growing answer text to [onText]; returns the final text.
     * Some GPUs initialise fine but fail on the first generation (e.g. missing OpenCL): then the
     * engine is rebuilt on the CPU once and the request repeated, instead of failing every time.
     */
    suspend fun generate(req: LlmRequest, onText: (String) -> Unit = {}): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                run(req, onText, useSchema = req.schema != null && jsonModeWorks)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val st = _state.value
                val m = loadedModel
                if (st !is EngineState.Ready || st.backend != PrefBackend.GPU || m == null) throw t
                Log.w("Llm", "GPU generation failed, switching to CPU", t)
                withContext(Dispatchers.IO) {
                    closeEngine()
                    engine = createEngine(files.file(m).path, PrefBackend.CPU)
                    loadedKey = "${m.fileName}|GPU" // keep: the user's setting is still GPU, the device just can't do it
                    loadedModel = m
                    _state.value = EngineState.Ready(m.id, PrefBackend.CPU)
                }
                run(req, onText, useSchema = req.schema != null && jsonModeWorks)
            }
        }
    }

    private suspend fun run(req: LlmRequest, onText: (String) -> Unit, useSchema: Boolean): String {
        val e = engine ?: throw IllegalStateException("no-model")
        val conv = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(req.system),
                initialMessages = req.history.takeLast(16).map { if (it.user) Message.user(it.text) else Message.model(it.text) },
                samplerConfig = SamplerConfig(topK = req.topK, topP = req.topP, temperature = req.temperature, seed = 7),
                enableResponseFormat = useSchema,
            )
        )
        val sb = StringBuilder()
        try {
            val flow = if (useSchema) conv.sendMessageAsync(req.user, responseFormat = ResponseFormat.json(req.schema!!))
            else conv.sendMessageAsync(req.user)
            flow.collect { m ->
                sb.append(textOf(m))
                onText(sb.toString())
                if (sb.length > req.maxChars) { conv.cancelProcess(); throw StopGeneration() }
            }
        } catch (_: StopGeneration) {
        } catch (c: CancellationException) {
            runCatching { conv.cancelProcess() }
            throw c
        } catch (t: Throwable) {
            if (useSchema && sb.isEmpty()) {
                // Maybe the model or runtime lacks constrained decoding: try prompt-only JSON. Only if that
                // works is JSON mode switched off; otherwise the failure had another cause (e.g. backend).
                runCatching { conv.close() }
                val plain = runCatching { run(req, onText, useSchema = false) }
                plain.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (plain.isSuccess) {
                    Log.w("Llm", "json mode unsupported, using prompt-only JSON", t)
                    jsonModeWorks = false
                    return plain.getOrThrow()
                }
            }
            throw t
        } finally {
            runCatching { conv.close() }
        }
        return sb.toString().trim()
    }

    /**
     * JSON request with validation: constrained decoding if available, lenient extraction otherwise,
     * one retry at a lower temperature, then null. Callers must handle null with a safe fallback.
     */
    suspend fun json(req: LlmRequest, onText: (String) -> Unit = {}): JsonObject? {
        repeat(2) { attempt ->
            val r = if (attempt == 0) req else req.copy(temperature = minOf(req.temperature, 0.2), user = req.user + "\n\nAnswer with ONE valid JSON object only.")
            val raw = try { generate(r, onText) } catch (c: CancellationException) { throw c } catch (t: Throwable) { Log.w("Llm", "gen failed", t); null }
            Log.d("Llm", "attempt $attempt json=$jsonModeWorks raw=${raw?.take(300)}")
            raw?.let(::extractJson)?.let { return it }
        }
        return null
    }

    private class StopGeneration : RuntimeException()

    companion object {
        fun textOf(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

        /** Finds the first balanced {...} block and parses it. */
        fun extractJson(raw: String): JsonObject? {
            val start = raw.indexOf('{')
            if (start < 0) return null
            var depth = 0; var inStr = false; var esc = false
            for (i in start until raw.length) {
                val c = raw[i]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return runCatching { AppJson.parseToJsonElement(raw.substring(start, i + 1)).jsonObject }.getOrNull()
                }
            }
            return null
        }
    }
}
