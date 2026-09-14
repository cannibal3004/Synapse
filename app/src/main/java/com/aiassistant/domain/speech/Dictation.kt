package com.aiassistant.domain.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Dictation"

/**
 * Give up after this many silent restarts, so a mic left open does not sit there forever.
 */
private const val MAX_IDLE_RESTARTS = 3

/**
 * Speech to text, shaped for dictating a message rather than issuing a command.
 *
 * The platform recogniser is built around single utterances: it stops on a pause and reports
 * either a result or a no-match. Left alone that means a sentence, then silence. So a session
 * here spans as many of those as the user needs -- each result is appended and listening starts
 * again -- until they stop it, or three consecutive pauses suggest they have finished talking
 * and walked away.
 */
@Singleton
class Dictation @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class State(
        val listening: Boolean = false,
        /** Everything recognised this session, including the sentence in progress. */
        val transcript: String = "",
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** Text from sentences already finalised; the live partial is appended for display. */
    private var settled = ""
    private var idleRestarts = 0

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Main thread only: SpeechRecognizer requires the looper it was created on. */
    fun start() {
        if (_state.value.listening) return
        if (!isAvailable) {
            _state.value = State(error = "No speech recognition on this device.")
            return
        }
        settled = ""
        idleRestarts = 0
        _state.value = State(listening = true)
        listen()
    }

    /** Ends the session and releases the microphone. */
    fun stop() {
        idleRestarts = MAX_IDLE_RESTARTS
        release()
        _state.value = _state.value.copy(listening = false)
    }

    private fun release() {
        // Destroyed rather than kept: holding a recogniser holds the mic, and on some devices
        // that shows a permanent recording indicator.
        recognizer?.runCatching { destroy() }
        recognizer = null
    }

    private fun listen() {
        release()
        val speech = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        speech.setRecognitionListener(Listener())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Advisory only -- most OEM recognisers round these to their own idea of a pause --
            // but where they are honoured they buy a moment to think mid-sentence.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        runCatching { speech.startListening(intent) }.onFailure {
            Log.e(TAG, "startListening failed", it)
            _state.value = State(listening = false, error = it.message)
        }
    }

    /** Another utterance, unless the user stopped or the room has gone quiet. */
    private fun continueOrFinish() {
        if (!_state.value.listening) return
        if (idleRestarts >= MAX_IDLE_RESTARTS) {
            stop()
            return
        }
        listen()
    }

    private fun publish(live: String) {
        val joined = listOf(settled, live).filter { it.isNotBlank() }.joinToString(" ")
        _state.value = _state.value.copy(transcript = joined)
    }

    private inner class Listener : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results.firstResult()
            if (text.isNullOrBlank()) {
                idleRestarts++
            } else {
                idleRestarts = 0
                settled = listOf(settled, text).filter { it.isNotBlank() }.joinToString(" ")
            }
            publish("")
            continueOrFinish()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstResult()?.takeIf { it.isNotBlank() }?.let { publish(it) }
        }

        override fun onError(error: Int) {
            when (error) {
                // A pause, not a failure. Keep the session open and listen again.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    idleRestarts++
                    publish("")
                    continueOrFinish()
                }
                // The recogniser is still finishing the previous session; try once more.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> continueOrFinish()
                else -> {
                    Log.w(TAG, "Recognition error $error")
                    release()
                    _state.value = _state.value.copy(
                        listening = false,
                        error = describe(error)
                    )
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is not granted."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a connection on this device."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This language is not available for speech recognition."
        else -> "Speech recognition failed ($error)."
    }
}
