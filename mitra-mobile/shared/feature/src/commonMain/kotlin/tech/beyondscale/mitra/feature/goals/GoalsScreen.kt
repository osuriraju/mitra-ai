package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.EventEffect

private val TABS = listOf("Active", "Review due", "Completed")

@Composable
fun GoalsRoute(
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onStartReview: () -> Unit,
    /** Extra top-bar actions after New goal (the shell passes its sync pill and avatar). */
    topBarActions: @Composable RowScope.() -> Unit = {},
    vm: GoalsViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    GoalsScreen(state, vm::onAction, onOpenGoal, onNewGoal, onStartReview, topBarActions)
}

/** Goals list (web `GoalsScreen`): Active / Review due / Completed (paused + completed) cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    state: GoalsUiState,
    onAction: (GoalsAction) -> Unit,
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onStartReview: () -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Goals") {
            IconButton(onClick = onNewGoal) { Icon(MitraIcons.Plus, contentDescription = "New goal", tint = c.accent) }
            topBarActions()
        }
        MitraSegmented(
            options = TABS,
            selectedIndex = state.tab,
            onSelect = { onAction(GoalsAction.Tab(it)) },
            modifier = Modifier.padding(horizontal = MitraDimens.pad),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad).padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.summary, style = Mitra.type.sm, color = c.muted, modifier = Modifier.weight(1f))
            if (state.showStartReview) {
                TextButton(onClick = onStartReview) { Text("Start review", style = Mitra.type.sm, color = c.accent) }
            }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(GoalsAction.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val empty = state.empty
                if (empty != null) {
                    item(key = "empty:${empty.name}") {
                        MitraCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                            EmptyState(
                                MitraIcons.Target,
                                empty.title,
                                empty.body,
                                action = if (empty.showNew) {
                                    { MitraButton("New goal", onClick = onNewGoal, icon = MitraIcons.Plus) }
                                } else null,
                            )
                        }
                    }
                } else {
                    items(state.cards, key = { it.id }) { card -> GoalCard(card, onClick = { onOpenGoal(card.id) }) }
                }
            }
        }
    }
}
