package tech.beyondscale.mitra.feature.quickadd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.feature.habits.HabitRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

private fun QuickType.icon(): ImageVector = when (this) {
    QuickType.Task -> MitraIcons.CheckSquare
    QuickType.Expense -> MitraIcons.Wallet
    QuickType.Habit -> MitraIcons.Repeat
    QuickType.Note -> MitraIcons.Note
    QuickType.Mood -> MitraIcons.Smile
}

/** Quick Add (spec §9): type chips, one parsed-as-you-type field and the chips it recognised. Shown while this composable is in the tree. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(state: QuickAddUiState, onAction: (QuickAddAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Mitra.colors.bg,
    ) {
        QuickAddContent(state, onAction)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddContent(state: QuickAddUiState, onAction: (QuickAddAction) -> Unit) {
    val c = Mitra.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = MitraDimens.pad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Quick add", style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            QuickType.entries.forEach { t ->
                MitraChip(t.label, tone = ChipTone.Outline, icon = t.icon(), selected = state.type == t, onClick = { onAction(QuickAddAction.Type(t)) })
            }
        }
        MitraTextField(
            value = state.text,
            onValueChange = { onAction(QuickAddAction.Text(it)) },
            label = if (state.type == QuickType.Task) "Task" else state.type.label,
            placeholder = when (state.type) {
                QuickType.Expense -> "₹240 food lunch"
                QuickType.Habit -> "Search today's habits"
                else -> "Call John tomorrow p1 #work"
            },
            error = state.error,
            keyboardType = KeyboardType.Text,
            imeAction = if (state.type == QuickType.Habit) ImeAction.Search else ImeAction.Done,
            onImeAction = { if (state.type == QuickType.Habit) keyboard?.hide() else onAction(QuickAddAction.Save) },
            fieldModifier = Modifier.focusRequester(focus),
        )
        if (state.notice != null) {
            Text(state.notice, style = Mitra.type.body, color = c.muted)
        } else if (state.type == QuickType.Habit) {
            HabitCheckList(state, onAction)
        } else if (state.type == QuickType.Expense) {
            if (state.amountLabel != null || state.categoryLabel != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.amountLabel?.let {
                        MitraChip(it, icon = MitraIcons.Wallet, selected = true, onRemove = { onAction(QuickAddAction.ClearAmount) })
                    }
                    state.categoryLabel?.let {
                        MitraChip(it, selected = true, onRemove = { onAction(QuickAddAction.ClearCategory) })
                    }
                }
            }
        } else if (state.dueLabel != null || state.priLabel != null || state.projectLabel != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                state.dueLabel?.let {
                    MitraChip("Due · $it", icon = MitraIcons.Calendar, selected = true, onRemove = { onAction(QuickAddAction.ClearDue) })
                }
                state.priLabel?.let {
                    MitraChip("Priority · $it", icon = MitraIcons.Flag, selected = true, onRemove = { onAction(QuickAddAction.ClearPri) })
                }
                state.projectLabel?.let {
                    MitraChip(it, selected = true, onRemove = { onAction(QuickAddAction.ClearProject) })
                }
            }
        }
        if (state.type != QuickType.Habit) {
            MitraButton(
                if (state.type == QuickType.Expense) "Save expense" else "Add",
                onClick = { onAction(QuickAddAction.Save) },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

/** Habit check: due-today habits matching the text, each with its check button, then a "New habit" row. */
@Composable
private fun HabitCheckList(state: QuickAddUiState, onAction: (QuickAddAction) -> Unit) {
    val c = Mitra.colors
    MitraCard(Modifier.padding(bottom = 12.dp), contentPadding = PaddingValues(0.dp)) {
        if (state.habitRows.isEmpty() && state.text.isBlank()) {
            Text("No habits due today", style = Mitra.type.sm, color = c.muted, modifier = Modifier.padding(14.dp))
        }
        state.habitRows.forEach { row ->
            val check = { onAction(QuickAddAction.CheckHabit(row.id)) }
            HabitRow(row, onCheck = check, onOpen = check)
        }
        val name = state.text.trim()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onAction(QuickAddAction.NewHabit) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(MitraIcons.Plus, contentDescription = null, tint = c.accent)
            Text(
                if (name.isEmpty()) "New habit" else "New habit \u201c$name\u201d",
                style = Mitra.type.body.copy(fontWeight = FontWeight.Medium),
                color = c.accent,
            )
        }
    }
}
