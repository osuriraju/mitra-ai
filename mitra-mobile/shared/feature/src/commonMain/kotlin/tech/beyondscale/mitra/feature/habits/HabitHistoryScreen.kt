package tech.beyondscale.mitra.feature.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.CalendarDayStyle
import tech.beyondscale.mitra.designsystem.components.CalendarMonth
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.MonthSwitcher
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

private val NO_STYLE = CalendarDayStyle(hit = false, miss = false, today = false, future = true, partial = false)

@Composable
fun HabitHistoryRoute(id: String, onBack: () -> Unit, vm: HabitHistoryViewModel = koinViewModel { parametersOf(id) }) {
    val state by vm.state.collectAsStateWithLifecycle()
    HabitHistoryScreen(state, vm::onAction, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitHistoryScreen(state: HabitHistoryUiState, onAction: (HabitHistoryAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar(if (state.notFound) "Habit not found" else state.title, onBack = onBack)
        if (!state.loaded) return@Column
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
            // Unbounded both ways, as on the web.
            MonthSwitcher(state.monthTitle, canNext = true, onPrev = { onAction(HabitHistoryAction.Prev) }, onNext = { onAction(HabitHistoryAction.Next) })
            MitraCard {
                CalendarMonth(
                    cells = state.cells,
                    decorate = { cell -> cell.date?.let(state.styles::get) ?: NO_STYLE },
                    onTap = { onAction(HabitHistoryAction.Tap(it)) },
                )
                FlowRow(
                    Modifier.padding(top = 4.dp).clearAndSetSemantics { },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Legend("Done") { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.successSoft)) }
                    Legend("Missed / rest") { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2)) }
                    Legend("Today") { Box(Modifier.size(10.dp).border(2.dp, c.accent, RoundedCornerShape(3.dp))) }
                }
                Text("Tap any past day to correct it.", style = Mitra.type.xs, color = c.faint)
            }
            MitraCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kpi("Completion", state.completion, state.completionMonth, Modifier.weight(1f))
                    Kpi("Best month", state.bestMonth, null, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kpi("Total", state.total, null, Modifier.weight(1f))
                    Kpi(state.lastLabel, state.lastValue, null, Modifier.weight(1f))
                }
            }
            if (state.log.isNotEmpty()) {
                SectionHeader("Log")
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    state.log.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = c.border)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.date, Modifier.width(72.dp), style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            Text(r.value, Modifier.weight(1f), style = Mitra.type.num, color = c.text)
                            Text(r.status, style = Mitra.type.xs, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(text, style = Mitra.type.xs, color = Mitra.colors.muted)
    }
}

@Composable
private fun Kpi(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted, maxLines = 1)
        Text(value, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text, maxLines = 1)
        if (sub != null) Text(sub, style = Mitra.type.xs, color = Mitra.colors.faint)
    }
}
