package tech.beyondscale.mitra.feature.habits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.components.WeekDots
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun HabitsRoute(
    onOpenHabit: (String) -> Unit,
    onNewHabit: () -> Unit,
    /** Extra top-bar actions after ＋ (the shell passes its sync pill and avatar). */
    topBarActions: @Composable RowScope.() -> Unit = {},
    vm: HabitsViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    HabitsScreen(state, vm::onAction, onOpenHabit, onNewHabit, topBarActions)
}

/** Port of the web `HabitsScreen`: stats, Today / Week, time-of-day groups with check-off and swipe, "This week". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitsScreen(
    state: HabitsUiState,
    onAction: (HabitsAction) -> Unit,
    onOpenHabit: (String) -> Unit,
    onNewHabit: () -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Habits") {
            IconButton(onClick = onNewHabit) { Icon(MitraIcons.Plus, contentDescription = "New habit", tint = c.accent) }
            topBarActions()
        }
        if (!state.loaded) return@Column
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(HabitsAction.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
            ) {
                item(key = "stats") { Stats(state) }
                if (state.empty) {
                    item(key = "empty") {
                        MitraCard {
                            EmptyState(
                                MitraIcons.Repeat,
                                "No habits yet",
                                "Start with one small daily habit — Mitra keeps streaks gentle.",
                                action = { MitraButton("New habit", onClick = onNewHabit, icon = MitraIcons.Plus) },
                            )
                        }
                    }
                    return@LazyColumn
                }
                item(key = "tabs") {
                    MitraSegmented(listOf("Today", "Week"), selectedIndex = state.tab, onSelect = { onAction(HabitsAction.Tab(it)) })
                }
                groups(state, onAction, onOpenHabit)
                if (state.nothingToday) {
                    item(key = "nothing") {
                        MitraCard { EmptyState(MitraIcons.Sun, "Nothing scheduled today", "Switch to Week to see all habits.") }
                    }
                }
                item(key = "week") { WeekCard(state, onOpenHabit) }
            }
        }
    }
}

private fun LazyListScope.groups(state: HabitsUiState, onAction: (HabitsAction) -> Unit, onOpenHabit: (String) -> Unit) {
    state.groups.forEach { g ->
        item(key = "h:${g.title}") { SectionHeader(g.title) }
        // One swipeable card per habit: a swipe slides the whole row, as on the Tasks and Transactions lists.
        items(g.rows, key = { "r:${it.id}" }) { row ->
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                SwipeHabitRow(
                    row,
                    onCheck = { onAction(HabitsAction.Check(row.id)) },
                    onComplete = { onAction(HabitsAction.Complete(row.id)) },
                    onArchive = { onAction(HabitsAction.Archive(row.id)) },
                    onOpen = { onOpenHabit(row.id) },
                )
            }
        }
    }
}

/** Web `.stat-inline`: done today · this week · longest active streak. */
@Composable
private fun Stats(state: HabitsUiState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(state.doneToday, "done today", Modifier.weight(1f))
        Stat(state.weekRate, "this week", Modifier.weight(1f))
        Stat(state.longestStreak, "longest active streak", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = Mitra.colors
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = Mitra.type.numLg, color = c.text, maxLines = 1)
        Text(label, style = Mitra.type.xs, color = c.muted, maxLines = 2)
    }
}

@Composable
private fun WeekCard(state: HabitsUiState, onOpenHabit: (String) -> Unit) {
    val c = Mitra.colors
    Column(verticalArrangement = Arrangement.spacedBy(MitraDimens.gap)) {
        SectionHeader("This week")
        MitraCard(contentPadding = PaddingValues(horizontal = MitraDimens.pad, vertical = 6.dp)) {
            state.week.forEachIndexed { i, w ->
                if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "Open habit") { onOpenHabit(w.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        w.name, Modifier.weight(1f),
                        style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    WeekDots(w.dots, state.todayIndex)
                    Text(w.rate, Modifier.width(40.dp), style = Mitra.type.xs, color = c.muted, textAlign = TextAlign.End)
                }
            }
            Text("Mon → Sun · today outlined", Modifier.padding(top = 6.dp, bottom = 4.dp), style = Mitra.type.xs, color = c.faint)
        }
    }
}
