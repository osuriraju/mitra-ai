package tech.beyondscale.mitra.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.BannerTone
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.ai.boldSegments
import tech.beyondscale.mitra.domain.ai.proposalApplyLabel
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.feature.common.EventEffect

/* ================= CHAT ================= */

@Composable
fun AiChatRoute(
    onOpenLog: () -> Unit,
    onOpenSuggestions: () -> Unit,
    /** "View" on an applied card: "transactions" · "tasks" · "goals". */
    onOpenModule: (String) -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
    vm: AiChatViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    AiChatScreen(state, vm::onAction, onOpenLog, onOpenSuggestions, onOpenModule, topBarActions)
}

@Composable
fun AiChatScreen(
    state: AiChatUiState,
    onAction: (AiChatAction) -> Unit,
    onOpenLog: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenModule: (String) -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mitra.colors
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, state.typing, last?.text?.length) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.scrollToItem(count - 1)
    }
    val send = { q: String -> onAction(AiChatAction.Send(q)); text = "" }
    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar("Mitra") {
            IconButton(onClick = onOpenSuggestions) { Icon(MitraIcons.Sparkles, contentDescription = "Suggested actions", tint = c.accent) }
            IconButton(onClick = onOpenLog) { Icon(MitraIcons.History, contentDescription = "AI activity log", tint = c.muted) }
            if (state.messages.isNotEmpty()) {
                IconButton(onClick = { onAction(AiChatAction.Clear) }) { Icon(MitraIcons.Trash, contentDescription = "Clear conversation", tint = c.muted) }
            }
            topBarActions()
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(MitraDimens.pad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "scope") {
                Text(
                    "Mitra sees tasks, habits, goals, money and notes · every change is previewed, logged and reversible.",
                    style = Mitra.type.xs, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.enabled) item(key = "off") { MitraBanner("AI is switched off on this server (no API key configured). Everything else works as usual.") }
            if (state.offline) {
                item(key = "offline") {
                    MitraBanner("You're offline. Mitra needs a connection to answer — your conversation is saved on this device.", BannerTone.Info)
                }
            }
            if (state.messages.isEmpty()) {
                item(key = "hello") {
                    Bubble(AiWho.Ai) {
                        Text(
                            "Hi. I can capture, answer and plan — and I always show a preview before changing anything.",
                            style = Mitra.type.body, color = c.text,
                        )
                    }
                }
            }
            items(state.messages, key = { it.id }) { m ->
                Bubble(m.who) {
                    Text(
                        bold(m.text), style = Mitra.type.body, color = if (m.who == AiWho.User) c.onAccent else c.text,
                        modifier = if (m.revealing) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    )
                    m.chart?.let { MiniBars(it) }
                    m.proposal?.let { ProposalCard(m.id, it, m.applied, m.id in state.confirming, onAction, onOpenModule) }
                    if (m.showSources && !m.revealing) {
                        Text("Sources: your tasks, habits, goals, money · via Mitra AI.", style = Mitra.type.xs, color = c.faint)
                    }
                }
            }
            if (state.typing) item(key = "typing") { Bubble(AiWho.Ai) { Text("Thinking…", style = Mitra.type.sm, color = c.muted) } }
            if (state.openSuggestions.isNotEmpty()) {
                item(key = "sugg-h") { SectionHeader("Suggested actions") }
                items(state.openSuggestions, key = { "s:${it.id}" }) { s ->
                    MitraCard(onClick = onOpenSuggestions) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconBox(MitraIcons.byName(s.icon), toneOf(s.tone), size = 30.dp)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                Text(s.source, style = Mitra.type.xs, color = c.muted)
                            }
                        }
                    }
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = MitraDimens.pad),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.prompts) { p -> MitraChip(p, onClick = if (state.typing) null else ({ send(p) })) }
        }
        Row(
            Modifier.fillMaxWidth().padding(MitraDimens.pad, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Ask, capture or plan…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(text) }),
            )
            IconButton(onClick = { send(text) }, enabled = text.isNotBlank() && !state.typing) {
                Icon(MitraIcons.Send, contentDescription = "Send", tint = c.accent)
            }
        }
    }
}

@Composable
private fun Bubble(who: AiWho, content: @Composable () -> Unit) {
    val c = Mitra.colors
    val mine = who == AiWho.User
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(MitraDimens.radius))
                .background(if (mine) c.accent else c.surface2).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

