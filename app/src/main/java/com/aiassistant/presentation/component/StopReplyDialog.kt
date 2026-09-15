package com.aiassistant.presentation.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Asks before an action that would throw away a reply in progress.
 *
 * Phrased around what is lost rather than around the navigation: the navigation is the part the
 * user already meant to do, the reply stopping is the part they may not know about.
 */
@Composable
fun StopReplyDialog(
    conversationTitle: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val name = conversationTitle?.takeIf { it.isNotBlank() } ?: "this conversation"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop the reply?") },
        text = {
            Text(
                "Still answering in “$name”. Leaving now discards the reply. The message " +
                    "you sent stays, without an answer."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Stop and leave") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay") } }
    )
}
