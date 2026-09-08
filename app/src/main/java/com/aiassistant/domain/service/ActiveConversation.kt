package com.aiassistant.domain.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation inference is currently running for, in the app process.
 *
 * Tools are handed a plain arguments string and have no other way to learn which conversation
 * they were invoked from, so `remember_fact` reads provenance from here. Set it before starting
 * inference. The on-device path does not use this: that engine runs in the `:llm` process and
 * takes the id from the message it was sent.
 */
@Singleton
class ActiveConversation @Inject constructor() {

    @Volatile
    var id: String = ""
        private set

    fun set(conversationId: String) {
        id = conversationId
    }
}
