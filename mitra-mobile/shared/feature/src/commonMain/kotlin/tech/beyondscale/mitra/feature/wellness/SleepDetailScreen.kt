package tech.beyondscale.mitra.feature.wellness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.toFixed
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.ScrubBars
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.common.MitraTimePickerDialog

@Composable
fun SleepDetailRoute(
    onBack: () -> Unit,
    onOpenHabit: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenCheckin: () -> Unit,
    vm: SleepDetailViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, {})
    SleepDetailScreen(state, vm::onAction, onBack) { link ->
        when (link) {
            is SleepLink.Habit -> onOpenHabit(link.id)
            is SleepLink.Note -> onOpenNote(link.id)
            SleepLink.Checkin -> onOpenCheckin()
        }
    }
}

@Composable
fun SleepDetailScreen(state: SleepDetailUiState, onAction: (SleepAction) -> Unit, onBack: () -> Unit, onOpen: (SleepLink) -> Unit) {
    val c = Mitra.colors
    var editing by rememberSaveable(state.day) { mutableStateOf(false) }
    var bed by rememberSaveable(state.day, state.bedRaw) { mutableStateOf(state.bedRaw) }
    var wake by rememberSaveable(state.day, state.wakeRaw) { mutableStateOf(state.wakeRaw) }
    var noteOpen by rememberSaveable(state.day) { mutableStateOf(false) }
    var noteText by rememberSaveable(state.day) { mutableStateOf("") }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = bed, false = wake
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Sleep", onBack = onBack) {
            IconButton(onClick = { onAction(SleepAction.Prev) }) { Icon(MitraIcons.ChevronLeft, contentDescription = "Previous night", tint = c.muted) }
            IconButton(onClick = { onAction(SleepAction.Next) }, enabled = state.canNext) {
                Icon(MitraIcons.ChevronRight, contentDescription = "Next night", tint = if (state.canNext) c.muted else c.faint)
            }
        }
        if (!state.loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            Eyebrow(state.eyebrow)
            MitraCard {
                Row(verticalAlignment = Alignment.Top) {
                    Kpi("Duration", state.duration, state.durationSub, up = if (state.underTarget) false else null, modifier = Modifier.weight(1f))
                    MitraChip("Manual entry", tone = ChipTone.Neutral, icon = MitraIcons.Edit)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.bed, style = Mitra.type.xs, color = c.muted)
                    Text("Bed → wake", style = Mitra.type.xs, color = c.muted)
                    Text(state.wake, style = Mitra.type.xs, color = c.muted)
                }
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField("Bedtime", bed, MitraIcons.Moon, { picking = true }, Modifier.weight(1f))
                        TimeField("Wake time", wake, MitraIcons.Sun, { picking = false }, Modifier.weight(1f))
                        MitraButton("Save", onClick = { onAction(SleepAction.SaveTimes(bed, wake)); editing = false })
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MitraButton("Edit times", onClick = { editing = true }, kind = ButtonKind.Outline)
                        MitraButton(
                            if (state.note.isNotEmpty()) "Edit note" else "Add note",
                            onClick = { noteText = state.note; noteOpen = !noteOpen }, kind = ButtonKind.Ghost,
                        )
                    }
                }
                if (noteOpen) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MitraTextField(
                            noteText, { noteText = it.take(2000) }, "Note", Modifier.weight(1f), placeholder = "Note for this night",
                            onImeAction = { onAction(SleepAction.SaveNote(noteText)); noteOpen = false },
                        )
                        MitraButton("Save", onClick = { onAction(SleepAction.SaveNote(noteText)); noteOpen = false })
                    }
                }
            }
            MitraCard {
                Eyebrow("This week")
                ScrubBars(
                    state.week.map { jsRound(it * 10) }, state.weekLabels, highlight = 6,
                    format = { "${toFixed(it / 10.0, 1)}h" }, modifier = Modifier.height(120.dp),
                )
                Text(state.weekFoot, style = Mitra.type.xs, color = c.muted)
            }
            SectionHeader("Related")
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                state.related.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = c.border)
                    val icon = when (r.target) {
                        is SleepLink.Habit -> MitraIcons.Repeat
                        is SleepLink.Note -> MitraIcons.Edit
                        SleepLink.Checkin -> MitraIcons.Smile
                        null -> MitraIcons.Note
                    }
                    StatRow(icon, r.title, r.sub, "", onClick = r.target?.let { t -> { onOpen(t) } })
                }
            }
        }
    }
    picking?.let { isBed ->
        MitraTimePickerDialog(
            initial = if (isBed) bed else wake,
            onPick = { if (isBed) bed = it else wake = it },
            onDismiss = { picking = null },
        )
    }
}
