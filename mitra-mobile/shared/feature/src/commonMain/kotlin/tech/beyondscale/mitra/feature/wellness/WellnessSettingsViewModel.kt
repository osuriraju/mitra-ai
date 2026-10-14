package tech.beyondscale.mitra.feature.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.domain.model.WellnessMetric
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.domain.wellness.hm
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

/* Wellness settings — port of WellnessSettingsScreen in mitra-frontend/src/screens/wellness.tsx. */

/** The editable target rows. */
enum class WellnessTarget { Sleep, Steps, Water, Height }

data class WellnessSettingsUiState(
    val loaded: Boolean = false,
    val settings: WellnessSettings = WellnessSettings(),
    /** "7h 30m", "8,000", "8 glasses", "170 cm" — row subtitles. */
    val sleepTarget: String = "",
    val stepsTarget: String = "",
    val waterTarget: String = "",
    val height: String = "",
)

sealed interface WellnessSettingsAction {
    data class Track(val metric: WellnessMetric, val on: Boolean) : WellnessSettingsAction
    /** Raw text from the edit dialog: "7:30" / "7.5" for sleep, digits for the rest. */
    data class SetTarget(val target: WellnessTarget, val text: String) : WellnessSettingsAction
    data class MorningReminder(val hhmm: String) : WellnessSettingsAction
    data class EveningReminder(val hhmm: String) : WellnessSettingsAction
    data class ShareAI(val on: Boolean) : WellnessSettingsAction
    data class ShowOnToday(val on: Boolean) : WellnessSettingsAction
    data object HealthIntegrations : WellnessSettingsAction
}

/**
 * [onShareAiChanged] mirrors the web, which also PATCHes the user setting `shareWellness` on this toggle (the AI
 * context is shared when either flag is on). Wire it to the settings repository once it can write that key.
 */
class WellnessSettingsViewModel(
    private val wellness: WellnessRepository,
    private val onShareAiChanged: suspend (Boolean) -> Unit = {},
) : ViewModel() {
    private val queue = EventQueue<Nothing>()
    val events = queue.events

    val state: StateFlow<WellnessSettingsUiState> = wellness.settings.map { s ->
        WellnessSettingsUiState(
            loaded = true,
            settings = s,
            sleepTarget = hm(s.sleepTarget),
            stepsTarget = localeIN(s.stepsTarget.toDouble()),
            waterTarget = "${s.waterTarget} glasses",
            height = "${s.height} cm",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WellnessSettingsUiState())

    fun onAction(a: WellnessSettingsAction) {
        when (a) {
            is WellnessSettingsAction.Track -> update(WellnessSettingsChange.Track(a.metric, a.on))
            is WellnessSettingsAction.SetTarget -> {
                val change = parseTarget(a.target, a.text)
                if (change == null) {
                    queue.send(UiEvent.Haptic(HapticKind.Reject))
                    queue.send(UiEvent.Snack(targetError(a.target)))
                } else {
                    update(change)
                }
            }
            is WellnessSettingsAction.MorningReminder -> update(WellnessSettingsChange.MorningReminder(a.hhmm))
            is WellnessSettingsAction.EveningReminder -> update(WellnessSettingsChange.EveningReminder(a.hhmm))
            is WellnessSettingsAction.ShareAI -> viewModelScope.launch {
                wellness.updateSettings(listOf(WellnessSettingsChange.ShareAI(a.on)))
                onShareAiChanged(a.on)
            }
            is WellnessSettingsAction.ShowOnToday -> update(WellnessSettingsChange.ShowOnToday(a.on))
            WellnessSettingsAction.HealthIntegrations -> queue.send(UiEvent.Snack("Health integrations arrive in a later phase"))
        }
    }

    private fun update(c: WellnessSettingsChange) {
        viewModelScope.launch { wellness.updateSettings(listOf(c)) }
    }
}

/** The dialog's starting text for a target. */
fun targetInput(s: WellnessSettings, t: WellnessTarget): String = when (t) {
    WellnessTarget.Sleep -> "${s.sleepTarget / 60}:${(s.sleepTarget % 60).toString().padStart(2, '0')}"
    WellnessTarget.Steps -> s.stepsTarget.toString()
    WellnessTarget.Water -> s.waterTarget.toString()
    WellnessTarget.Height -> s.height.toString()
}

/** Parses and range-checks against the server's limits; null when out of range or unreadable. */
internal fun parseTarget(t: WellnessTarget, text: String): WellnessSettingsChange? {
    val v = text.trim()
    return when (t) {
        WellnessTarget.Sleep -> {
            val mins = if (":" in v) {
                val p = v.split(":")
                val h = p[0].toIntOrNull() ?: return null
                val m = p.getOrNull(1)?.ifEmpty { "0" }?.toIntOrNull() ?: return null
                if (m !in 0..59) return null
                h * 60 + m
            } else {
                jsRound((v.toDoubleOrNull() ?: return null) * 60).toInt()
            }
            mins.takeIf { it in 60..900 }?.let { WellnessSettingsChange.SleepTarget(it) }
        }
        WellnessTarget.Steps -> v.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 100..100_000 }?.let { WellnessSettingsChange.StepsTarget(it) }
        WellnessTarget.Water -> v.toIntOrNull()?.takeIf { it in 1..30 }?.let { WellnessSettingsChange.WaterTarget(it) }
        WellnessTarget.Height -> v.toIntOrNull()?.takeIf { it in 50..272 }?.let { WellnessSettingsChange.Height(it) }
    }
}

private fun targetError(t: WellnessTarget): String = when (t) {
    WellnessTarget.Sleep -> "Sleep target must be between 1h and 15h"
    WellnessTarget.Steps -> "Steps target must be between 100 and 1,00,000"
    WellnessTarget.Water -> "Water target must be between 1 and 30 glasses"
    WellnessTarget.Height -> "Height must be between 50 and 272 cm"
}
