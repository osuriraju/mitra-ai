package tech.beyondscale.mitra.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.StatCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.Ring
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import tech.beyondscale.mitra.feature.goals.chipTone
import tech.beyondscale.mitra.feature.goals.tone
import tech.beyondscale.mitra.feature.habits.HabitRow
import tech.beyondscale.mitra.feature.habits.HabitRowUi
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.tasks.TaskRow
@Composable
fun TodayRoute(
    onOpenTasks: () -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenMoney: () -> Unit = {},
    onAddExpense: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenHabits: () -> Unit = {},
    onOpenHabit: (String) -> Unit = {},
    onAddHabit: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenGoal: (String) -> Unit = {},
    vm: TodayViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    TodayScreen(
        state,
        onAction = { a ->
            when (a) {
                TodayAction.AddExpense -> onAddExpense()
                TodayAction.OpenBudgets -> onOpenBudgets()
                TodayAction.OpenHabits -> onOpenHabits()
                is TodayAction.OpenHabit -> onOpenHabit(a.id)
                TodayAction.AddHabit -> onAddHabit()
                TodayAction.OpenGoals -> onOpenGoals()
                is TodayAction.OpenGoal -> onOpenGoal(a.id)
                else -> vm.onAction(a)
            }
        },
        onOpenTasks = onOpenTasks,
        onOpenTask = onOpenTask,
        onOpenMoney = onOpenMoney,
    )
}

@Composable
fun TodayScreen(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenMoney: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        Column(Modifier.padding(horizontal = MitraDimens.pad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(state.dateLine)
            Text(state.greeting, style = Mitra.type.h1, color = Mitra.colors.text)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = MitraDimens.pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.stats, key = { it.label }) { stat ->
                StatCard(stat.label, stat.value, stat.sub, onClick = when (stat.target) {
                    StatTarget.Tasks -> onOpenTasks
                    StatTarget.Habits -> { { onAction(TodayAction.OpenHabits) } }
                    StatTarget.Money -> onOpenMoney
                    null -> null
                })
            }
        }
        state.sections.forEach { section ->
            when (section) {
                "priorities" -> if (state.showPriorities) {
                    SectionTitle("Now · priorities", "Plan", "Plan the day", onOpenTasks)
                    MitraCard(Modifier.padding(horizontal = MitraDimens.pad), contentPadding = PaddingValues(0.dp)) {
                        if (state.priorities.isEmpty()) {
                            EmptyState(
                                icon = MitraIcons.CheckSquare,
                                title = "Nothing urgent",
                                body = "No overdue or due-today tasks. Pull one from Upcoming or enjoy the calm.",
                                action = { MitraButton("Plan the day", onOpenTasks, kind = ButtonKind.Outline) },
                            )
                        } else {
                            state.priorities.forEach { row ->
                                TaskRow(row, onToggle = { onAction(TodayAction.Toggle(row.id)) }, onOpen = { onOpenTask(row.id) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.prioritiesFooter,
                                style = Mitra.type.sm.copy(fontWeight = FontWeight.Medium),
                                color = Mitra.colors.accent,
                                modifier = Modifier.heightIn(min = 48.dp).weight(1f)
                                    .clickable(role = Role.Button, onClickLabel = "Open today's tasks", onClick = onOpenTasks)
                                    .wrapContentHeight(Alignment.CenterVertically),
                            )
                            Text(state.prioritiesMeta, style = Mitra.type.xs, color = Mitra.colors.muted)
                        }
                    }
                }
                "habits" -> state.habits?.let { HabitsSection(it, onAction) }
                "money" -> state.money?.let { m ->
                    SectionTitle("Money", "Overview", "Open Money", onOpenMoney)
                    MoneyCard(m, onAction)
                }
                "goals" -> state.goals?.let { GoalsSection(it, onAction) }
            }
        }
    }
}

@Composable
private fun MoneyCard(m: MoneyCardUi, onAction: (TodayAction) -> Unit) {
    val c = Mitra.colors
    MitraCard(Modifier.padding(horizontal = MitraDimens.pad)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text("Safe to spend", style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(m.safe, style = Mitra.type.numLg, color = c.text)
                    if (m.perDay) Text(" / day", style = Mitra.type.sm, color = c.muted)
                }
                Text(m.daysLeft, style = Mitra.type.xs, color = c.faint)
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.End) {
                Text(m.remainingTitle, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                Text(m.remaining, style = Mitra.type.numLg, color = if (m.remainingDanger) c.danger else c.text)
                if (m.budgetSub.isNotEmpty()) Text(m.budgetSub, style = Mitra.type.xs, color = c.faint)
            }
        }
        m.next?.let { Text(it, style = Mitra.type.sm, color = c.text) }
        m.alert?.let { Text(it, style = Mitra.type.xs, color = if (m.alertDanger) c.danger else c.warning) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MitraButton("Add expense", { onAction(TodayAction.AddExpense) }, kind = ButtonKind.Soft, icon = MitraIcons.Plus)
            MitraButton("Budgets", { onAction(TodayAction.OpenBudgets) }, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String, actionLabel: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f))
        Text(
            action,
            style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold),
            color = Mitra.colors.accent,
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
                .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/** Web `TodayHabits`: due-today rows with the check button, or an empty state. */
@Composable
private fun HabitsSection(rows: List<HabitRowUi>, onAction: (TodayAction) -> Unit) {
    SectionTitle("Habits", "All", "Open Habits") { onAction(TodayAction.OpenHabits) }
    MitraCard(Modifier.padding(horizontal = MitraDimens.pad), contentPadding = PaddingValues(0.dp)) {
        if (rows.isEmpty()) {
            EmptyState(
                icon = MitraIcons.Repeat,
                title = "No habits today",
                body = "",
                action = { MitraButton("Add a habit", { onAction(TodayAction.AddHabit) }, kind = ButtonKind.Outline) },
            )
        } else {
            rows.forEach { row ->
                HabitRow(row, onCheck = { onAction(TodayAction.CheckHabit(row.id)) }, onOpen = { onAction(TodayAction.OpenHabit(row.id)) })
            }
        }
    }
}

/** Web `TodayGoals`: the top two active goals, not-on-track first. */
@Composable
private fun GoalsSection(goals: List<TodayGoalUi>, onAction: (TodayAction) -> Unit) {
    val c = Mitra.colors
    SectionTitle("Goals", "All", "Open Goals") { onAction(TodayAction.OpenGoals) }
    MitraCard(Modifier.padding(horizontal = MitraDimens.pad), contentPadding = PaddingValues(0.dp)) {
        if (goals.isEmpty()) {
            EmptyState(
                icon = MitraIcons.Target,
                title = "No active goals",
                body = "",
                action = { MitraButton("Set a goal", { onAction(TodayAction.OpenGoals) }, kind = ButtonKind.Outline) },
            )
        } else {
            goals.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp)
                        .clickable(role = Role.Button, onClickLabel = "Open goal") { onAction(TodayAction.OpenGoal(g.id)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Ring(g.pct, size = 40.dp, stroke = 4.dp, label = "${g.pct}%", tone = g.tone.tone())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(g.name, style = Mitra.type.body.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(g.next, style = Mitra.type.xs, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box { MitraChip(g.status, tone = g.statusTone.chipTone()) }
                }
            }
        }
    }
}
