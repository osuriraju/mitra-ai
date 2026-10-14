package tech.beyondscale.mitra.feature.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import tech.beyondscale.mitra.designsystem.theme.Mitra

private const val DAY_MS = 86_400_000L

/** Material date pickers speak UTC-midnight millis; the app speaks ISO days ("2026-10-14"). */
fun isoFromUtcMillis(ms: Long): String = LocalDate.fromEpochDays(ms.floorDiv(DAY_MS)).toString()

fun utcMillisFromIso(iso: String): Long = LocalDate.parse(iso).toEpochDays() * DAY_MS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitraDatePickerDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let(::utcMillisFromIso))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(isoFromUtcMillis(it)) }; onDismiss() },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK", color = Mitra.colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mitra.colors.muted) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitraTimePickerDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val parts = initial?.split(":")
    val h = parts?.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 9
    val m = parts?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    val state = rememberTimePickerState(h, m, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick("${state.hour.toString().padStart(2, '0')}:${state.minute.toString().padStart(2, '0')}")
                onDismiss()
            }) { Text("OK", color = Mitra.colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mitra.colors.muted) } },
        text = { TimePicker(state) },
    )
}