/** Inline action card: the card is the preview; nothing is applied until the confirm button. */
@Composable
private fun ProposalCard(
    messageId: String,
    p: AiProposal,
    applied: Boolean,
    busy: Boolean,
    onAction: (AiChatAction) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val c = Mitra.colors
    MitraCard(tone = CardTone.Default) {
        if (p is AiProposal.Reschedule) {
            Text(p.title, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            p.moves.forEach { m -> Text("Move to ${m.label} · ${m.title}", style = Mitra.type.sm, color = c.muted) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBox(
                    when (p) { is AiProposal.Expense -> MitraIcons.Wallet; is AiProposal.Task -> MitraIcons.CheckSquare; else -> MitraIcons.Target },
                    Tone.Accent, size = 30.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(p.sub, style = Mitra.type.xs, color = c.muted)
                }
            }
        }
        if (applied) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MitraChip("Applied · logged", tone = ChipTone.Success, icon = MitraIcons.Check)
                MitraButton("View", onClick = {
                    onOpenModule(when (p) { is AiProposal.Expense -> "transactions"; is AiProposal.Plan -> "goals"; else -> "tasks" })
                }, kind = ButtonKind.Ghost)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MitraButton(proposalApplyLabel(p), onClick = { onAction(AiChatAction.Confirm(messageId)) }, loading = busy, enabled = !busy)
                MitraButton("Cancel", onClick = { onAction(AiChatAction.Cancel(messageId)) }, kind = ButtonKind.Ghost, enabled = !busy)
            }
            Text("Nothing changes until you confirm. Every change is logged and reversible.", style = Mitra.type.xs, color = c.faint)
        }
    }
}

@Composable
private fun MiniBars(vals: List<Double>) {
    val c = Mitra.colors
    val max = vals.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Row(
        Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "Chart: ${vals.joinToString(", ") { it.toInt().toString() }}" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        vals.forEach { v ->
            Box(Modifier.weight(1f).fillMaxHeight((v / max).toFloat().coerceIn(0.03f, 1f)).clip(RoundedCornerShape(3.dp)).background(c.accent))
        }
    }
}

private fun bold(text: String) = buildAnnotatedString {
    boldSegments(text).forEach { (t, b) -> if (b) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) } else append(t) }
}

private fun toneOf(tone: String): Tone = when (tone) {
    "accent" -> Tone.Accent; "success" -> Tone.Success; "warning" -> Tone.Warning; "danger" -> Tone.Danger; else -> Tone.Neutral
}

/* ================= SUGGESTIONS ================= */

@Composable
fun AiSuggestionsRoute(onBack: () -> Unit, onOpenLog: () -> Unit, vm: AiSuggestionsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    AiSuggestionsScreen(state, vm::onAction, onBack, onOpenLog)
}

