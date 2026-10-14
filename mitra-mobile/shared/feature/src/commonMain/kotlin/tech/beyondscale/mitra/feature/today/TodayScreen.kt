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
                    StatTarget.Money -> onOpenMoney
                    null -> null
                })
            }
        }
        if (state.showPriorities) {
            Row(Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Now · priorities", Modifier.weight(1f))
                Text(
                    "Plan",
                    style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold),
                    color = Mitra.colors.accent,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Plan the day", onClick = onOpenTasks)
                        .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
                )
            }
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
        state.money?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Money", Modifier.weight(1f))
                Text(
                    "Overview",
                    style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold),
                    color = Mitra.colors.accent,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Open Money", onClick = onOpenMoney)
                        .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
                )
            }
            MoneyCard(m, onAction)
        }
        MitraCard(Modifier.padding(horizontal = MitraDimens.pad)) {
            EmptyState(
                icon = MitraIcons.Sun,
                title = "Your day starts here",
                body = "Habits and your check-in will appear here as each module arrives in the app.",
            )
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
