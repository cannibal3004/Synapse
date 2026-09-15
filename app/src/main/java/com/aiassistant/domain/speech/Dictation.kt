package com.aiassistant.domain.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Breathing room before listening again while held.
 *
 * Calling startListening from inside onResults gets ERROR_RECOGNIZER_BUSY on several devices;
 * the service has not finished with the previous utterance yet.
 */
private const val RESTART_DELAY_MS = 250L

/**
 * How long a pause is tolerated inside one held session.
 *
 * Only meaningful while held, where the release ends the session and silence therefore does not
 * have to. Set high so a pause for thought stays inside one recognition session -- every new
 * session costs a pair of earcons. Advisory: plenty of OEM recognisers ignore it and stop at
 * their own idea of a pause, which is why the restart below exists at all.
 */
private const val HELD_SILENCE_MS = 15_000L

/** The same pause, when a tap has to end the utterance by itself. */
private const val TAPPED_SILENCE_MS = 2_000L

/** Consecutive failures with nothing recognised before a held session is given up on. */
private const val MAX_TRANSIENT_FAILURES = 3

/**
 * Speech to text, in two shapes.
 *
 * Tapped, it takes one utterance: listens until you stop talking, puts the words in the box and
 * ends. Held, it keeps going for as long as the button is down, stitching utterances together
 * until you let go.
 *
 * The split exists because of a noise. The recognition service plays start and stop earcons on
 * every startListening, and nothing public silences them, so a session that restarts after every
 * pause beeps after every sentence. While held that is worth accepting and mostly avoidable --
 * silence no longer has to end anything, so the pause tolerance goes up and a whole held phrase
 * usually fits in one session. Tapped, there is no such signal, so one utterance it is.
 *
 * One recogniser per session either way, reused across restarts. Destroying the client between
 * utterances tears down the shared recognition service, which then reports SERVER_DISCONNECTED
 * to the client that replaced it: recognition worked, and the session died of its own restart.
 */
@Singleton
class Dictation @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class State(
        val listening: Boolean = false,
        /** What has been recognised this session, including the utterance in progress. */
        val transcript: String = "",
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** True for a held session, which restarts until released. */
    private var continuous = false

    /** Utterances already finalised this session; the live partial is appended for display. */
    private var settled = ""

    /** Whether the utterance in progress has produced anything, final or partial. */
    private var heardSomething = false
    private var transientFailures = 0

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Begins listening. Main thread only: SpeechRecognizer requires the looper it was created on.
     *
     * @param continuous true while a button is held, so pauses restart rather than finish.
     */
    fun start(continuous: Boolean) {
        if (_state.value.listening) return
        if (!isAvailable) {
            _state.value = State(error = "No speech recognition on this device.")
            return
        }
        this.continuous = continuous
        settled = ""
        transientFailures = 0
        _state.value = State(listening = true)

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(Listener())
        }
        listen()
    }

    /** Ends the session and releases the microphone. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        release()
        _state.value = _state.value.copy(listening = false, error = null)
    }

    private fun release() {
        recognizer?.runCatching {
            setRecognitionListener(null)
            cancel()
            // Only at the end of a session: holding a recogniser holds the microphone, and the
            // system recording indicator with it.
            destroy()
        }
        recognizer = null
    }

    private fun listen() {
        val speech = recognizer ?: return
        heardSomething = false

        val silence = if (continuous) HELD_SILENCE_MS else TAPPED_SILENCE_MS
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silence
            )
        }
        runCatching { speech.startListening(intent) }.onFailure {
            Log.e(TAG, "startListening failed", it)
            release()
            _state.value = State(listening = false, error = it.message)
        }
    }

    /** Listens again, if this is a held session that has not been released. */
    private fun continueOrStop() {
        if (!continuous || !_state.value.listening) {
            stop()
            return
        }
        handler.postDelayed({ if (_state.value.listening) listen() }, RESTART_DELAY_MS)
    }

    private fun publish(live: String) {
        val joined = if (continuous) {
            listOf(settled, live).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            live
        }
        _state.value = _state.value.copy(transcript = joined)
    }

    private inner class Listener : RecognitionListener {

        override fun onResults(results: Bundle?) {
            if (!_state.value.listening) return
            val text = results.firstResult()?.takeIf { it.isNotBlank() }
            if (text != null) {
                transientFailures = 0
                // Held sessions accumulate; a tapped one is the single utterance it returned.
                if (continuous) {
                    settled = listOf(settled, text).filter { it.isNotBlank() }.joinToString(" ")
                }
                publish(if (continuous) "" else text)
            } else {
                publish("")
            }
            continueOrStop()
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
                // Nothing said. While held that is just a pause; tapped, it is the end. Either
                // way it is not worth a toast -- a press thought better of is not a failure.
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    publish("")
                    continueOrStop()
                }

                // Words already arrived, so whatever the service is complaining about on the way
                // out, the utterance succeeded. Keep them.
                heardSomething -> {
                    Log.d(TAG, "Error $error after a result; keeping what was heard")
                    continueOrStop()
                }

                // Restarting quickly upsets the service; believe it only if nothing at all is
                // being recognised.
                continuous && (
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    ) -> {
                    transientFailures++
                    Log.d(TAG, "Transient error $error (#$transientFailures)")
                    if (transientFailures >= MAX_TRANSIENT_FAILURES) fail(error) else continueOrStop()
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
            handler.removeCallbacksAndMessages(null)
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