@Composable
fun AiSuggestionsScreen(state: AiSuggestionsUiState, onAction: (AiSuggestionsAction) -> Unit, onBack: () -> Unit, onOpenLog: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Suggested actions", onBack = onBack) {
            IconButton(onClick = { onAction(AiSuggestionsAction.Refresh) }, enabled = !state.busy && state.enabled) {
                Icon(MitraIcons.Refresh, contentDescription = if (state.busy) "Thinking" else "Refresh", tint = c.accent)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "sub") { Text("Review, apply or dismiss — nothing is applied automatically", style = Mitra.type.sm, color = c.muted) }
            if (state.offline) item(key = "offline") { MitraBanner("You're offline. Suggestions need a connection — showing the last ones loaded.", BannerTone.Info) }
            if (!state.enabled) item(key = "off") { MitraBanner("AI is switched off on this server.") }
            item(key = "tabs") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MitraSegmented(listOf("Open ${state.openCount}", "Handled"), state.tab, { onAction(AiSuggestionsAction.Tab(it)) }, Modifier.weight(1f))
                    if (state.tab == 1 && state.list.isNotEmpty()) MitraButton("Restore all", onClick = { onAction(AiSuggestionsAction.RestoreAll) }, kind = ButtonKind.Ghost)
                }
            }
            if (state.list.isEmpty()) {
                item(key = "empty") {
                    MitraCard {
                        EmptyState(
                            MitraIcons.Sparkles,
                            when { state.busy -> "Looking at your data…"; state.tab == 0 -> "Nothing to suggest right now"; else -> "No handled suggestions" },
                            if (state.tab == 0 && !state.busy) "Mitra suggests actions when it notices patterns in your data." else "",
                            action = if (state.tab == 0 && !state.busy && state.enabled) {
                                { MitraButton("Look again", onClick = { onAction(AiSuggestionsAction.Refresh) }, kind = ButtonKind.Outline, icon = MitraIcons.Refresh) }
                            } else null,
                        )
                    }
                }
            }
            items(state.list, key = { it.id }) { s ->
                MitraCard(Modifier.alpha(if (s.state == SuggestionState.Dismissed) 0.6f else 1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBox(MitraIcons.byName(s.icon), toneOf(s.tone))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title, style = Mitra.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                                if (s.state != SuggestionState.Open) {
                                    MitraChip(s.state.wire, tone = if (s.state == SuggestionState.Applied) ChipTone.Success else ChipTone.Neutral)
                                }
                            }
                            Text(s.why, style = Mitra.type.sm, color = c.muted)
                            Text(s.source, style = Mitra.type.xs, color = c.faint)
                            val open = state.previewId == s.id
                            if (open) {
                                MitraCard(tone = CardTone.Soft) {
                                    Text("Will change", style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                                    Text(s.preview ?: s.why, style = Mitra.type.sm, color = c.text)
                                    Text("Reversible · from the activity log or the snackbar", style = Mitra.type.xs, color = c.muted)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (s.state == SuggestionState.Open) {
                                    if (open) {
                                        MitraButton("Confirm", onClick = { onAction(AiSuggestionsAction.Apply(s.id)) }, icon = MitraIcons.Check)
                                        MitraButton("Hide", onClick = { onAction(AiSuggestionsAction.TogglePreview(s.id)) }, kind = ButtonKind.Ghost)
                                    } else {
                                        MitraButton("Preview", onClick = { onAction(AiSuggestionsAction.TogglePreview(s.id)) }, kind = ButtonKind.Outline, icon = MitraIcons.Eye)
                                        MitraButton("Dismiss", onClick = { onAction(AiSuggestionsAction.Dismiss(s.id)) }, kind = ButtonKind.Ghost)
                                    }
                                } else {
                                    MitraButton("Restore", onClick = { onAction(AiSuggestionsAction.Restore(s.id)) }, kind = ButtonKind.Ghost)
                                    if (s.state == SuggestionState.Applied) MitraButton("View log", onClick = onOpenLog, kind = ButtonKind.Ghost)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================= ACTIVITY LOG ================= */

@Composable
fun AiLogRoute(onBack: () -> Unit, vm: AiLogViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    LaunchedEffect(Unit) { vm.onAction(AiLogAction.Refresh) }
    AiLogScreen(state, vm::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLogScreen(state: AiLogUiState, onAction: (AiLogAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("AI activity", onBack = onBack)
        PullToRefreshBox(state.refreshing, { onAction(AiLogAction.Refresh) }, Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
            ) {
                item(key = "filters") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MitraChip(state.type, selected = state.type != "All types", icon = MitraIcons.ChevronDown, onClick = { onAction(AiLogAction.NextType) })
                        MitraChip(if (state.allTime) "All time" else "Last 30 days", icon = MitraIcons.ChevronDown, onClick = { onAction(AiLogAction.ToggleRange) })
                        Text("${state.total} actions · ${state.revertedCount} reverted", style = Mitra.type.sm, color = c.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
                item(key = "list") {
                    MitraCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (state.rows.isEmpty()) {
                            Text("Mitra has not changed anything yet.", style = Mitra.type.sm, color = c.muted, modifier = Modifier.padding(MitraDimens.pad))
                        }
                        state.rows.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                IconBox(if (r.isRevertRow) MitraIcons.Undo else MitraIcons.Sparkles, toneOf(r.tone), size = 30.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(r.action) }
                                            if (r.detail.isNotEmpty()) append(" · ${r.detail}")
                                        },
                                        style = Mitra.type.sm, color = if (r.reverted) c.muted else c.text,
                                    )
                                    Text(r.sub, style = Mitra.type.xs, color = c.muted)
                                }
                                if (r.canRevert) MitraButton("Revert", onClick = { onAction(AiLogAction.Revert(r.id)) }, kind = ButtonKind.Ghost, icon = MitraIcons.Undo)
                            }
                        }
                    }
                }
                item(key = "note") {
                    MitraCard(tone = CardTone.Soft) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(MitraIcons.Lock, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                            Text(
                                "This log is immutable. Reverting creates a new entry rather than deleting the original. Financial and health records are never modified silently.",
                                style = Mitra.type.sm, color = c.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}
