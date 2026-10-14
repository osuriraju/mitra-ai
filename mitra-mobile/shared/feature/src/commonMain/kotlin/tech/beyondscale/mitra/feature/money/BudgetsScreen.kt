package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmojiBox
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.money.BudgetTone
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun BudgetsRoute(onBack: () -> Unit, vm: BudgetsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    BudgetsScreen(state, vm::onAction, onBack)
}

@Composable
fun BudgetsScreen(state: BudgetsUiState, onAction: (BudgetsAction) -> Unit, onBack: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Budgets", onBack = onBack) {
            MitraButton("New budget", onClick = { adding = true }, icon = MitraIcons.Plus, kind = ButtonKind.Soft)
        }
        if (!state.loaded) return@Column
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "header") { Header(state) }
            items(state.cards, key = { it.id }) { BudgetCard(it, onAction) }
        }
    }
    if (adding) AddBudgetSheet(state.unbudgeted, onAdd = { id, rupees -> onAction(BudgetsAction.Add(id, rupees)) }, onDismiss = { adding = false })
}

@Composable
private fun Kpi(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = Mitra.type.xs, color = c.muted)
        Text(value, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        if (sub != null) Text(sub, style = Mitra.type.xs, color = c.muted)
    }
}

@Composable
private fun Header(state: BudgetsUiState) {
    val c = Mitra.colors
    MitraCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Kpi("Total budget", state.total, modifier = Modifier.weight(1f))
            Kpi("Spent", state.spent, modifier = Modifier.weight(1f))
            Kpi("Left", state.left, state.leftSub, modifier = Modifier.weight(1f))
        }
        MitraProgressBar(state.barFraction, color = if (state.barOver) c.danger else c.accent)
        Text(state.safeLine, style = Mitra.type.xs, color = c.muted)
    }
}

@Composable
private fun BudgetCard(card: BudgetCardUi, onAction: (BudgetsAction) -> Unit) {
    val c = Mitra.colors
    var editing by rememberSaveable(card.id) { mutableStateOf(false) }
    var value by rememberSaveable(card.id) { mutableStateOf("") }
    val barColor = when (card.tone) { BudgetTone.Danger -> c.danger; BudgetTone.Warning -> c.warning; BudgetTone.Normal -> c.accent }
    MitraCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EmojiBox(card.emoji)
            Column(Modifier.weight(1f)) {
                Text(card.name, style = Mitra.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(card.resets, style = Mitra.type.xs, color = c.muted)
            }
            MitraChip(
                card.chip,
                tone = when (card.tone) { BudgetTone.Danger -> ChipTone.Danger; BudgetTone.Warning -> ChipTone.Warning; BudgetTone.Normal -> ChipTone.Accent },
                icon = if (card.tone == BudgetTone.Danger) MitraIcons.Alert else null,
            )
        }
        MitraProgressBar(
            card.fraction, color = barColor, height = 10.dp,
            modifier = Modifier.semantics { contentDescription = "${card.name}: ${card.amounts}" },
        )
        if (editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MitraTextField(
                    value = value, onValueChange = { value = it.filter(Char::isDigit) }, label = "Monthly limit (₹)",
                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                    onImeAction = { editing = false; onAction(BudgetsAction.Save(card.id, value)) },
                    modifier = Modifier.weight(1f),
                )
                MitraButton("Save", onClick = { editing = false; onAction(BudgetsAction.Save(card.id, value)) })
                MitraButton("Remove", onClick = { editing = false; onAction(BudgetsAction.Remove(card.id)) }, kind = ButtonKind.Ghost)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(card.amounts, Modifier.weight(1f), style = Mitra.type.sm, color = c.text)
                Text(card.leftText, style = Mitra.type.sm, color = c.muted)
                MitraButton(
                    "Edit", onClick = { value = card.budgetRupees; editing = true }, kind = ButtonKind.Ghost,
                    modifier = Modifier.semantics { contentDescription = "Edit ${card.name} budget" },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBudgetSheet(options: List<CategoryOption>, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = Mitra.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var value by rememberSaveable { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val ready = chosen != null && rupeesToPaise(value) != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("New budget", style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
                Column(Modifier.selectableGroup()) {
                    options.forEach { o ->
                        val on = o.id == chosen
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = on, role = Role.RadioButton, onClick = { chosen = o.id })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            EmojiBox(o.emoji)
                            Text(o.name, Modifier.weight(1f), style = Mitra.type.body, color = if (on) c.accent else c.text)
                            if (on) androidx.compose.material3.Icon(MitraIcons.Check, contentDescription = "Selected", tint = c.accent)
                        }
                    }
                }
                if (options.isEmpty()) {
                    Text("Every category has a budget — create a new category first.", style = Mitra.type.sm, color = c.muted)
                } else {
                    MitraTextField(
                        value = value, onValueChange = { value = it.filter(Char::isDigit) }, label = "Monthly limit (₹)",
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, placeholder = "2000",
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MitraButton("Cancel", onClick = close, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
                MitraButton(
                    "Set budget", enabled = ready && !done, modifier = Modifier.weight(1f),
                    onClick = { val id = chosen; if (id != null && !done) { done = true; onAdd(id, value); close() } },
                )
            }
        }
    }
}
