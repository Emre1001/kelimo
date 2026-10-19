package app.kelimo.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/**
 * Text-to-speech restricted to offline voices: voices that need a network connection are skipped,
 * so nothing typed in the app is sent to a speech server.
 */
class Tts(context: Context) {
    private var ready = false
    private var pendingLocale: Locale? = null
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    var rate = 0.95f

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        pendingLocale?.let { setLanguage(it) }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false }
        })
    }

    fun setLanguage(locale: Locale) {
        if (!ready) { pendingLocale = locale; return }
        val offline: Voice? = runCatching {
            tts.voices?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired && Voice.QUALITY_NORMAL <= it.quality }
                ?.sortedWith(compareByDescending<Voice> { it.locale.country == defaultCountry(locale) }.thenByDescending { it.quality })
                ?.firstOrNull()
        }.getOrNull()
        if (offline != null) tts.voice = offline else tts.language = locale
    }

    /** True when an offline voice for the language is installed. */
    fun hasOfflineVoice(locale: Locale): Boolean = ready && runCatching {
        tts.voices?.any { it.locale.language == locale.language && !it.isNetworkConnectionRequired } == true
    }.getOrDefault(false)

    fun speak(text: String, slow: Boolean = false) {
        if (!ready || text.isBlank()) return
        tts.setSpeechRate(if (slow) rate * 0.6f else rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString())
    }

    fun stop() { if (ready) tts.stop() }
    fun shutdown() = tts.shutdown()

    private fun defaultCountry(l: Locale) = when (l.language) { "de" -> "DE"; "en" -> "GB"; "es" -> "ES"; "fr" -> "FR"; "pt" -> "PT"; else -> l.country }
}

sealed interface SttState {
    data object Idle : SttState
    data object Listening : SttState
    data class Partial(val text: String) : SttState
    data class Result(val alternatives: List<String>) : SttState
    data class Error(val code: Int) : SttState
    /** No on-device recognizer or language pack. The app then refuses instead of using a cloud recognizer. */
    data object Unavailable : SttState
}

/** On-device speech recognition only (SpeechRecognizer.createOnDeviceSpeechRecognizer, API 31+). */
class Stt(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state
    private val _level = MutableStateFlow(0f)
    /** Microphone level 0..1 for the waveform. */
    val level: StateFlow<Float> = _level

    val available: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(locale: Locale) {
        stop()
        if (!available) { _state.value = SttState.Unavailable; return }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = SttState.Listening }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onError(error: Int) {
                _level.value = 0f
                _state.value = if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) SttState.Unavailable else SttState.Error(error)
            }
            override fun onResults(results: Bundle?) {
                val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                _state.value = if (alts.isEmpty()) SttState.Error(SpeechRecognizer.ERROR_NO_MATCH) else SttState.Result(alts)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _state.value = SttState.Partial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        _state.value = SttState.Listening
        r.startListening(intent)
    }

    fun finish() { recognizer?.stopListening() }

    fun stop() {
        recognizer?.run { cancel(); destroy() }
        recognizer = null
        _level.value = 0f
        if (_state.value is SttState.Listening || _state.value is SttState.Partial) _state.value = SttState.Idle
    }

    fun reset() { _state.value = SttState.Idle }
}
