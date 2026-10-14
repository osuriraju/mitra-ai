package tech.beyondscale.mitra.feature.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.feature.common.MitraTimePickerDialog

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * Create/edit habit form (web `HabitForm`) as a full-height bottom sheet, host-owned like `TaskFormSheet`: the shell
 * sends `Open` / `Edit` and also collects [HabitEditorViewModel.events] and [HabitEditorViewModel.navigation], so a
 * save's snackbar shows after the sheet closes. Nothing is shown while the form is closed. [onDismiss] runs after the
 * form closes from the sheet (Cancel, swipe down, back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitFormSheet(vm: HabitEditorViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.form.open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        vm.onAction(HabitEditorAction.Dismiss)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, containerColor = Mitra.colors.bg) {
        HabitForm(state, vm::onAction, onCancel = { scope.launch { sheetState.hide() }.invokeOnCompletion { dismiss() } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitForm(state: HabitEditorUiState, onAction: (HabitEditorAction) -> Unit, onCancel: () -> Unit) {
    val f = state.form
    val c = Mitra.colors
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    // A new habit starts in the name field (web `autoFocus`); editing doesn't pop the keyboard over the form.
    LaunchedEffect(Unit) { if (f.editId == null) nameFocus.requestFocus() }

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.heading, style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
            MitraTextField(
                value = f.name,
                onValueChange = { onAction(HabitEditorAction.Name(it)) },
                label = "Name",
                placeholder = "e.g. Evening stretch",
                error = f.error,
                imeAction = ImeAction.Done,
                onImeAction = { onAction(HabitEditorAction.Save) },
                fieldModifier = Modifier.focusRequester(nameFocus),
            )

            Field("Icon") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HABIT_ICONS.forEach { id -> IconChip(id, selected = id == f.icon) { onAction(HabitEditorAction.Icon(id)) } }
                }
            }

            Field("Type") {
                MitraSegmented(
                    options = HabitType.entries.map { it.label },
                    selectedIndex = f.type.ordinal,
                    onSelect = { onAction(HabitEditorAction.Type(HabitType.entries[it])) },
                )
            }

            if (state.showTarget) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    MitraTextField(
                        value = f.target,
                        onValueChange = { onAction(HabitEditorAction.Target(it)) },
                        label = "Target",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    MitraTextField(
                        value = f.unit,
                        onValueChange = { onAction(HabitEditorAction.Unit(it)) },
                        label = "Unit",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Field("Time of day") {
                MitraSegmented(
                    options = TimeOfDay.entries.map { it.label },
                    selectedIndex = f.timeOfDay.ordinal,
                    onSelect = { onAction(HabitEditorAction.TimeOfDay(TimeOfDay.entries[it])) },
                )
            }

            Field("Schedule") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SCHEDULE_PRESETS.forEach { p ->
                        MitraChip(p, tone = ChipTone.Outline, selected = state.scheduleLabel == p, onClick = { onAction(HabitEditorAction.Schedule(p)) })
                    }
                    // Web: a label only — it lights up when the day toggles match no preset.
                    MitraChip(SCHEDULE_CUSTOM, tone = ChipTone.Outline, selected = state.scheduleLabel == SCHEDULE_CUSTOM)
                }
            }

            Field("Days") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_LETTERS.forEachIndexed { i, d ->
                        DayToggle(d, DAY_NAMES[i], on = f.days[i], modifier = Modifier.weight(1f)) { onAction(HabitEditorAction.ToggleDay(i)) }
                    }
                }
            }

            MitraCard(contentPadding = SettingsCardPadding) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBox(MitraIcons.Bell)
                    MitraSwitchRow(
                        title = "Reminder",
                        checked = f.reminderOn,
                        onCheckedChange = { onAction(HabitEditorAction.ReminderOn(it)) },
                        sub = if (f.reminderOn) null else "Off",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (f.reminderOn) {
                    Box(Modifier.padding(start = 48.dp, bottom = 4.dp)) {
                        MitraChip(fmtTime(f.reminder), icon = MitraIcons.Clock, selected = true, onClick = { pickTime = true })
                    }
                }
                HorizontalDivider(color = c.border, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBox(MitraIcons.Target)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Supports goal", style = Mitra.type.bodyBold, color = c.text)
                        GoalPicker(state, onAction)
                    }
                }
                HorizontalDivider(color = c.border, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBox(MitraIcons.Heart)
                    MitraSwitchRow(
                        title = "Gentle mode",
                        checked = f.gentle,
                        onCheckedChange = { onAction(HabitEditorAction.Gentle(it)) },
                        sub = "Missed days show as recovering, never as failure",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MitraButton("Cancel", onClick = onCancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
            MitraButton(state.saveLabel, onClick = { onAction(HabitEditorAction.Save) }, modifier = Modifier.weight(1f))
        }
    }

    if (pickTime) MitraTimePickerDialog(f.reminder, onPick = { onAction(HabitEditorAction.ReminderTime(it)) }, onDismiss = { pickTime = false })
}

private val SettingsCardPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

@Composable
private fun IconChip(id: String, selected: Boolean, onClick: () -> Unit) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.size(48.dp)
            .clip(shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Icon $id" + if (selected) ", selected" else "" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 44.dp, height = 32.dp).clip(shape)
                .background(if (selected) c.accentSoft else Color.Transparent)
                .border(1.dp, if (selected) c.accent.copy(alpha = 0.4f) else c.border, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(habitIcon(id), contentDescription = null, tint = if (selected) c.accent else c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** Web `.scale` day buttons: filled accent when on. */
@Composable
private fun DayToggle(letter: String, name: String, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(MitraDimens.radiusSm)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (on) c.accent else c.surface2)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.onAccent else c.muted)
    }
}

@Composable
private fun GoalPicker(state: HabitEditorUiState, onAction: (HabitEditorAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.goals.firstOrNull { it.id == state.form.goalId } ?: state.goals.first()
    Box {
        MitraChip(
            "${current.label} ▾",
            tone = ChipTone.Outline,
            selected = current.id != null,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.goals.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.label, style = Mitra.type.body, maxLines = 1, color = if (g.id == state.form.goalId) Mitra.colors.accent else Mitra.colors.text) },
                    onClick = { open = false; onAction(HabitEditorAction.Goal(g.id)) },
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        content()
    }
}
