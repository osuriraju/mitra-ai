package tech.beyondscale.mitra.designsystem.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tech.beyondscale.mitra.designsystem.theme.Mitra

/** Confirm dialog styled like the existing delete confirms (web `Confirm`); [danger] tints the confirm action. */
@Composable
fun MitraConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Mitra.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Mitra.type.h3, color = c.text) },
        text = { Text(body, style = Mitra.type.body, color = c.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = if (danger) c.danger else c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        containerColor = c.surface,
    )
}
