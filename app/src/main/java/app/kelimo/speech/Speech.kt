package app.kelimo.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One selectable voice, for the voice picker. */
data class VoiceOption(val engine: String, val engineLabel: String, val name: String, val quality: Int)

/**
 * Text-to-speech restricted to installed offline voices (network voices and voices whose data is
 * not downloaded are skipped). The voice is chosen per utterance, so German replies and Turkish
 * explanations each get a native voice. Picks the highest-quality voice unless the user chose one.
 */
class Tts(private val context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var engine: String? = null
    private var autoFallbackDone = false
    private var locale: Locale = Locale.GERMAN
    /** language code -> preferred voice name (from settings). */
    var preferred: Map<String, String> = emptyMap()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _voiceMissing = MutableStateFlow(false)
    /** True when no installed voice can speak the current language. */
    val voiceMissing: StateFlow<Boolean> = _voiceMissing
    var rate = 0.95f

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { _speaking.value = true }
        override fun onDone(id: String?) { finish(id, true) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { finish(id, false) }
        override fun onError(id: String?, errorCode: Int) { finish(id, false) }
        override fun onStop(id: String?, interrupted: Boolean) { finish(id, false) }
    }

    init { create(null) }

    private fun finish(id: String?, ok: Boolean) {
        _speaking.value = false
        id?.let { waiting.remove(it)?.complete(ok) }
    }

    /** Switches the speech engine (null = system default). */
    fun useEngine(name: String?) {
        if (name == engine && tts != null) return
        autoFallbackDone = name != null
        create(name)
    }

    private fun create(engineName: String?) {
        tts?.shutdown()
        ready = false
        engine = engineName
        tts = TextToSpeech(context.applicationContext, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) checkLanguage(locale)
        }, engineName).apply { setOnUtteranceProgressListener(listener) }
    }

    fun setLanguage(l: Locale) {
        locale = l
        if (ready) checkLanguage(l)
    }

    private fun installedVoices(t: TextToSpeech?, l: Locale): List<Voice> = runCatching {
        t?.voices.orEmpty().filter {
            it.locale.language == l.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
    }.getOrDefault(emptyList())

    /** Best voice: the user's choice, else highest quality, preferring the main country of the language. */
    private fun pick(l: Locale): Voice? {
        val all = installedVoices(tts, l)
        preferred[l.language]?.let { name -> all.firstOrNull { it.name == name }?.let { return it } }
        return all.sortedWith(
            compareByDescending<Voice> { it.quality }.thenByDescending { it.locale.country == defaultCountry(l) }.thenBy { it.latency }
        ).firstOrNull()
    }

    private fun checkLanguage(l: Locale) {
        val t = tts ?: return
        val ok = pick(l) != null || t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE
        if (!ok && !autoFallbackDone) {
            autoFallbackDone = true
            // Samsung's engine often lacks German; Google's engine usually has it offline.
            val google = "com.google.android.tts"
            if (t.defaultEngine != google && t.engines.any { it.name == google }) { create(google); return }
        }
        _voiceMissing.value = !ok
    }

    /** Applies the right voice for [l] to the engine. False if the language can't be spoken. */
    private fun prepare(t: TextToSpeech, l: Locale, slow: Boolean): Boolean {
        val v = pick(l)
        val ok = if (v != null) t.setVoice(v) == TextToSpeech.SUCCESS else t.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE
        t.setSpeechRate(if (slow) rate * 0.6f else rate)
        // A hair below neutral pitch sounds calmer and less robotic on most engines.
        t.setPitch(0.97f)
        return ok
    }

    fun hasOfflineVoice(l: Locale): Boolean = ready && installedVoices(tts, l).isNotEmpty()

    /** Opens the engine's voice-data installer. */
    fun installVoiceIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
        engine?.let { setPackage(it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Fire and forget, for play buttons. */
    fun speak(text: String, slow: Boolean = false, lang: Locale = locale) {
        val t = tts ?: return
        if (!ready || text.isBlank()) return
        prepare(t, lang, slow)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /**
     * Speaks and suspends until the utterance ends. Returns false when nothing could be said,
     * so callers (the voice loop) never wait for an event that will not come.
     */
    suspend fun say(text: String, slow: Boolean = false, lang: Locale = locale): Boolean {
        if (text.isBlank()) return false
        withTimeoutOrNull(3000) { while (!ready) delay(50) } ?: return false
        val t = tts ?: return false
        if (!prepare(t, lang, slow)) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        waiting[id] = done
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) { waiting.remove(id); return false }
        // Generous upper bound: ~12 characters per second at slow rate, plus start-up.
        return withTimeoutOrNull(5000L + text.length * 120L) { done.await() } ?: run { waiting.remove(id); false }
    }

    fun stop() { if (ready) tts?.stop() }

    /** All installed offline voices for [l] across every speech engine on the phone. */
    suspend fun listVoices(l: Locale): List<VoiceOption> {
        val engines = tts?.engines.orEmpty()
        val out = mutableListOf<VoiceOption>()
        for (e in engines) {
            val ok = CompletableDeferred<Boolean>()
            val probe = TextToSpeech(context.applicationContext, { ok.complete(it == TextToSpeech.SUCCESS) }, e.name)
            if (withTimeoutOrNull(4000) { ok.await() } == true) {
                installedVoices(probe, l).forEach { out += VoiceOption(e.name, e.label, it.name, it.quality) }
            }
            probe.shutdown()
        }
        return out.sortedByDescending { it.quality }
    }

    val currentEngine: String? get() = engine ?: tts?.defaultEngine

    private fun defaultCountry(l: Locale) = when (l.language) { "de" -> "DE"; "en" -> "GB"; "es" -> "ES"; "fr" -> "FR"; "pt" -> "PT"; else -> l.country }
}

sealed interface SttState {
    data object Idle : SttState
    data object Listening : SttState
    data class Partial(val text: String) : SttState
    data class Result(val alternatives: List<String>) : SttState
    data class Error(val code: Int) : SttState {
        /** Silence or nothing recognisable: the user should simply try again. */
        val noSpeech: Boolean get() = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
    /** No usable recognizer for the language. */
    data object Unavailable : SttState
}

/**
 * Speech recognition. Uses the on-device recognizer first. If it is missing or lacks the language,
 * it asks the system to download the offline pack and, unless strict mode is on, falls back to the
 * system recognizer with "prefer offline".
 */
class Stt(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    /** Increments on every start; callbacks from an older recognizer are ignored. */
    private var session = 0
    private val noOnDevice = mutableSetOf<String>()
    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state
    private val _level = MutableStateFlow(0f)
    /** Microphone level 0..1 for the waveform. */
    val level: StateFlow<Float> = _level
    private val _usingFallback = MutableStateFlow(false)
    /** True when the last session used the system recognizer instead of the on-device one. */
    val usingFallback: StateFlow<Boolean> = _usingFallback

    val onDeviceAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val available: Boolean get() = onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(context)

    fun start(locale: Locale, strictOffline: Boolean) {
        stop()
        val onDevice = onDeviceAvailable && locale.language !in noOnDevice
        if (!onDevice && (strictOffline || !SpeechRecognizer.isRecognitionAvailable(context))) { _state.value = SttState.Unavailable; return }
        begin(locale, onDevice, strictOffline)
    }

    private fun begin(locale: Locale, onDevice: Boolean, strictOffline: Boolean) {
        val my = ++session
        _usingFallback.value = !onDevice
        val r = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrElse { Log.w("Stt", "create failed", it); _state.value = SttState.Unavailable; return }
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (my == session) _state.value = SttState.Listening }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (my == session) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (my == session) _level.value = 0f }
            override fun onError(error: Int) {
                if (my != session) return
                Log.i("Stt", "error $error onDevice=$onDevice")
                _level.value = 0f
                val langMissing = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                if (onDevice && (langMissing || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)) {
                    // Ask the system to fetch the offline pack for next time (the download is done by Android, not by this app).
                    if (Build.VERSION.SDK_INT >= 33) runCatching { r.triggerModelDownload(intent) }
                    noOnDevice += locale.language
                    if (!strictOffline && SpeechRecognizer.isRecognitionAvailable(context)) {
                        release()
                        begin(locale, onDevice = false, strictOffline = false)
                        return
                    }
                }
                release()
                _state.value = if (langMissing) SttState.Unavailable else SttState.Error(error)
            }
            override fun onResults(results: Bundle?) {
                if (my != session) return
                val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                release()
                _state.value = if (alts.isEmpty()) SttState.Error(SpeechRecognizer.ERROR_NO_MATCH) else SttState.Result(alts)
            }
            override fun onPartialResults(partial: Bundle?) {
                if (my != session) return
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { _state.value = SttState.Partial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        _state.value = SttState.Listening
        runCatching { r.startListening(intent) }.onFailure { release(); _state.value = SttState.Error(SpeechRecognizer.ERROR_CLIENT) }
    }

    /** Stop recording and deliver what was heard so far. */
    fun finish() { recognizer?.stopListening() }

    private fun release() {
        recognizer?.runCatching { destroy() }
        recognizer = null
        _level.value = 0f
    }

    /** Abort without a result. */
    fun stop() {
        session++
        recognizer?.runCatching { cancel() }
        release()
        if (_state.value is SttState.Listening || _state.value is SttState.Partial) _state.value = SttState.Idle
    }

    fun reset() { _state.value = SttState.Idle }
}
