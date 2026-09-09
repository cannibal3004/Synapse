package com.aiassistant.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One step of an assistant turn, in the order it happened.
 *
 * Reasoning and tool calls used to be two separate pieces of UI that appeared at different
 * moments, which shifted the transcript under the reader. Recording them as one sequence lets a
 * single row show the whole turn, live and afterwards.
 */
sealed interface TurnActivity : Parcelable {
    @Parcelize
    data class Thought(val text: String) : TurnActivity

    @Parcelize
    data class ToolRun(val name: String, val arguments: String) : TurnActivity
}
