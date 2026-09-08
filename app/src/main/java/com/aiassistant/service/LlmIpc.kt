package com.aiassistant.service

/**
 * Wire protocol shared by [LlmService] and [com.aiassistant.client.LlmClient].
 *
 * The service runs in its own process, so these constants are the only contract between the two
 * sides. Keep them here rather than duplicated per file so a new message cannot be added to one
 * side only.
 */
internal object LlmIpc {

    // Client -> service
    const val MSG_INITIALIZE = 1
    const val MSG_CHAT = 2
    const val MSG_SHUTDOWN = 3
    const val MSG_GET_STATE = 4
    const val MSG_NEEDS_REINIT = 5
    const val MSG_PING = 6
    const val MSG_RESET_CONVERSATION = 7
    const val MSG_CANCEL = 8

    // Service -> client
    const val CB_INIT_DONE = 100
    const val CB_CHUNK = 101
    const val CB_DONE = 102
    const val CB_ERROR = 103
    const val CB_STATE = 104
    const val CB_NEEDS_REINIT = 105
    const val CB_THINKING = 106

    const val EXTRA_MODEL_PATH = "modelPath"
    const val EXTRA_SYSTEM_PROMPT = "systemPrompt"
    const val EXTRA_TEMPERATURE = "temperature"
    const val EXTRA_TOP_K = "topK"
    const val EXTRA_TOP_P = "topP"
    const val EXTRA_USE_TOOLS = "useTools"
    const val EXTRA_ENABLE_THINKING = "enableThinking"
    const val EXTRA_THINKING_BUDGET = "thinkingTokenBudget"
    const val EXTRA_MAX_OUTPUT_TOKENS = "maxOutputTokens"
    const val EXTRA_BACKEND = "backend"
    const val EXTRA_CONTEXT_TOKENS = "contextTokens"
    const val EXTRA_MESSAGES_JSON = "messagesJson"

    const val KEY_TEXT = "text"
    const val KEY_RESPONSE = "response"
    const val KEY_ERROR = "error"
    const val KEY_STATE = "state"
    const val KEY_STATS = "stats"
}
