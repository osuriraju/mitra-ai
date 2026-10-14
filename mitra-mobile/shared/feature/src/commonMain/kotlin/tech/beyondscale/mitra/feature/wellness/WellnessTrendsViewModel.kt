package tech.beyondscale.mitra.feature.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.domain.wellness.WellnessTrends
import tech.beyondscale.mitra.domain.wellness.bmi
import tech.beyondscale.mitra.domain.wellness.hm
import tech.beyondscale.mitra.domain.wellness.moodAvgLabel
import tech.beyondscale.mitra.domain.wellness.sleepDeltaLabel
import tech.beyondscale.mitra.domain.wellness.sleepVsDoneInsight
import tech.beyondscale.mitra.domain.wellness.trendsBmi
import tech.beyondscale.mitra.domain.wellness.weightDeltaLabel
import tech.beyondscale.mitra.domain.wellness.wellnessTrends

/* Wellness trends — port of WellnessTrendsScreen in mitra-frontend/src/screens/wellness.tsx. */

val TREND_RANGES = listOf("Week", "Month", "3 months")
private val RANGE_DAYS = listOf(7, 30, 90)

/** A KPI tile: [up] true/false colours the sub-label, null leaves it neutral. */
data class TrendKpi(val label: String, val value: String, val sub: String, val up: Boolean? = null)

data class TrendsUiState(
    val loaded: Boolean = false,
    val rangeIndex: Int = 1,
    val days: Int = 30,
    val kpis: List<TrendKpi> = emptyList(),
    /** "Sleep · last 30 nights" */
    val sleepTitle: String = "",
    /** Null → [sleepEmpty] is shown instead of the chart. */
    val sleepValues: List<Double>? = null,
    val sleepLabels: List<String> = emptyList(),
    val sleepGoal: Double = 7.5,
    val sleepEmpty: String = "",
    val sleepLegend: String = "",
    val steps: List<Long> = emptyList(),
    val stepsLabels: List<String> = emptyList(),
    val stepsLegend: String = "",
    /** "Weight & BMI · month" */
    val weightTitle: String = "",
    /** Null → "Log weight in a few check-ins to see the trend." */
    val weights: List<Double>? = null,
    val weightLabels: List<String> = emptyList(),
    val weightLegend: String = "",
    val moodTitle: String = "",
    val moodHeat: List<Int> = emptyList(),
    val moodColumns: Int = 10,
    val moodFrom: String = "",
    /** Average "things done" after <6.5h, 6.5–7.5h and >7.5h nights (one decimal). */
    val sleepVsDone: List<Double> = emptyList(),
    val insight: String = "",
    val insightFoot: String = "",
)

class WellnessTrendsViewModel(
    wellness: WellnessRepository,
    tasks: TasksRepository,
    clock: AppClock,
) : ViewModel() {
    private val range = MutableStateFlow(1)

    val state: StateFlow<TrendsUiState> = combine(wellness.entries, wellness.settings, tasks.tasks, clock.dayFlow(), range) { all, ws, ts, today, r ->
        val done = ts.mapNotNull { it.completedAt }.groupingBy { it }.eachCount()
        buildTrends(all, ws, today, r) { done[it] ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsUiState())

    fun setRange(i: Int) { range.value = i.coerceIn(0, RANGE_DAYS.lastIndex) }
}

/** Web `lbl`: first, last and every [every]th point get a "4 Oct" label. */
private fun axisLabels(dates: List<String>, every: Int): List<String> =
    dates.mapIndexed { i, d -> if (i == 0 || i == dates.lastIndex || i % every == 0) fmtShort(d) else "" }

internal fun buildTrends(all: Map<String, WellnessEntry>, ws: WellnessSettings, today: String, rangeIndex: Int, doneOn: (String) -> Int): TrendsUiState {
    val days = RANGE_DAYS[rangeIndex]
    val t: WellnessTrends = wellnessTrends(all, today, days, doneOn)
    val w0 = t.firstWeight
    val w1 = t.lastWeight
    val b = trendsBmi(t, ws)
    val stepDates = t.dates.takeLast(t.steps.size)
    return TrendsUiState(
        loaded = true,
        rangeIndex = rangeIndex,
        days = days,
        kpis = listOf(
            TrendKpi("Avg sleep", hm(jsRound(t.avgSleepHours * 60).toInt()), sleepDeltaLabel(t), (t.avgSleepHours >= t.prevSleepHours).takeIf { t.prevSleepHours != 0.0 }),
            TrendKpi("Avg steps", localeIN(t.avgSteps.toDouble()), "target ${localeIN(ws.stepsTarget.toDouble())}", true.takeIf { t.avgSteps >= ws.stepsTarget }),
            TrendKpi("Mood avg", moodAvgLabel(t), "${t.checkins} check-ins"),
            TrendKpi("BMI", if (b != 0.0) jsNum(b) else "—", weightDeltaLabel(t), true.takeIf { w0 != null && w1 != null && w1 < w0 }),
        ),
        sleepTitle = "Sleep · last $days nights",
        sleepValues = t.sleepChart.takeIf { t.nightsLogged > 1 },
        sleepLabels = axisLabels(t.dates, (days + 3) / 4),
        sleepGoal = ws.sleepTarget / 60.0,
        sleepEmpty = "Log ${maxOf(0, 7 - t.nightsLogged)} more check-ins to see this chart.",
        sleepLegend = "Hours · target ${hm(ws.sleepTarget)}",
        steps = t.steps.map { it.toLong() },
        stepsLabels = stepDates.mapIndexed { i, d -> if (i % 2 == 0) dayOfMonth(d).toString() else "" },
        stepsLegend = "Steps · today highlighted · target ${localeIN(ws.stepsTarget.toDouble())}",
        weightTitle = "Weight & BMI · ${TREND_RANGES[rangeIndex].lowercase()}",
        weights = t.weights.takeIf { it.size > 1 },
        weightLabels = t.weights.indices.map { i -> if (i == 0) "Start" else if (i == t.weights.lastIndex) "Now" else "" },
        weightLegend = if (w0 != null && w1 != null) "Weight (kg) · BMI ${jsNum(bmi(w0, ws.height))} → ${jsNum(bmi(w1, ws.height))} · height ${ws.height} cm" else "",
        moodTitle = "Mood · last $days days",
        moodHeat = t.moodHeat,
        moodColumns = if (days >= 30) 10 else 7,
        moodFrom = fmtShort(t.dates.first()),
        sleepVsDone = t.sleepVsDone,
        insight = sleepVsDoneInsight(t),
        insightFoot = "$days days · ${t.nightsLogged} check-ins · correlation, not causation.",
    )
}
