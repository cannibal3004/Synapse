package com.aiassistant.domain.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Speaker"

/** Long enough to be worth speaking, short enough that speech starts while the reply streams. */
private const val FLUSH_AT_CHARS = 180

/**
 * Reads replies aloud as they arrive.
 *
 * Fed the same deltas the transcript gets, and speaks at sentence boundaries rather than waiting
 * for the turn to finish -- on a long answer, waiting would leave the user staring at text
 * scrolling past in silence.
 *
 * Two things it deliberately does not read: fenced code, and markdown punctuation. A spoken
 * backtick or a URL read character by character is worse than the silence it replaces.
 */
@Singleton
class Speaker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** Whatever has arrived since the last sentence was handed to the engine. */
    private val pending = StringBuilder()

    /** Inside a ``` fence, where everything is skipped until it closes. */
    private var inCodeFence = false

    private val utteranceCounter = AtomicInteger(0)

    /**
     * Utterances that arrived before the engine finished binding.
     *
     * TextToSpeech connects to its service asynchronously, and speak() before that lands is
     * dropped with nothing but a log line -- which ate the opening sentence of every reply,
     * the one most worth hearing.
     */
    private val awaitingEngine = ArrayDeque<Pair<String, String>>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** Must be called from the main thread; TextToSpeech binds a service on the caller's looper. */
    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "TextToSpeech unavailable (status=$status)")
                return@TextToSpeech
            }
            tts?.language = Locale.getDefault()
            Log.d(TAG, "TextToSpeech ready")
            while (awaitingEngine.isNotEmpty()) {
                val (id, text) = awaitingEngine.removeFirst()
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                // Only clear on the last queued utterance; onDone fires per sentence and the
                // queue is usually several deep while a reply is still arriving.
                if (utteranceId == "u${utteranceCounter.get()}") _isSpeaking.value = false
            }

            @Deprecated("Required override", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }
        })
    }

    /**
     * Adds streamed text, speaking whatever complete sentences it now has.
     *
     * Call from the main thread, once per delta.
     */
    fun feed(delta: String) {
        ensureEngine()
        pending.append(delta)
        while (true) {
            val chunk = takeSpeakableChunk() ?: break
            enqueue(chunk)
        }
    }

    /** Speaks whatever is left, at the end of a turn. */
    fun flush() {
        ensureEngine()
        val rest = clean(pending.toString())
        pending.setLength(0)
        inCodeFence = false
        if (rest.isNotBlank()) enqueue(rest)
    }

    /** Stops immediately and forgets what was queued. Used for barge-in. */
    fun stop() {
        pending.setLength(0)
        awaitingEngine.clear()
        inCodeFence = false
        tts?.stop()
        _isSpeaking.value = false
    }

    private fun enqueue(text: String) {
        val id = "u${utteranceCounter.incrementAndGet()}"
        if (!ready) {
            awaitingEngine.addLast(id to text)
            return
        }
        // QUEUE_ADD, so a reply arriving in pieces is spoken as one continuous stretch rather
        // than each sentence cutting off the one before it.
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    /**
     * Pulls one speakable sentence out of the buffer, or null if there is not one yet.
     *
     * Waiting for a sentence terminator keeps the prosody right, but a model writing a long
     * bulleted list can go a while without one, so a length cap breaks at the last space
     * instead.
     */
    private fun takeSpeakableChunk(): String? {
        val text = pending.toString()

        val terminator = text.indexOfFirst(
            fromIndex = 1,
            predicate = { it == '.' || it == '!' || it == '?' || it == '\n' }
        )
        val cut = when {
            terminator >= 0 -> terminator + 1
            text.length >= FLUSH_AT_CHARS -> text.lastIndexOf(' ', FLUSH_AT_CHARS).takeIf { it > 0 }
                ?: return null
            else -> return null
        }

        val raw = text.substring(0, cut)
        pending.delete(0, cut)
        return clean(raw).takeIf { it.isNotBlank() }
    }

    private inline fun String.indexOfFirst(fromIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in fromIndex until length) if (predicate(this[i])) return i
        return -1
    }

    /**
     * Turns a slice of markdown into something worth hearing.
     *
     * Deliberately lossy. A spoken URL is noise, and so is every asterisk in a bulleted list.
     */
    private fun clean(raw: String): String {
        val lines = raw.lines().filter { line ->
            val fence = line.trimStart().startsWith("```")
            if (fence) inCodeFence = !inCodeFence
            !fence && !inCodeFence
        }

        return lines.joinToString(" ") { line ->
            line.trim()
                .removePrefix("#").removePrefix("#").removePrefix("#").trimStart()
                .replace(Regex("^[-*+]\\s+"), "")
                .replace(Regex("^\\d+\\.\\s+"), "")
                // Link text is worth hearing; the target never is.
                .replace(Regex("""\[([^]]*)]\([^)]*\)"""), "$1")
                .replace(Regex("""https?://\S+"""), "a link")
                .replace(Regex("""`([^`]*)`"""), "$1")
                .replace(Regex("""\*\*([^*]*)\*\*"""), "$1")
                .replace(Regex("""\*([^*]*)\*"""), "$1")
                .replace(Regex("""^\|.*\|$"""), "")
        }.replace(Regex("\\s{2,}"), " ").trim()
    }
}
