package tech.beyondscale.mitra.feature.wellness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.WellnessMetric
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.common.MitraTimePickerDialog

@Composable
fun WellnessSettingsRoute(onBack: () -> Unit, vm: WellnessSettingsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, {})
    WellnessSettingsScreen(state, vm::onAction, onBack)
}

private val TRACK_ROWS = listOf(
    Triple(WellnessMetric.Mood, "Mood", "Five-point scale, also on Today"),
    Triple(WellnessMetric.Sleep, "Sleep", "Duration and bed/wake times"),
    Triple(WellnessMetric.Steps, "Steps", "Manual entry now, wearable import later"),
    Triple(WellnessMetric.Water, "Water", "Glasses per day"),
    Triple(WellnessMetric.Bmi, "Weight & height → BMI", "BMI shown on Today and Trends"),
)

@Composable
fun WellnessSettingsScreen(state: WellnessSettingsUiState, onAction: (WellnessSettingsAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    val s = state.settings
    var editing by remember { mutableStateOf<WellnessTarget?>(null) }
    var reminder by remember { mutableStateOf<Boolean?>(null) } // true = morning
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Wellness settings", onBack = onBack)
        if (!state.loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            SectionHeader("What to track")
            MitraCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                TRACK_ROWS.forEachIndexed { i, (m, t, sub) ->
                    if (i > 0) HorizontalDivider(color = c.border)
                    MitraSwitchRow(t, s.track[m], { onAction(WellnessSettingsAction.Track(m, it)) }, sub)
                }
            }
            SectionHeader("Targets")
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                StatRow(MitraIcons.Moon, "Sleep target", null, state.sleepTarget, onClick = { editing = WellnessTarget.Sleep })
                HorizontalDivider(color = c.border)
                StatRow(MitraIcons.Activity, "Steps target", null, state.stepsTarget, onClick = { editing = WellnessTarget.Steps })
                HorizontalDivider(color = c.border)
                StatRow(MitraIcons.Droplet, "Water target", null, state.waterTarget, onClick = { editing = WellnessTarget.Water })
                HorizontalDivider(color = c.border)
                StatRow(MitraIcons.Scale, "Height", null, state.height, onClick = { editing = WellnessTarget.Height })
            }
            SectionHeader("Reminders")
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                StatRow(MitraIcons.Bell, "Morning check-in", null, fmtTime(s.morningReminder), onClick = { reminder = true })
                HorizontalDivider(color = c.border)
                StatRow(MitraIcons.Bell, "Evening check-in", null, fmtTime(s.eveningReminder), onClick = { reminder = false })
            }
            SectionHeader("Privacy & integrations")
            MitraCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                MitraSwitchRow("Share with AI assistant", s.shareAI, { onAction(WellnessSettingsAction.ShareAI(it)) }, "Off keeps wellness out of AI context")
                HorizontalDivider(color = c.border)
                MitraSwitchRow("Show on Today", s.showOnToday, { onAction(WellnessSettingsAction.ShowOnToday(it)) }, "Mood picker and compact stats")
            }
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                StatRow(MitraIcons.Link, "Apple Health / Health Connect", "Coming in a later phase", "Soon", onClick = { onAction(WellnessSettingsAction.HealthIntegrations) })
            }
        }
    }
    editing?.let { t -> TargetDialog(t, targetInput(s, t), onAction) { editing = null } }
    reminder?.let { morning ->
        MitraTimePickerDialog(
            initial = if (morning) s.morningReminder else s.eveningReminder,
            onPick = { onAction(if (morning) WellnessSettingsAction.MorningReminder(it) else WellnessSettingsAction.EveningReminder(it)) },
            onDismiss = { reminder = null },
        )
    }
}

@Composable
private fun TargetDialog(target: WellnessTarget, initial: String, onAction: (WellnessSettingsAction) -> Unit, onDismiss: () -> Unit) {
    var text by remember(target) { mutableStateOf(initial) }
    val (title, label) = when (target) {
        WellnessTarget.Sleep -> "Sleep target" to "Hours (h:mm)"
        WellnessTarget.Steps -> "Steps target" to "Steps"
        WellnessTarget.Water -> "Water target" to "Glasses"
        WellnessTarget.Height -> "Height" to "Centimetres"
    }
    val save = { onAction(WellnessSettingsAction.SetTarget(target, text)); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Mitra.type.h3) },
        text = {
            MitraTextField(
                text, { text = it }, label,
                keyboardType = if (target == WellnessTarget.Sleep) KeyboardType.Text else KeyboardType.Number,
                imeAction = ImeAction.Done, onImeAction = save,
            )
        },
        confirmButton = { TextButton(onClick = save) { Text("Save", color = Mitra.colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mitra.colors.muted) } },
    )
}
