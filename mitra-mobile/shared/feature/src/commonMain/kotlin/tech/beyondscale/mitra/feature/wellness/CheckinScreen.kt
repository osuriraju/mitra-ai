package tech.beyondscale.mitra.feature.wellness

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.MOODS
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.common.MitraTimePickerDialog

/**
 * Wellness tab root. [onSaved] runs after a save (web opens Trends); [onSkip] leaves without saving (web opens Today).
 */
@Composable
fun CheckinRoute(
    onSaved: () -> Unit,
    onSkip: () -> Unit,
    onOpenTrends: () -> Unit,
    onOpenSettings: () -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
    vm: CheckinViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, {}, onClose = onSaved)
    CheckinScreen(
        state,
        onAction = { a -> vm.onAction(a); if (a == CheckinAction.Skip) onSkip() },
        onOpenTrends, onOpenSettings, topBarActions,
    )
}

private enum class TimeTarget { Bed, Wake }

@Composable
fun CheckinScreen(
    state: CheckinUiState,
    onAction: (CheckinAction) -> Unit,
    onOpenTrends: () -> Unit,
    onOpenSettings: () -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mitra.colors
    val f = state.form
    val track = state.settings.track
    var picking by remember { mutableStateOf<TimeTarget?>(null) }
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Wellness") {
            IconButton(onClick = onOpenTrends) { Icon(MitraIcons.Chart, contentDescription = "Trends", tint = c.muted) }
            IconButton(onClick = onOpenSettings) { Icon(MitraIcons.Settings, contentDescription = "Wellness settings", tint = c.muted) }
            topBarActions()
        }
        if (!state.loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            MitraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Daily check-in")
                        Text(state.heading, style = Mitra.type.h3, color = c.text)
                    }
                    state.streak?.let { MitraChip(it, tone = ChipTone.Success) }
                }
                if (track.mood) {
                    FieldLabel("Mood")
                    MoodRow(f.mood, { onAction(CheckinAction.Mood(it)) })
                }
                if (track.sleep) {
                    FieldLabel("Sleep")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField("Bedtime", f.sleepStart, MitraIcons.Moon, { picking = TimeTarget.Bed }, Modifier.weight(1f))
                        Text("→", color = c.muted)
                        TimeField("Wake time", f.sleepEnd, MitraIcons.Sun, { picking = TimeTarget.Wake }, Modifier.weight(1f))
                        Text(state.sleepLabel, style = Mitra.type.num.copy(fontWeight = FontWeight.Bold), color = c.text)
                    }
                    Text(state.sleepHint, style = Mitra.type.xs, color = c.muted)
                }
                if (track.steps) {
                    MitraTextField(
                        f.steps, { onAction(CheckinAction.Steps(it)) }, "Steps",
                        keyboardType = KeyboardType.Number, placeholder = state.stepsTarget,
                    )
                    Text("${state.stepsTarget} · enter manually — wearable import comes later", style = Mitra.type.xs, color = c.faint)
                }
                if (track.water) {
                    FieldLabel("Water")
                    Stepper(f.water, { onAction(CheckinAction.Water(it)) }, MAX_WATER, "glasses", Modifier.fillMaxWidth())
                    Text("of ${state.settings.waterTarget} glasses", style = Mitra.type.xs, color = c.faint)
                }
                if (track.bmi) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MitraTextField(f.weight, { onAction(CheckinAction.Weight(it)) }, "Weight (kg)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                        MitraTextField(f.height, { onAction(CheckinAction.Height(it)) }, "Height (cm)", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                    }
                    if (state.weightHint.isNotEmpty()) Text(state.weightHint, style = Mitra.type.xs, color = c.muted)
                    BmiCard(state)
                }
                MitraTextField(
                    f.note, { onAction(CheckinAction.Note(it)) }, "Note",
                    placeholder = "Anything worth remembering about today?", singleLine = false, minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MitraButton("Skip today", onClick = { onAction(CheckinAction.Skip) }, kind = ButtonKind.Ghost)
                    Spacer(Modifier.weight(1f))
                    MitraButton("Save check-in", onClick = { onAction(CheckinAction.Save) })
                }
            }
            SectionHeader("Today")
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                val ws = state.settings
                val rows = buildList<@Composable () -> Unit> {
                    if (track.mood) add { StatRow(MitraIcons.Smile, "Mood", state.moodSub, f.mood?.let { MOODS[it] } ?: "—") }
                    if (track.sleep) add {
                        val under = state.sleepMin < ws.sleepTarget
                        StatRow(MitraIcons.Moon, "Sleep", if (under) "Under target" else "On target", state.sleepLabel, if (under) Tone.Accent else Tone.Success)
                    }
                    if (track.steps) add {
                        val steps = f.steps.toIntOrNull()
                        StatRow(MitraIcons.Activity, "Steps", state.stepsTarget, steps?.let { localeIN(it.toDouble()) } ?: "—", if ((steps ?: 0) >= ws.stepsTarget) Tone.Success else Tone.Neutral)
                    }
                    if (track.water) add {
                        StatRow(MitraIcons.Droplet, "Water", null, "${f.water} / ${ws.waterTarget}", if (f.water >= ws.waterTarget) Tone.Success else Tone.Neutral)
                    }
                    if (track.bmi) add {
                        StatRow(MitraIcons.Scale, "BMI", if (state.bmi != 0.0) "${state.bmiBand} · ${f.weight} kg · ${f.height} cm" else "Enter weight and height", state.bmiLabel)
                    }
                }
                rows.forEachIndexed { i, row -> if (i > 0) HorizontalDivider(color = c.border); row() }
            }
            MitraCard(tone = CardTone.Soft) {
                Eyebrow("Privacy")
                Text(
                    "Wellness data stays on this account and is ${if (state.shareAI) "" else "not "}shared with the AI assistant (change in Settings). No medical advice is given.",
                    style = Mitra.type.sm, color = c.text,
                )
            }
        }
    }
    picking?.let { which ->
        MitraTimePickerDialog(
            initial = if (which == TimeTarget.Bed) f.sleepStart else f.sleepEnd,
            onPick = { onAction(if (which == TimeTarget.Bed) CheckinAction.SleepStart(it) else CheckinAction.SleepEnd(it)) },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun BmiCard(state: CheckinUiState) {
    val c = Mitra.colors
    MitraCard(tone = CardTone.Accent) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("BMI · calculated", style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.bmiLabel, style = Mitra.type.numLg, color = c.text)
                    Text(state.bmiBand, style = Mitra.type.sm, color = c.muted)
                }
            }
            Text(
                "weight ÷ height² ${state.bmiFormula} · Reference 18.5–24.9 normal. Informational only, not medical advice.",
                Modifier.weight(1f), style = Mitra.type.xs, color = c.muted,
            )
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (state.bmiSegment == i) c.accent else c.track))
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("<18.5", "18.5–24.9", "25–29.9", "30+").forEach { Text(it, style = Mitra.type.xs, color = c.faint) }
        }
    }
}
