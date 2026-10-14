package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.feature.common.MitraDatePickerDialog

/**
 * Create/edit goal form (web `GoalForm`) as a full-height bottom sheet, host-owned like `TaskFormSheet`: the shell
 * sends [GoalEditorAction.Open] / [GoalEditorAction.Edit], shows this, and collects [GoalEditorViewModel.events]
 * (snackbars) and [GoalEditorViewModel.navigation] (open the created goal). Nothing is shown while the form is closed;
 * [onDismiss] runs after the sheet is dismissed or cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormSheet(vm: GoalEditorViewModel, onDismiss: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.form.open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissNow: () -> Unit = {
        vm.onAction(GoalEditorAction.Dismiss)
        onDismiss()
    }
    val cancel: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { dismissNow() } }
    ModalBottomSheet(onDismissRequest = dismissNow, sheetState = sheetState, containerColor = Mitra.colors.bg) {
        GoalForm(state, vm::onAction, onCancel = cancel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalForm(state: GoalEditorUiState, onAction: (GoalEditorAction) -> Unit, onCancel: () -> Unit) {
    val f = state.form
    val c = Mitra.colors
    var pickDue by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }
    val sfx = if (f.unit.isEmpty()) "" else " (${f.unit})"

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.heading, style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
            MitraTextField(
                value = f.name,
                onValueChange = { onAction(GoalEditorAction.Name(it)) },
                label = "Goal",
                placeholder = "e.g. Build ₹1,00,000 emergency fund",
                error = f.error,
                imeAction = ImeAction.Done,
                onImeAction = { onAction(GoalEditorAction.Save) },
                fieldModifier = Modifier.focusRequester(nameFocus),
            )

            Field("Type") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GoalType.entries.forEach { t ->
                        MitraChip(t.label, tone = ChipTone.Outline, selected = f.type == t, onClick = { onAction(GoalEditorAction.Type(t)) })
                    }
                }
                Text(state.typeHint, style = Mitra.type.xs, color = c.muted)
            }

            if (state.showNumbers) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MitraTextField(
                        value = f.current,
                        onValueChange = { onAction(GoalEditorAction.Current(it)) },
                        label = "Current$sfx",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    MitraTextField(
                        value = f.target,
                        onValueChange = { onAction(GoalEditorAction.Target(it)) },
                        label = "Target$sfx",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.showUnit) {
                Field("Unit") {
                    MitraSegmented(
                        options = listOf("Number", "₹ Rupees"),
                        selectedIndex = if (f.unit == "₹") 1 else 0,
                        onSelect = { onAction(GoalEditorAction.Unit(rupees = it == 1)) },
                    )
                }
            }

            Field("Due") {
                MitraChip(
                    if (f.due.isEmpty()) "Pick date" else "${fmtShort(f.due)} ${f.due.take(4)}",
                    tone = ChipTone.Outline,
                    icon = MitraIcons.Calendar,
                    selected = f.due.isNotEmpty(),
                    onClick = { pickDue = true },
                )
            }

            Field("Horizon") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GoalTerm.entries.forEach { t ->
                        MitraChip(t.wire, tone = ChipTone.Outline, icon = MitraIcons.Flag, selected = f.term == t, onClick = { onAction(GoalEditorAction.Term(t)) })
                    }
                }
            }

            MitraTextField(
                value = f.nextAction,
                onValueChange = { onAction(GoalEditorAction.NextAction(it)) },
                label = "Next action",
                placeholder = "The one thing to do next",
            )

            Field("Review") {
                MitraSegmented(
                    options = ReviewEvery.entries.map { it.wire },
                    selectedIndex = f.review.ordinal,
                    onSelect = { onAction(GoalEditorAction.Review(ReviewEvery.entries[it])) },
                )
            }

            if (state.showMilestones) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MitraTextField(
                        value = f.milestones,
                        onValueChange = { onAction(GoalEditorAction.Milestones(it)) },
                        label = "Milestones (one per line)",
                        error = f.milestonesError,
                        singleLine = false,
                        minLines = 4,
                        imeAction = ImeAction.Default,
                    )
                    Text("Targets and dates are spread evenly; edit them on the goal page.", style = Mitra.type.xs, color = c.muted)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MitraButton("Cancel", onClick = onCancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
            MitraButton(state.saveLabel, onClick = { onAction(GoalEditorAction.Save) }, modifier = Modifier.weight(1f))
        }
    }

    if (pickDue) {
        MitraDatePickerDialog(f.due.ifEmpty { state.today.ifEmpty { null } }, onPick = { onAction(GoalEditorAction.Due(it)) }, onDismiss = { pickDue = false })
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        content()
    }
}
