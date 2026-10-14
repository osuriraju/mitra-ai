package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.designsystem.components.AmountDisplay
import tech.beyondscale.mitra.designsystem.components.AmountKeypad
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.common.MitraDatePickerDialog
import tech.beyondscale.mitra.feature.common.MitraTimePickerDialog

private val KINDS = listOf(TxKind.Expense, TxKind.Income, TxKind.Transfer)

/**
 * Add/edit transaction sheet (web `TxForm`), host-owned like `TaskFormSheet`: the shell calls `vm.start(editId)`, shows
 * this, and hides it on [onDismiss]. The amount is typed on [AmountKeypad] only — the system keyboard never opens for it.
 * [onManageCategories] shows the "Manage" link next to Category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxFormSheet(vm: TxEditorViewModel, onDismiss: () -> Unit, onManageCategories: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    EventEffect(vm.events, vm::onAction, onClose = close)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Mitra.colors.bg) {
        if (state.ready) {
            TxForm(
                state, vm::onAction,
                onManageCategories = onManageCategories?.let { manage -> { close(); manage() } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TxForm(state: TxEditorUiState, onAction: (TxEditorAction) -> Unit, onManageCategories: (() -> Unit)?) {
    val c = Mitra.colors
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var newCat by rememberSaveable { mutableStateOf(false) }
    // Editing hides the keypad until the amount is tapped.
    var editKeypad by rememberSaveable { mutableStateOf(false) }
    val keypad = state.showKeypad || editKeypad

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.title, style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
            MitraSegmented(
                options = KINDS.map(::kindName),
                selectedIndex = KINDS.indexOf(state.kind),
                onSelect = { onAction(TxEditorAction.Kind(KINDS[it])) },
            )
            AmountDisplay(
                state.amountText,
                Modifier.then(
                    if (state.showKeypad) Modifier
                    else Modifier.clickable(role = Role.Button, onClickLabel = if (editKeypad) "Hide keypad" else "Change amount") { editKeypad = !editKeypad },
                ),
            )
            state.error?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    style = Mitra.type.sm, color = c.danger, textAlign = TextAlign.Center,
                )
            }
            state.duplicate?.let { DuplicateBanner(it) }

            if (state.showCategories) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldLabel("Category", Modifier.weight(1f))
                        if (onManageCategories != null) {
                            TextButton(onClick = onManageCategories) { Text("Manage", style = Mitra.type.xs, color = c.accent) }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.categories.forEach { o ->
                            MitraChip(o.label, tone = ChipTone.Outline, selected = o.id == state.categoryId, onClick = { onAction(TxEditorAction.Category(o.id)) })
                        }
                        MitraChip("New category", tone = ChipTone.Accent, icon = MitraIcons.Plus, selected = newCat, onClick = { newCat = !newCat })
                    }
                    if (newCat) NewCategoryRow(onAdd = { name, emoji -> onAction(TxEditorAction.NewCategory(name, emoji)); newCat = false })
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(state.accountLabel)
                OptionPicker(state.accounts, state.accountId, "Choose account", MitraIcons.Wallet) { onAction(TxEditorAction.Account(it)) }
                state.accountHint?.let { Text(it, style = Mitra.type.xs, color = c.muted) }
            }
            if (state.kind == TxKind.Transfer) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("To account")
                    OptionPicker(state.toAccounts, state.toAccountId, "Choose account", MitraIcons.Wallet) { onAction(TxEditorAction.ToAccount(it)) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Date")
                val today = state.today
                val quick = listOf("Today" to today, "Yesterday" to addDays(today, -1))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    quick.forEach { (label, day) ->
                        MitraChip(label, tone = ChipTone.Outline, selected = state.date == day, onClick = { onAction(TxEditorAction.Date(day)) })
                    }
                    val custom = state.date.takeIf { d -> quick.none { it.second == d } }
                    MitraChip(
                        custom?.let { fmtDay(it, today) } ?: "Pick date",
                        tone = ChipTone.Outline, icon = MitraIcons.Calendar, selected = custom != null,
                        onClick = { pickDate = true },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Time")
                MitraChip(state.timeLabel.ifEmpty { "Add time" }, tone = ChipTone.Outline, icon = MitraIcons.Clock, onClick = { pickTime = true })
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MitraTextField(
                    value = state.merchant,
                    onValueChange = { onAction(TxEditorAction.Merchant(it)) },
                    label = state.merchantLabel,
                    placeholder = state.merchantPlaceholder,
                )
                if (state.recentMerchants.isNotEmpty()) {
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recent:", style = Mitra.type.xs, color = c.muted, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
                        state.recentMerchants.forEach { m ->
                            MitraChip(m, tone = ChipTone.Outline, onClick = { onAction(TxEditorAction.Merchant(m)) })
                        }
                    }
                }
            }
            MitraTextField(
                value = state.note,
                onValueChange = { onAction(TxEditorAction.Note(it)) },
                label = "Note",
                placeholder = "Optional",
                imeAction = ImeAction.Done,
            )
            if (state.showRepeat || state.showDelete) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.showRepeat) {
                        MitraButton(
                            state.repeatLabel, onClick = { onAction(TxEditorAction.ToggleRepeat) },
                            kind = if (state.repeat) ButtonKind.Soft else ButtonKind.Outline, icon = MitraIcons.Repeat,
                        )
                    }
                    if (state.showDelete) {
                        MitraButton("Delete", onClick = { confirmDelete = true }, kind = ButtonKind.Danger, icon = MitraIcons.Trash, enabled = !state.saving)
                    }
                }
            }
            if (keypad) {
                AmountKeypad(
                    onKey = { onAction(TxEditorAction.Key(it)) },
                    onBackspace = { onAction(TxEditorAction.Backspace) },
                    onClear = { onAction(TxEditorAction.Amount("")) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.showSaveAgain) {
                MitraButton(
                    "Save & add another", onClick = { onAction(TxEditorAction.Save(again = true)) },
                    kind = ButtonKind.Outline, enabled = !state.saving, modifier = Modifier.weight(1f),
                )
            }
            MitraButton(
                state.saveLabel, onClick = { onAction(TxEditorAction.Save(again = false)) },
                loading = state.saving, modifier = Modifier.weight(1f),
            )
        }
    }

    if (pickDate) MitraDatePickerDialog(state.date.ifEmpty { null }, onPick = { onAction(TxEditorAction.Date(it)) }, onDismiss = { pickDate = false })
    if (pickTime) MitraTimePickerDialog(state.time.ifEmpty { null }, onPick = { onAction(TxEditorAction.Time(it)) }, onDismiss = { pickTime = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?", style = Mitra.type.h3, color = c.text) },
            text = { Text("The account balance will be adjusted back.", style = Mitra.type.body, color = c.muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onAction(TxEditorAction.Delete) }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun DuplicateBanner(text: String) {
    val c = Mitra.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radiusSm)).background(c.warningSoft).padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MitraIcons.Alert, contentDescription = null, tint = c.warning, modifier = Modifier.size(18.dp))
        Text(text, Modifier.weight(1f), style = Mitra.type.sm, color = c.text)
    }
}

@Composable
private fun NewCategoryRow(onAdd: (name: String, emoji: String) -> Unit) {
    val c = Mitra.colors
    var emoji by rememberSaveable { mutableStateOf("🎁") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radius)).background(c.surface2).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MitraTextField(emoji, { emoji = lastGraphemes(it) }, "Emoji", modifier = Modifier.width(72.dp))
            MitraTextField(
                name, { name = it }, "Name", modifier = Modifier.weight(1f), placeholder = "Category name",
                imeAction = ImeAction.Done, onImeAction = { if (name.isNotBlank()) onAdd(name, emoji) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New categories appear here and in Budgets. Pick an emoji and a name.", Modifier.weight(1f), style = Mitra.type.xs, color = c.muted)
            MitraButton("Add", onClick = { if (name.isNotBlank()) onAdd(name, emoji) }, enabled = name.isNotBlank())
        }
    }
}

/** The web keeps `value.slice(-2)` (one emoji); a surrogate pair is never split in half. */
private fun lastGraphemes(s: String): String {
    val tail = s.takeLast(2)
    return if (tail.length == 2 && tail[0].isLowSurrogate()) tail.drop(1) else tail
}

@Composable
private fun OptionPicker(
    options: List<TxOption>,
    selectedId: String?,
    placeholder: String,
    icon: ImageVector,
    onPick: (String) -> Unit,
) {
    val c = Mitra.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.id == selectedId }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(MitraDimens.radiusSm))
                .background(c.surface)
                .clickable(enabled = options.isNotEmpty(), role = Role.DropdownList, onClickLabel = "Choose") { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            Text(
                current?.label ?: placeholder, Modifier.weight(1f),
                style = Mitra.type.body.copy(fontWeight = if (current != null) FontWeight.Medium else FontWeight.Normal),
                color = if (current != null) c.text else c.faint, maxLines = 1,
            )
            Icon(MitraIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label, style = Mitra.type.body, color = if (o.id == selectedId) c.accent else c.text, maxLines = 1) },
                    onClick = { open = false; onPick(o.id) },
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
}
