package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.Donut
import tech.beyondscale.mitra.designsystem.components.DonutSegment
import tech.beyondscale.mitra.designsystem.components.EmojiBox
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.tasks.OverflowItem

@Composable
fun CategoriesRoute(onBack: () -> Unit, onOpenTransactions: (categoryId: String) -> Unit, vm: CategoriesViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    CategoriesScreen(state, vm::onAction, onBack, onOpenTransactions)
}

/** Web `shade(i)`: the accent fading into the surface for the first four, the track colour after that. */
@Composable
private fun sliceColor(shade: Int): Color =
    if (shade < 4) lerp(Mitra.colors.surface, Mitra.colors.accent, (100 - shade * 22) / 100f) else Mitra.colors.track

@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    onAction: (CategoriesAction) -> Unit,
    onBack: () -> Unit,
    onOpenTransactions: (String) -> Unit,
) {
    val c = Mitra.colors
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val expense = state.kind == CategoryKind.Expense
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Categories", onBack = onBack)
        if (!state.loaded) return@Column
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "kind") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MitraSegmented(
                        listOf("Expense", "Income"), if (expense) 0 else 1,
                        { onAction(CategoriesAction.SetKind(if (it == 0) CategoryKind.Expense else CategoryKind.Income)) },
                        Modifier.weight(1f),
                    )
                    Text(state.monthLabel, style = Mitra.type.sm, color = c.muted)
                }
            }
            item(key = "create-$expense") { CreateCard(expense, onAction) }
            item(key = "donut") { DonutCard(state) }
            items(state.rows, key = { it.id }) { row ->
                CategoryRow(
                    row, expense, editing == row.id,
                    onEdit = { editing = row.id },
                    onCancel = { editing = null },
                    onSave = { name, budget -> editing = null; onAction(CategoriesAction.Update(row.id, name, budget)) },
                    onTransactions = { onOpenTransactions(row.id) },
                    onDelete = { deleting = row.id },
                )
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this category?", style = Mitra.type.h3, color = c.text) },
            text = { Text("Existing transactions keep their history but show as uncategorised.", style = Mitra.type.body, color = c.muted) },
            confirmButton = { TextButton(onClick = { deleting = null; onAction(CategoriesAction.Delete(id)) }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun CreateCard(expense: Boolean, onAction: (CategoriesAction) -> Unit) {
    var emoji by rememberSaveable { mutableStateOf("🎁") }
    var name by rememberSaveable { mutableStateOf("") }
    var budget by rememberSaveable { mutableStateOf("") }
    val create = {
        if (name.isNotBlank()) {
            onAction(CategoriesAction.Create(name, emoji, budget))
            name = ""
            budget = ""
        }
    }
    MitraCard {
        Eyebrow("Create a category")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MitraTextField(emoji, { emoji = it.takeLast(2) }, "Emoji", Modifier.weight(0.3f))
            MitraTextField(
                name, { name = it }, "Category name", Modifier.weight(0.7f), placeholder = "Category name",
                imeAction = if (expense) ImeAction.Next else ImeAction.Done, onImeAction = { if (!expense) create() },
            )
        }
        if (expense) {
            MitraTextField(
                budget, { budget = it.filter(Char::isDigit) }, "Monthly budget (₹)", placeholder = "2000",
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, onImeAction = create,
            )
        }
        MitraButton("Create", onClick = create, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank())
    }
}

@Composable
private fun DonutCard(state: CategoriesUiState) {
    val c = Mitra.colors
    MitraCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.slices.isNotEmpty()) {
                Donut(state.slices.map { DonutSegment(it.paise, sliceColor(it.shade), it.name) }, size = 112.dp, stroke = 16.dp)
            } else {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) { Text("No data", style = Mitra.type.xs, color = c.muted) }
            }
            Column(Modifier.weight(1f)) {
                state.slices.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(sliceColor(s.shade)).clearAndSetSemantics { })
                        Text(s.name, Modifier.weight(1f).padding(start = 8.dp), style = Mitra.type.sm, color = c.text, maxLines = 1)
                        Text(s.value, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Text(" ${s.pct}%", style = Mitra.type.sm, color = c.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    row: CategoryRowUi,
    expense: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: (name: String, budget: String) -> Unit,
    onTransactions: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Mitra.colors
    MitraCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (editing) {
            var name by rememberSaveable(row.id) { mutableStateOf(row.name) }
            var budget by rememberSaveable(row.id) { mutableStateOf(row.budgetRupees) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmojiBox(row.emoji)
                MitraTextField(name, { name = it }, "Name", Modifier.weight(1f))
            }
            if (expense) {
                MitraTextField(
                    budget, { budget = it.filter(Char::isDigit) }, "Monthly budget (₹)", keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done, onImeAction = { onSave(name, budget) },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MitraButton("Save", onClick = { onSave(name, budget) }, modifier = Modifier.weight(1f))
                MitraButton("Cancel", onClick = onCancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
            }
        } else {
            var menu by rememberSaveable(row.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EmojiBox(row.emoji)
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = Mitra.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(row.subtitle, style = Mitra.type.xs, color = c.muted)
                }
                Text(row.value, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions for ${row.name}", tint = c.muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        OverflowItem("Transactions", MitraIcons.List) { menu = false; onTransactions() }
                        OverflowItem("Rename / budget", MitraIcons.Edit) { menu = false; onEdit() }
                        OverflowItem("Delete", MitraIcons.Trash, danger = true) { menu = false; onDelete() }
                    }
                }
            }
        }
    }
}
