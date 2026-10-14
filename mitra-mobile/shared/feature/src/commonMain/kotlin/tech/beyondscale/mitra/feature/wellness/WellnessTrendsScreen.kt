package tech.beyondscale.mitra.feature.wellness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.core.toFixed
import tech.beyondscale.mitra.designsystem.components.Heatmap
import tech.beyondscale.mitra.designsystem.components.LineChart
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.ScrubBars
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun WellnessTrendsRoute(
    onBack: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: WellnessTrendsViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    WellnessTrendsScreen(state, vm::setRange, onBack, onOpenSleep, onOpenSettings)
}

@Composable
fun WellnessTrendsScreen(
    state: TrendsUiState,
    onRange: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Trends", onBack = onBack) {
            IconButton(onClick = onOpenSettings) { Icon(MitraIcons.Settings, contentDescription = "Wellness settings", tint = c.muted) }
        }
        if (!state.loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            MitraSegmented(TREND_RANGES, state.rangeIndex, onRange)
            state.kpis.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { k -> MitraCard(Modifier.weight(1f)) { Kpi(k.label, k.value, k.sub, k.up) } }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(state.sleepTitle, Modifier.weight(1f))
                TextButton(onClick = onOpenSleep) { Text("Detail", color = c.accent) }
            }
            MitraCard {
                val values = state.sleepValues
                if (values != null) {
                    LineChart(values, state.sleepLabels, goal = state.sleepGoal, format = { "${toFixed(it, 1)}h" }, modifier = Modifier.height(140.dp))
                    Text(state.sleepLegend, style = Mitra.type.xs, color = c.muted)
                } else {
                    Empty(state.sleepEmpty)
                }
            }

            SectionHeader("Steps · last 2 weeks")
            MitraCard {
                ScrubBars(
                    state.steps, state.stepsLabels, highlight = state.steps.lastIndex,
                    format = { localeIN(it.toDouble()) }, modifier = Modifier.height(140.dp),
                )
                Text(state.stepsLegend, style = Mitra.type.xs, color = c.muted)
            }

            SectionHeader(state.weightTitle)
            MitraCard {
                val weights = state.weights
                if (weights != null) {
                    LineChart(weights, state.weightLabels, format = { "${jsNum(it)} kg" }, modifier = Modifier.height(130.dp))
                    Text(state.weightLegend, style = Mitra.type.xs, color = c.muted)
                } else {
                    Empty("Log weight in a few check-ins to see the trend.")
                }
            }

            SectionHeader(state.moodTitle)
            MitraCard {
                Heatmap(state.moodHeat, columns = state.moodColumns, description = "Mood heatmap, darker is better")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.moodFrom, style = Mitra.type.xs, color = c.muted)
                    Text("Darker = better mood", style = Mitra.type.xs, color = c.muted)
                    Text("Today", style = Mitra.type.xs, color = c.muted)
                }
            }

            SectionHeader("Sleep vs productivity")
            MitraCard {
                // Tenths so one decimal survives ScrubBars' Long values.
                ScrubBars(
                    state.sleepVsDone.map { jsRound(it * 10) }, listOf("< 6.5h", "6.5–7.5h", "> 7.5h"),
                    format = { jsNum(it / 10.0) }, modifier = Modifier.height(120.dp),
                )
                Text(state.insight, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(state.insightFoot, style = Mitra.type.xs, color = c.faint)
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 20.dp), style = Mitra.type.sm, color = Mitra.colors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
