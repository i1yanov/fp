package net.family.multiki.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import net.family.multiki.Conflict

/** "This section is busy — stop the current one and start the new one, or stay?" */
@Composable
fun ConflictDialog(conflict: Conflict, onCancel: () -> Unit) {
    val stay = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(conflict.title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(conflict.message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            // "stay" first: it is the safe choice and gets the remote focus
            val alt = conflict.onAlt
            val altLabel = conflict.altLabel
            DialogButtons(
                listOfNotNull(
                    DialogAction(conflict.cancelLabel, Palette.Teal, onCancel),
                    DialogAction(conflict.confirmLabel, Palette.Red, conflict.onConfirm),
                    if (alt != null && altLabel != null) DialogAction(altLabel, Palette.RedDark, alt) else null,
                )
            )
        },
        containerColor = Palette.Paper,
    )
}
