package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SwipeRow
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun TransactionsRoute(
    initialCategoryId: String?,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    vm: TransactionsViewModel = koinViewModel(),
) {
    LaunchedEffect(vm, initialCategoryId) { vm.start(initialCategoryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    TransactionsScreen(state, vm::onAction, onBack, onEdit, onAdd)
}

private val KIND_OPTIONS = listOf<TxKind?>(null, TxKind.Expense, TxKind.Income, TxKind.Transfer)

/** Port of the web `TransactionsScreen`: filters, search, summary, sort, grouped rows (tap edits, swipe left deletes). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onAction: (TransactionsAction) -> Unit,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Transactions", onBack = onBack) {
            IconButton(onClick = onAdd) { Icon(MitraIcons.Plus, contentDescription = "Add transaction", tint = c.accent) }
        }
        if (!state.loaded) return@Column
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(TransactionsAction.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
            ) {
                item(key = "filters") { Filters(state, onAction) }
                item(key = "summary") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.summary, Modifier.weight(1f), style = Mitra.type.xs, color = c.muted)
                        TextButton(onClick = { onAction(TransactionsAction.ToggleSort) }) {
                            Text(state.sortLabel, style = Mitra.type.xs, color = c.accent)
                        }
                    }
                }
                if (state.empty) {
                    item(key = "empty") {
                        MitraCard {
                            EmptyState(
                                MitraIcons.Wallet,
                                "No transactions match these filters",
                                "Try a different month or clear the filters.",
                                action = { MitraButton("Clear filters", onClick = { onAction(TransactionsAction.ClearFilters) }, kind = ButtonKind.Outline) },
                            )
                        }
                    }
                }
                state.groups.forEach { g ->
                    item(key = "h:${g.title}") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.title, Modifier.weight(1f).semantics { heading() }, style = Mitra.type.h3, color = c.text)
                            Text(g.net, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                        }
                    }
                    items(g.rows, key = { "r:${it.id}" }) { row ->
                        SwipeRow(
                            onSwipeRight = null,
                            onSwipeLeft = { onAction(TransactionsAction.Delete(row.id)) },
                            rightLabel = "",
                            rightIcon = MitraIcons.Check,
                            leftLabel = "Delete",
                            leftIcon = MitraIcons.Trash,
                        ) {
                            MitraCard(contentPadding = PaddingValues(0.dp)) {
                                TxRow(row, onClick = { onEdit(row.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(state: TransactionsUiState, onAction: (TransactionsAction) -> Unit) {
    val c = Mitra.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FilterChip(state.monthLabel, MitraIcons.Calendar, selected = state.month != null, state.monthOptions) { onAction(TransactionsAction.Month(it)) }
            FilterChip(state.categoryLabel, null, selected = state.categoryId != null, state.categoryOptions) { onAction(TransactionsAction.Category(it)) }
            FilterChip(state.accountLabel, MitraIcons.Wallet, selected = state.accountId != null, state.accountOptions) { onAction(TransactionsAction.Account(it)) }
            FilterChip(
                state.kindLabel, null, selected = state.kind != null,
                KIND_OPTIONS.map { TxFilterOption(it?.wire, it?.let(::kindName) ?: "All types") },
            ) { wire -> onAction(TransactionsAction.Kind(TxKind.entries.firstOrNull { it.wire == wire })) }
            if (state.showClear) {
                MitraChip("Clear", tone = ChipTone.Outline, icon = MitraIcons.X, onClick = {
                    // The web's "Clear" chip keeps the month; the empty state's "Clear filters" also resets it.
                    onAction(TransactionsAction.Category(null))
                    onAction(TransactionsAction.Account(null))
                    onAction(TransactionsAction.Kind(null))
                    onAction(TransactionsAction.Query(""))
                })
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(TransactionsAction.Query(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search merchant or note", style = Mitra.type.body, color = c.faint) },
            leadingIcon = { Icon(MitraIcons.Search, contentDescription = null, tint = c.muted) },
            trailingIcon = if (state.query.isEmpty()) null else {
                { IconButton(onClick = { onAction(TransactionsAction.Query("")) }) { Icon(MitraIcons.X, contentDescription = "Clear search", tint = c.muted) } }
            },
            textStyle = Mitra.type.body.copy(color = c.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(MitraDimens.radiusSm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.border,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, cursorColor = c.accent,
            ),
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    options: List<TxFilterOption>,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // The "▾" is visual only: TalkBack reads the label (the chip's role and click say it opens a menu).
        MitraChip(
            "$label ▾", Modifier.semantics { contentDescription = label },
            tone = ChipTone.Outline, icon = icon, selected = selected, onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label, style = Mitra.type.body, color = if (o.label == label) Mitra.colors.accent else Mitra.colors.text, maxLines = 1) },
                    onClick = { open = false; onPick(o.id) },
                )
            }
        }
    }
}
