package com.aiassistant.domain.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Dictation"

/**
 * Speech to text: one utterance per press of the microphone.
 *
 * This used to keep listening, restarting after each pause so a long message could be dictated
 * in one go. It worked, and it was unusable: the recognition service plays its start and stop
 * earcons on every startListening, so a continuous session beeped after every sentence. There is
 * no public way to silence those -- the usual trick is muting an entire audio stream, which
 * takes the user's music with it and leaves the device muted if anything throws on the way back.
 *
 * So this follows the shape the platform actually has. A press listens until you stop talking,
 * appends what you said, and ends. Pressing again adds to what is already in the box, which is
 * how a longer message gets dictated: in sentences, at the cost of a tap each, and with one pair
 * of beeps rather than a stream of them.
 */
@Singleton
class Dictation @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class State(
        val listening: Boolean = false,
        /** This utterance, updated as it is recognised. Not cumulative across presses. */
        val transcript: String = "",
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** Whether this utterance has produced anything, final or partial. */
    private var heardSomething = false

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Main thread only: SpeechRecognizer requires the looper it was created on. */
    fun start() {
        if (_state.value.listening) return
        if (!isAvailable) {
            _state.value = State(error = "No speech recognition on this device.")
            return
        }
        _state.value = State(listening = true)

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(Listener())
        }
        listen()
    }

    /** Ends the utterance and releases the microphone. */
    fun stop() {
        release()
        _state.value = _state.value.copy(listening = false, error = null)
    }

    private fun release() {
        recognizer?.runCatching {
            setRecognitionListener(null)
            cancel()
            // Only here, at the end of the session: holding a recogniser holds the microphone
            // and the system indicator with it.
            destroy()
        }
        recognizer = null
    }

    private fun listen() {
        val speech = recognizer ?: return
        heardSomething = false

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
            release()
            _state.value = State(listening = false, error = it.message)
        }
    }

    private fun publish(text: String) {
        _state.value = _state.value.copy(transcript = text)
    }

    private inner class Listener : RecognitionListener {

        override fun onResults(results: Bundle?) {
            if (!_state.value.listening) return
            // The final result supersedes the partials rather than adding to them.
            results.firstResult()?.takeIf { it.isNotBlank() }?.let { publish(it) }
            stop()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!_state.value.listening) return
            partialResults.firstResult()?.takeIf { it.isNotBlank() }?.let {
                heardSomething = true
                publish(it)
            }
        }

        override fun onError(error: Int) {
            if (!_state.value.listening) {
                Log.d(TAG, "Ignoring error $error after the session ended")
                return
            }
            when {
                // Nothing said. Ending quietly is the whole response: a toast reading "no match"
                // for a press the user thought better of would be noise.
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> stop()

                // Words already arrived, so whatever the service is complaining about on the way
                // out, the utterance succeeded. Keep the text and end quietly.
                heardSomething -> {
                    Log.d(TAG, "Error $error after a result; keeping what was heard")
                    stop()
                }

                else -> fail(error)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun fail(error: Int) {
            Log.w(TAG, "Recognition error $error")
            release()
            _state.value = _state.value.copy(listening = false, error = describe(error))
        }
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is not granted."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a connection on this device."
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "The speech recognition service stopped responding."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This language is not available for speech recognition. " +
                "Install the offline speech pack in system settings."
        else -> "Speech recognition failed ($error)."
    }
}
