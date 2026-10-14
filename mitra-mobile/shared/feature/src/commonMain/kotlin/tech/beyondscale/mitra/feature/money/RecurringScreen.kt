package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmojiBox
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.tasks.OverflowItem

@Composable
fun RecurringRoute(onBack: () -> Unit, vm: RecurringViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    RecurringScreen(state, vm::onAction, onBack)
}

/** Recurring bills, income and transfers with "Mark paid" (web `RecurringScreen`, minus the placeholder "Mitra noticed" banner). */
@Composable
fun RecurringScreen(state: RecurringUiState, onAction: (RecurringAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val tabs = RecurringTab.entries

    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Recurring", onBack = onBack) {
            IconButton(onClick = { adding = true }) { Icon(MitraIcons.Plus, contentDescription = "Add recurring item", tint = c.accent) }
        }
        if (!state.loaded) return@Column
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = MitraDimens.pad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "tabs") {
                MitraSegmented(tabs.map { it.label }, state.tab.ordinal, { onAction(RecurringAction.SelectTab(tabs[it])) })
            }
            item(key = "kpis") {
                MitraCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RecSubKpi("This month", state.monthTotal, state.monthSub, Modifier.weight(1f))
                        RecSubKpi("Next 7 days", state.next7, state.next7Sub, Modifier.weight(1f))
                    }
                }
            }
            if (state.tab == RecurringTab.Bills && state.dueSoon.isNotEmpty()) {
                item(key = "dueHeader") { SectionHeader("Due soon") }
                item(key = "due") {
                    MitraCard(contentPadding = PaddingValues(0.dp)) {
                        state.dueSoon.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = c.border)
                            DueRow(r, onMarkPaid = { onAction(RecurringAction.MarkPaid(r.id)) })
                        }
                    }
                }
            }
            item(key = "allHeader") { SectionHeader("All ${state.tab.label.lowercase()}") }
            item(key = "all") {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    if (state.all.isEmpty()) {
                        EmptyState(MitraIcons.Repeat, "No recurring ${state.tab.label.lowercase()} yet", "") {
                            MitraButton("Add one", onClick = { adding = true }, icon = MitraIcons.Plus)
                        }
                    } else {
                        state.all.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = c.border)
                            AllRow(
                                r,
                                onToggleReminder = { onAction(RecurringAction.SetReminder(r.id, !r.reminder)) },
                                onDelete = { deleteId = r.id },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddRecurringSheet(
            income = state.tab == RecurringTab.Income,
            onSave = { name, emoji, paise, day -> onAction(RecurringAction.Add(name, emoji, paise, day)) },
            onDismiss = { adding = false },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Remove this recurring item?", style = Mitra.type.h3, color = c.text) },
            text = { Text("Past transactions are kept.", style = Mitra.type.body, color = c.muted) },
            confirmButton = {
                TextButton(onClick = { deleteId = null; onAction(RecurringAction.Delete(id)) }) { Text("Remove", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun DueRow(r: RecurringRowUi, onMarkPaid: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EmojiBox(r.emoji)
            Column(Modifier.weight(1f)) {
                Text(r.name, style = Mitra.type.bodyBold, color = c.text)
                Text(r.sub, style = Mitra.type.sm, color = c.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(r.amount, style = Mitra.type.num, color = c.text)
                Text(r.right, style = Mitra.type.xs, color = c.faint)
            }
        }
        if (r.paidLabel != null) {
            MitraChip(r.paidLabel, tone = ChipTone.Accent, icon = MitraIcons.Check)
        } else {
            MitraButton(
                "Mark paid", onClick = onMarkPaid, kind = ButtonKind.Soft, size = ButtonSize.Md,
                loading = r.busy, enabled = !r.busy,
            )
        }
    }
}

@Composable
private fun AllRow(r: RecurringRowUi, onToggleReminder: () -> Unit, onDelete: () -> Unit) {
    val c = Mitra.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBox(r.emoji)
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Mitra.type.bodyBold, color = c.text)
            Text(r.sub, style = Mitra.type.sm, color = c.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(r.amount, style = Mitra.type.num, color = c.text)
            if (r.right.isNotEmpty()) Text(r.right, style = Mitra.type.xs, color = c.faint)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions for ${r.name}", tint = c.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                OverflowItem(if (r.reminder) "Turn reminder off" else "Turn reminder on", MitraIcons.Bell) { menu = false; onToggleReminder() }
                OverflowItem("Delete", MitraIcons.Trash, danger = true) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
internal fun RecSubKpi(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Column(modifier) {
        Text(label, style = Mitra.type.xs, color = c.muted)
        Text(value, style = Mitra.type.numLg, color = c.text)
        if (!sub.isNullOrEmpty()) Text(sub, style = Mitra.type.xs, color = c.faint)
    }
}

/** Bottom-sheet frame shared by the recurring and subscription add forms; [onSave] runs at most once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecSubSheet(
    title: String,
    onSave: () -> Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Mitra.colors.bg) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, style = Mitra.type.h2, color = Mitra.colors.text, modifier = Modifier.semantics { heading() })
                content()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MitraButton("Cancel", onClick = close, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
                MitraButton("Add", onClick = { if (!saved && onSave()) { saved = true; close() } }, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun digitsOnly(s: String, max: Int = 9): String = s.filter { it in '0'..'9' }.take(max)

@Composable
private fun AddRecurringSheet(income: Boolean, onSave: (String, String, Long, Int) -> Unit, onDismiss: () -> Unit) {
    var emoji by rememberSaveable { mutableStateOf("💡") }
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    RecSubSheet(
        title = "Add recurring",
        onSave = {
            val rupees = amount.toLongOrNull() ?: 0L
            if (name.isBlank() || rupees <= 0) {
                error = if (name.isBlank()) "Enter a name" else "Enter an amount"
                false
            } else {
                onSave(name.trim(), emoji.ifBlank { "💡" }, rupees * 100, (day.toIntOrNull() ?: 1).coerceIn(1, 28))
                true
            }
        },
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            MitraTextField(emoji, { emoji = it.takeLast(2) }, "Icon", Modifier.weight(0.35f))
            MitraTextField(
                name, { name = it; error = null }, "Name", Modifier.weight(1f), error = error?.takeIf { name.isBlank() },
                placeholder = if (income) "e.g. Salary" else "e.g. Electricity",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            MitraTextField(
                amount, { amount = digitsOnly(it); error = null }, "Amount (₹)", Modifier.weight(1f),
                error = error?.takeIf { name.isNotBlank() }, keyboardType = KeyboardType.Number, placeholder = "Amount",
            )
            MitraTextField(
                day, { day = digitsOnly(it, 2) }, "Day of month", Modifier.weight(1f),
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, placeholder = "1–28",
            )
        }
        Text("Day is clamped to 1–28.", style = Mitra.type.xs, color = Mitra.colors.faint, textAlign = TextAlign.Start)
    }
}
