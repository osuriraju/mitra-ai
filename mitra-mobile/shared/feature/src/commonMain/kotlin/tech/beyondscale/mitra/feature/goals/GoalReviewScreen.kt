package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.Ring
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.common.MitraDatePickerDialog

@Composable
fun GoalReviewRoute(onDone: () -> Unit, onBack: () -> Unit, vm: GoalReviewViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction, onClose = onDone)
    GoalReviewScreen(state, vm::onAction, onBack)
}

/**
 * Goal review as a swipeable step pager (spec §9): Trajectory → one page per goal → Finish. A decision moves on to
 * the next page; swiping back lets the user change it.
 */
@Composable
fun GoalReviewScreen(state: GoalReviewUiState, onAction: (GoalReviewAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Goal review", onBack = onBack)
        if (!state.loaded) return@Column
        Text(state.sub, Modifier.padding(horizontal = MitraDimens.pad), style = Mitra.type.sm, color = c.muted)
        val pageCount = state.goals.size + 2
        val pager = rememberPagerState { pageCount }
        val scope = rememberCoroutineScope()
        val next: (Int) -> Unit = { page -> scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(pageCount - 1)) } }
        PageDots(pager, pageCount, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().imePadding(), key = { pageKey(state, it) }) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
            ) {
                when {
                    page == 0 -> TrajectoryPage(state, onStart = { next(0) }, onFinish = { onAction(GoalReviewAction.Finish) })
                    page <= state.goals.size -> GoalPage(
                        g = state.goals[page - 1],
                        adjusting = state.adjusting == state.goals[page - 1].id,
                        adjustTarget = state.adjustTarget,
                        adjustValid = state.adjustValid,
                        onAction = onAction,
                        onDecided = { next(page) },
                    )
                    else -> FinishPage(state, onFinish = { onAction(GoalReviewAction.Finish) })
                }
            }
        }
    }
}

private fun pageKey(state: GoalReviewUiState, page: Int): String = when {
    page == 0 -> "trajectory"
    page <= state.goals.size -> "goal:${state.goals[page - 1].id}"
    else -> "finish"
}

@Composable
private fun PageDots(pager: PagerState, count: Int, modifier: Modifier) {
    val c = Mitra.colors
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Step ${pager.currentPage + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val on = i == pager.currentPage
            Box(Modifier.size(if (on) 8.dp else 6.dp).background(if (on) c.accent else c.border, CircleShape))
        }
    }
}

@Composable
private fun TrajectoryPage(state: GoalReviewUiState, onStart: () -> Unit, onFinish: () -> Unit) {
    val c = Mitra.colors
    MitraCard {
        Eyebrow("Trajectory", Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TrajectoryStat(state.onTrack, "on track", c.success, Modifier.weight(1f))
            TrajectoryStat(state.behind, "behind", c.warning, Modifier.weight(1f))
            TrajectoryStat(state.slipping, "slipping", c.danger, Modifier.weight(1f))
        }
        Text("Compared with each goal's own target line since it started.", style = Mitra.type.xs, color = c.faint)
    }
    if (state.goals.isEmpty()) {
        MitraCard { EmptyState(MitraIcons.Target, "No active goals to review", "") }
        MitraButton("Finish review", onClick = onFinish, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg)
    } else {
        Text("Swipe through each goal and decide: keep it, adjust the target, or pause it.", style = Mitra.type.sm, color = c.muted)
        MitraButton("Start", onClick = onStart, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Soft)
    }
}

@Composable
private fun TrajectoryStat(value: Int, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$value $label" }) {
        Text(value.toString(), style = Mitra.type.h1, color = color)
        Text(label, style = Mitra.type.sm, color = Mitra.colors.muted)
    }
}

