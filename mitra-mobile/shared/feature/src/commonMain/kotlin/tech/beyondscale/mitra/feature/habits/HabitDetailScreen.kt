package tech.beyondscale.mitra.feature.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.Heatmap
import tech.beyondscale.mitra.designsystem.components.LineChart
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraConfirmDialog
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.Ring
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun HabitDetailRoute(
    id: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onHistory: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
    vm: HabitDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction, onClose = onBack)
    HabitDetailScreen(state, vm::onAction, onBack, onEdit = { onEdit(id) }, onHistory = { onHistory(id) }, onOpenGoal)
}

@Composable
fun HabitDetailScreen(
    state: HabitDetailUiState,
    onAction: (HabitDetailAction) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onOpenGoal: (String) -> Unit,
) {
    val c = Mitra.colors
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val ready = state.loaded && !state.notFound && !state.closing

    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar(if (state.notFound) "Habit not found" else state.name, onBack = onBack) {
            if (ready) {
                IconButton(onClick = onEdit) { Icon(MitraIcons.Edit, contentDescription = "Edit habit", tint = c.text) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("View history", MitraIcons.Calendar) { menu = false; onHistory() }
                        MenuItem(if (state.archived) "Unarchive" else "Archive", MitraIcons.Archive) {
                            menu = false
                            onAction(HabitDetailAction.ToggleArchive)
                        }
                        MenuItem("Delete", MitraIcons.Trash, danger = true) { menu = false; confirmDelete = true }
                    }
                }
            }
        }
        if (!state.loaded || state.closing) return@Column
        if (state.notFound) {
            EmptyState(MitraIcons.Repeat, "This habit doesn't exist", "") {
                MitraButton("All habits", onClick = onBack, kind = ButtonKind.Outline)
            }
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(state.eyebrow, Modifier.weight(1f, fill = false))
                if (state.archived) MitraChip("Archived", icon = MitraIcons.Archive)
            }
            Summary(state, onAction)
            Consistency(state)
            state.trend?.let { t -> Trend(t) }
            SectionHeader("Supports goal")
            SupportsGoal(state, onEdit, onOpenGoal)
            SectionHeader("Recent")
            Recent(state, onAction, onHistory)
        }
    }

    if (confirmDelete) {
        MitraConfirmDialog(
            title = "Delete “${state.name}”?",
            body = "All logs will be removed. Archiving keeps history.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onAction(HabitDetailAction.Delete) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Summary(state: HabitDetailUiState, onAction: (HabitDetailAction) -> Unit) {
    MitraCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Ring(state.ringPct, size = 84.dp, stroke = 8.dp, label = "${state.ringPct}%", tone = state.ringTone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kpi("Current streak", state.streakValue, Modifier.weight(1f))
                    Kpi("Best", state.bestValue, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kpi("This month", state.monthValue, Modifier.weight(1f))
                    Kpi(state.lastLabel, state.lastValue, Modifier.weight(1f))
                }
            }
        }
        MitraButton(
            if (state.doneToday) "Done today ✓" else "Done today",
            onClick = { onAction(HabitDetailAction.DoneToday) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            kind = if (state.doneToday) ButtonKind.Soft else ButtonKind.Primary,
            icon = MitraIcons.Check,
        )
        if (state.measurable) {
            var text by rememberSaveable { mutableStateOf("") }
            val submit = {
                if (text.isNotEmpty()) {
                    onAction(HabitDetailAction.LogValue(text))
                    text = ""
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MitraTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                    label = "Log ${state.unit}".trimEnd(),
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    onImeAction = submit,
                    placeholder = state.target.toString(),
                )
                MitraButton("Log", onClick = submit, kind = ButtonKind.Outline, icon = MitraIcons.Timer, enabled = text.isNotEmpty())
            }
        }
    }
}

@Composable
private fun Consistency(state: HabitDetailUiState) {
    val c = Mitra.colors
    // One column per week, oldest on the left, so the captions read left → right (the web stacks weeks as rows).
    val cells = remember(state.heat) { heatByWeekColumns(state.heat) }
    MitraCard {
        Eyebrow("Consistency · last 12 weeks")
        Heatmap(cells, columns = 12, description = state.heatDescription)
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("12 weeks ago", style = Mitra.type.xs, color = c.muted)
            Text(state.heatCaption, style = Mitra.type.xs, color = c.muted)
            Text("This week", style = Mitra.type.xs, color = c.muted)
        }
    }
}

@Composable
private fun Trend(t: HabitTrendUi) {
    val c = Mitra.colors
    SectionHeader("Trend")
    MitraCard {
        LineChart(values = t.values, labels = t.labels, goal = t.goal, format = ::jsNum, modifier = Modifier.fillMaxWidth().height(130.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(t.legend, swatch = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.accent)) })
            LegendItem(t.targetLabel, swatch = { Box(Modifier.width(12.dp).height(2.dp).background(c.muted)) })
        }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(text, style = Mitra.type.xs, color = Mitra.colors.muted)
    }
}

@Composable
private fun SupportsGoal(state: HabitDetailUiState, onEdit: () -> Unit, onOpenGoal: (String) -> Unit) {
    val c = Mitra.colors
    val g = state.goal
    if (g == null) {
        MitraCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Not linked to a goal.", Modifier.weight(1f), style = Mitra.type.sm, color = c.muted)
                MitraButton("Link one", onClick = onEdit, kind = ButtonKind.Ghost)
            }
        }
        return
    }
    MitraCard(onClick = { onOpenGoal(g.id) }, contentPadding = PaddingValues(12.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Ring(g.pct, size = 40.dp, stroke = 4.dp, label = "${g.pct}%")
            Column(Modifier.weight(1f)) {
                Text(g.name, style = Mitra.type.bodyBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.evidence, style = Mitra.type.sm, color = c.muted)
            }
            Icon(MitraIcons.ChevronRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Recent(state: HabitDetailUiState, onAction: (HabitDetailAction) -> Unit, onHistory: () -> Unit) {
    val c = Mitra.colors
    MitraCard(contentPadding = PaddingValues(0.dp)) {
        state.recent.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = c.border)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, Modifier.width(96.dp), style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(r.status, Modifier.weight(1f), style = Mitra.type.sm, color = c.muted)
                r.chip?.let { label ->
                    MitraChip(label, tone = ChipTone.Outline, onClick = { onAction(HabitDetailAction.ToggleDay(r.date)) })
                }
            }
        }
        HorizontalDivider(color = c.border)
        Text(
            "Full history",
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onHistory).padding(horizontal = 12.dp, vertical = 14.dp),
            style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
        )
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text, maxLines = 1)
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Mitra.colors.danger else Mitra.colors.text
    DropdownMenuItem(
        text = { Text(text, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}
