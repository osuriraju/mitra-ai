package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.goals.goalTargetLabel
import tech.beyondscale.mitra.domain.goals.goalValueLabel
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalType

/** The log progress sheet's copy for one goal (web `LogProgress`). */
data class LogProgressUi(
    val goalName: String,
    /** Numeric and date goals take an amount; milestone and habit goals only a note. */
    val numeric: Boolean,
    /** "Add to ₹58,000 (target ₹1,00,000)" */
    val fieldLabel: String,
    /** "8000" for ₹ goals, else "1". */
    val placeholder: String,
    /** "₹" or "". */
    val unit: String,
    /** The note shown instead of the field for milestone / habit goals. */
    val info: String,
)

fun logProgressUi(g: Goal): LogProgressUi = LogProgressUi(
    goalName = g.name,
    numeric = g.type == GoalType.Numeric || g.type == GoalType.Date,
    fieldLabel = "Add to ${goalValueLabel(g)} (target ${goalTargetLabel(g)})",
    placeholder = if (g.unit == "₹") "8000" else "1",
    unit = g.unit,
    info = if (g.type == GoalType.Milestone) {
        "Tick milestones on the goal page — progress is milestones reached ÷ total."
    } else {
        "Progress for habit-supported goals comes from the linked habits’ consistency. Add a note below."
    },
)

/** Digits and one decimal point, as the web field's `replace(/[^\d.]/g, '')`. */
internal fun goalDecimalInput(s: String): String = s.filter { it.isDigit() || it == '.' }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogProgressSheet(ui: LogProgressUi, onSave: (value: String, note: String) -> Unit, onDismiss: () -> Unit) {
    val c = Mitra.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val cancel: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    var value by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val save = { onSave(value, note) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Log progress", style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
                    Text(ui.goalName, style = Mitra.type.sm, color = c.muted)
                }
                if (ui.numeric) {
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    MitraTextField(
                        value = value,
                        onValueChange = { value = goalDecimalInput(it) },
                        label = ui.fieldLabel,
                        placeholder = ui.placeholder,
                        keyboardType = KeyboardType.Decimal,
                        fieldModifier = Modifier.focusRequester(focus),
                    )
                } else {
                    MitraCard(tone = CardTone.Soft) { Text(ui.info, style = Mitra.type.sm, color = c.text) }
                }
                MitraTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = "Note (optional)",
                    placeholder = "What moved the needle?",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                )
            }
            Row(Modifier.fillMaxWidth().padding(MitraDimens.pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MitraButton("Cancel", onClick = cancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
                MitraButton("Save", onClick = save, modifier = Modifier.weight(1f))
            }
        }
    }
}