@Composable
private fun GoalPage(
    g: ReviewGoalUi,
    adjusting: Boolean,
    adjustTarget: String,
    adjustValid: Boolean,
    onAction: (GoalReviewAction) -> Unit,
    onDecided: () -> Unit,
) {
    val c = Mitra.colors
    var picking by rememberSaveable(g.id) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(adjusting) { if (adjusting && g.showTarget) focus.requestFocus() }
    MitraCard(Modifier.alpha(if (g.decision != null) 0.75f else 1f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ring(g.pct, size = 48.dp, stroke = 5.dp, label = g.ringLabel, tone = g.ringTone.toTone())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.name, Modifier.weight(1f).semantics { heading() }, style = Mitra.type.bodyBold, color = c.text)
                    g.decision?.let { MitraChip(it.label, tone = it.chipTone()) }
                }
                Text(g.line, style = Mitra.type.sm, color = c.muted)
            }
        }
        if (adjusting) {
            val save = {
                if (adjustValid) {
                    focusManager.clearFocus()
                    onAction(GoalReviewAction.SaveAdjust(g.id))
                    onDecided()
                }
            }
            if (g.showTarget) {
                MitraTextField(
                    value = adjustTarget,
                    onValueChange = { onAction(GoalReviewAction.AdjustTarget(it)) },
                    label = "New target (${g.targetSuffix})",
                    placeholder = g.targetPlaceholder,
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    onImeAction = save,
                    fieldModifier = Modifier.focusRequester(focus),
                    error = if (adjustValid) null else "Enter a target above 0",
                )
            }
            MitraButton(g.dueLabel, onClick = { picking = true }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = MitraIcons.Calendar)
            MitraButton("Save", onClick = save, modifier = Modifier.fillMaxWidth(), enabled = adjustValid)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MitraButton(
                    "Keep",
                    onClick = { onAction(GoalReviewAction.Decide(g.id, Decision.Keep)); onDecided() },
                    modifier = Modifier.decisionState(g.decision == Decision.Keep),
                    kind = if (g.decision == Decision.Keep) ButtonKind.Primary else ButtonKind.Soft,
                )
                MitraButton(
                    "Adjust target",
                    onClick = { onAction(GoalReviewAction.StartAdjust(g.id)) },
                    modifier = Modifier.decisionState(g.decision == Decision.Adjusted),
                    kind = ButtonKind.Outline,
                )
                MitraButton(
                    "Pause",
                    onClick = { onAction(GoalReviewAction.Decide(g.id, Decision.Pause)); onDecided() },
                    modifier = Modifier.decisionState(g.decision == Decision.Pause),
                    kind = ButtonKind.Ghost,
                )
            }
        }
    }
    MitraButton("Skip", onClick = onDecided, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    if (picking) {
        MitraDatePickerDialog(g.due, onPick = { onAction(GoalReviewAction.AdjustDue(g.id, it)) }, onDismiss = { picking = false })
    }
}

@Composable
private fun FinishPage(state: GoalReviewUiState, onFinish: () -> Unit) {
    val c = Mitra.colors
    MitraCard {
        Eyebrow("Decisions", Modifier.semantics { heading() })
        if (state.summary.isEmpty()) {
            Text("No active goals to review", style = Mitra.type.body, color = c.muted)
        }
        state.summary.forEachIndexed { i, (name, decision) ->
            if (i > 0) HorizontalDivider(color = c.border)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = Mitra.type.body, color = c.text)
                Text(decision, style = Mitra.type.sm, color = if (decision == "No decision") c.faint else c.muted, textAlign = TextAlign.End)
            }
        }
    }
    MitraButton("Finish review", onClick = onFinish, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg)
}

/** Spoken state of a decision button: selected + "Chosen" for the current decision. */
private fun Modifier.decisionState(chosen: Boolean): Modifier = semantics {
    selected = chosen
    stateDescription = if (chosen) "Chosen" else "Not chosen"
}

private fun StatusTone.toTone(): Tone = when (this) {
    StatusTone.Success -> Tone.Success
    StatusTone.Warning -> Tone.Warning
    StatusTone.Danger -> Tone.Danger
    StatusTone.Accent -> Tone.Accent
    StatusTone.Neutral -> Tone.Neutral
}

/** Web chip: Keep → success, Pause → neutral, Adjusted → accent. */
private fun Decision.chipTone(): ChipTone = when (this) {
    Decision.Keep -> ChipTone.Success
    Decision.Pause -> ChipTone.Neutral
    Decision.Adjusted -> ChipTone.Accent
}
