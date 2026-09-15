package com.aiassistant.presentation.vm

import androidx.lifecycle.ViewModel
import com.aiassistant.domain.service.ActiveTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * What the navigation shell needs to know, which is almost nothing.
 *
 * Only exists because the sidebar lives outside the NavHost and so cannot see the ChatViewModel,
 * yet has to know whether navigating away would abandon a reply.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    activeTurn: ActiveTurn
) : ViewModel() {

    /** Title of the conversation being answered, or null when nothing is running. */
    val replyingTo: StateFlow<String?> = activeTurn.title
}
