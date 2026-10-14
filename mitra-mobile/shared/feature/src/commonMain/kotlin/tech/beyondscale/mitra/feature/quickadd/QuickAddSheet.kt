package tech.beyondscale.mitra.feature.quickadd

import androidx.compose.foundation.horizontalScroll
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
            placeholder = "Call John tomorrow p1 #work",
            error = state.error,
            imeAction = ImeAction.Done,
            onImeAction = { onAction(QuickAddAction.Save) },
            fieldModifier = Modifier.focusRequester(focus),
        )
        if (state.notice != null) {
            Text(state.notice, style = Mitra.type.body, color = c.muted)
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
        MitraButton(
            "Add",
            onClick = { onAction(QuickAddAction.Save) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
}
