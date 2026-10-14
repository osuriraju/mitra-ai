package tech.beyondscale.mitra.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.notes.Marked

@Composable
fun SearchRoute(onBack: () -> Unit, onOpen: (SearchTarget) -> Unit, vm: SearchViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(vm) { vm.navigation.collect { open(it) } }
    SearchScreen(state, vm::onAction, onBack)
}

/** Web ⌘K palette as a screen: the query field, then grouped results. */
@Composable
fun SearchScreen(state: SearchUiState, onAction: (SearchAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar("Search", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "q") {
                MitraTextField(
                    state.query, { onAction(SearchAction.Query(it)) }, "Search or ask Mitra",
                    placeholder = "Tasks, notes, money, goals, habits…",
                    imeAction = ImeAction.Search,
                    // Enter runs the first row (Add task), as on the web.
                    onImeAction = { state.groups.firstOrNull()?.items?.firstOrNull()?.let { onAction(SearchAction.Open(it)) } },
                    fieldModifier = Modifier.focusRequester(focus),
                )
            }
            if (state.noRecords) item(key = "none") {
                Text("No matching records — add it as a task or ask Mitra.", style = Mitra.type.sm, color = c.muted)
            }
            items(state.groups, key = { it.title }) { g ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(g.title)
                    MitraCard(contentPadding = PaddingValues(0.dp)) {
                        g.items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = c.border)
                            ResultRow(item) { onAction(SearchAction.Open(item)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: SearchItemUi, onClick: () -> Unit) {
    val c = Mitra.colors
    val (icon, tone) = when (item.icon) {
        SearchIcon.AddTask -> MitraIcons.Plus to Tone.Accent
        SearchIcon.Ask -> MitraIcons.Sparkles to Tone.Accent
        SearchIcon.Task -> MitraIcons.CheckSquare to Tone.Neutral
        SearchIcon.Note -> MitraIcons.Note to Tone.Neutral
        SearchIcon.Money -> MitraIcons.Wallet to Tone.Neutral
        SearchIcon.Goal -> MitraIcons.Target to Tone.Neutral
        SearchIcon.Habit -> MitraIcons.Repeat to Tone.Neutral
        SearchIcon.Place -> MitraIcons.ArrowRight to Tone.Neutral
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBox(icon, tone, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(marked(item.title, c.accentSoft), style = Mitra.type.bodyBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.sub.isNotEmpty()) Text(item.sub, style = Mitra.type.xs, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun marked(runs: Marked, bg: Color): AnnotatedString = buildAnnotatedString {
    runs.forEach { (t, hit) -> if (hit) withStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold)) { append(t) } else append(t) }
}
