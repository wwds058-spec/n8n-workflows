package com.personalai.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speech-to-text using the phone's speech recognizer. Must be used from the main thread.
 */
class SpeechInput(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onFinished()
    }

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, listener: Listener) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                if (text.isNullOrBlank()) listener.onError("I didn't catch that.") else listener.onFinal(text)
                listener.onFinished()
            }

            override fun onError(error: Int) {
                listener.onError(describe(error))
                listener.onFinished()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (languageTag.isNotBlank()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        r.startListening(intent)
    }

    /** Stops listening early; the recognizer still delivers what it heard. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "That speech language isn't available on this phone. Change it in Settings."
        else -> "Speech recognition failed (code $error)."
    }
}

/** Text-to-speech for spoken replies. */
class Speaker(context: Context) {
    private var ready = false
    private var pending: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) pending?.let { speak(it) }
        pending = null
    }

    fun speak(text: String, languageTag: String = "") {
        if (!ready) {
            pending = text
            return
        }
        if (languageTag.isNotBlank()) tts.language = Locale.forLanguageTag(languageTag)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
