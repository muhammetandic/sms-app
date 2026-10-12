package com.anttech.smsapp.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.anttech.smsapp.R

/**
 * Asks before several conversations are permanently removed.
 *
 * Deleting is destructive and irreversible, so the user has to confirm what was
 * selected. Shared by the inbox and the spam folder, which both delete in bulk
 * from their contextual toolbar.
 */
@Composable
fun ConfirmDeleteConversations(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_selected_title)) },
        text = {
            Text(pluralStringResource(R.plurals.delete_selected_body, count, count))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.dialog_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}