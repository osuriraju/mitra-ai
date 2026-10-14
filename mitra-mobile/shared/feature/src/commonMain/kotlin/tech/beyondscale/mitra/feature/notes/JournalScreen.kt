package tech.beyondscale.mitra.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.ai.boldSegments
import tech.beyondscale.mitra.domain.model.MOODS
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun JournalRoute(onBack: () -> Unit, onOpenNote: (String) -> Unit, vm: JournalViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    JournalScreen(state, vm::onAction, onBack, onOpenNote)
}

/** Web `JournalScreen`: week strip, the day's entry with an AI draft, mood, Save, and earlier entries. */
@Composable
fun JournalScreen(state: JournalUiState, onAction: (JournalAction) -> Unit, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar("Journal", onBack = onBack) {
            IconButton(onClick = { onAction(JournalAction.Today) }) { Icon(MitraIcons.Calendar, contentDescription = "Today", tint = c.text) }
        }
        if (!state.loaded) return@Column
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "week") { WeekStrip(state, onAction) }
            item(key = "nav") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("← Previous week", style = Mitra.type.xs, color = c.accent, modifier = Modifier.clickable { onAction(JournalAction.PrevWeek) }.padding(vertical = 8.dp))
                    Text(state.longDate, style = Mitra.type.xs, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(
                        "Next week →", style = Mitra.type.xs, color = if (state.canNextWeek) c.accent else c.faint,
                        modifier = Modifier.clickable(enabled = state.canNextWeek) { onAction(JournalAction.NextWeek) }.padding(vertical = 8.dp),
                    )
                }
            }
            item(key = "entry") { EntryCard(state, onAction) }
            item(key = "earlier-h") { SectionHeader("Earlier") }
            if (state.earlier.isEmpty()) item(key = "earlier-none") { Text("No earlier entries.", style = Mitra.type.sm, color = c.muted) }
            items(state.earlier, key = { it.id }) { e ->
                MitraCard(onClick = { onAction(JournalAction.SelectDay(e.date)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.label, style = Mitra.type.bodyBold, color = c.text)
                            Text(e.excerpt, style = Mitra.type.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Open", style = Mitra.type.xs, color = c.accent, modifier = Modifier.clickable { onOpenNote(e.id) }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(state: JournalUiState, onAction: (JournalAction) -> Unit) {
    val c = Mitra.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        state.week.forEach { d ->
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (d.selected) c.accent else c.surface2)
                    .clickable(enabled = !d.future) { onAction(JournalAction.SelectDay(d.date)) }
                    .alpha(if (d.future) 0.4f else 1f)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fg = if (d.selected) c.onAccent else c.text
                Text(d.dow, style = Mitra.type.xs, color = if (d.selected) c.onAccent else c.muted)
                Text("${d.num}", style = Mitra.type.bodyBold, color = fg)
                Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (d.hasEntry) (if (d.selected) c.onAccent else c.accent) else c.surface2.copy(alpha = 0f)))
            }
        }
    }
}

@Composable
private fun EntryCard(state: JournalUiState, onAction: (JournalAction) -> Unit) {
    val c = Mitra.colors
    MitraCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(state.longDate)
                Text(state.heading, style = Mitra.type.h2, color = c.text)
            }
            when {
                state.entryId != null -> MitraChip(if (state.entryAi) "Saved · AI-assisted" else "Saved", tone = ChipTone.Success, icon = MitraIcons.Check)
                state.draft != null -> MitraChip("Draft · AI-generated", tone = ChipTone.Accent, icon = MitraIcons.Sparkles)
                else -> MitraButton(
                    if (state.drafting) "Drafting…" else "Draft with AI", onClick = { onAction(JournalAction.Draft) },
                    kind = ButtonKind.Soft, icon = MitraIcons.Sparkles, loading = state.drafting,
                )
            }
        }
        state.draft?.let { d ->
            MitraCard(Modifier.padding(top = 12.dp), tone = CardTone.Accent) {
                d.text.split("\n\n").forEach { p ->
                    Text(
                        buildAnnotatedString {
                            boldSegments(p).forEach { (t, bold) -> if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) } else append(t) }
                        },
                        style = Mitra.type.body, color = c.text, modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MitraButton("Keep & edit", onClick = { onAction(JournalAction.KeepDraft) }, kind = ButtonKind.Soft)
                    MitraButton("Regenerate", onClick = { onAction(JournalAction.Draft) }, kind = ButtonKind.Ghost, icon = MitraIcons.Refresh)
                    MitraButton("Discard", onClick = { onAction(JournalAction.DiscardDraft) }, kind = ButtonKind.Ghost)
                }
                Text(
                    "Generated from the day's timeline: ${d.sources}. Edit freely — the draft is never saved without you.",
                    style = Mitra.type.xs, color = c.faint, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        MitraTextField(
            state.text, { onAction(JournalAction.Text(it)) }, if (state.entryId != null) "Your entry" else "Your words",
            modifier = Modifier.padding(top = 12.dp), singleLine = false, minLines = 6, placeholder = "What stood out today?",
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MOODS.forEachIndexed { i, emoji ->
                val on = state.mood == i
                Text(
                    emoji, style = Mitra.type.h2,
                    modifier = Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.surface.copy(alpha = 0f))
                        .clickable { onAction(JournalAction.Mood(if (on) null else i)) }.padding(6.dp),
                )
            }
        }
        MitraButton(
            if (state.entryId != null) "Update entry" else "Save entry", onClick = { onAction(JournalAction.Save) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), size = ButtonSize.Lg,
        )
    }
}
