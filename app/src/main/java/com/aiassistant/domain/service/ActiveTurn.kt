package com.aiassistant.domain.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a reply is being generated, and which conversation it belongs to.
 *
 * The shell needs this and cannot reach the ChatViewModel: that one is scoped to a navigation
 * entry, and the sidebar sits outside the NavHost. Leaving the conversation abandons the reply,
 * so the sidebar has to know there is something to lose before it navigates.
 *
 * Held in the app process only. The on-device engine runs in `:llm` and reports back through
 * the ChatViewModel like any other reply, so it is covered from here too.
 */
@Singleton
class ActiveTurn @Inject constructor() {

    private val _title = MutableStateFlow<String?>(null)

    /** The title of the conversation being answered, or null when nothing is running. */
    val title: StateFlow<String?> = _title.asStateFlow()

    private var owner: Any? = null

    fun begin(owner: Any, conversationTitle: String) {
        this.owner = owner
        _title.value = conversationTitle
    }

    /**
     * Clears the flag, but only for whoever set it.
     *
     * One flag, one instance, several ChatViewModels over the life of the app -- and a screen
     * being built can run its own reset while the screen it replaced is still winding down. The
     * owner check stops that reset reporting someone else's reply as finished.
     *
     * Call it from a `finally`: a turn that ends by being cancelled still has to clear the flag,
     * or every later navigation asks about a reply that stopped long ago.
     */
    fun end(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        _title.value = null
    }
}
