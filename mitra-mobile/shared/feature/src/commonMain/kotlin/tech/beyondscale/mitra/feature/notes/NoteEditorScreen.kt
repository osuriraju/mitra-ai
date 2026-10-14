package tech.beyondscale.mitra.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.designsystem.components.CheckCircle
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraConfirmDialog
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.NOTE_FOLDER_ICON
import tech.beyondscale.mitra.domain.notes.NoteLine
import tech.beyondscale.mitra.domain.notes.WikiTarget
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun NoteEditorRoute(
    id: String,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    /** Open straight into writing (a note just created). */
    startWriting: Boolean = false,
    vm: NoteEditorViewModel = koinViewModel(key = "note:$id") { parametersOf(id, startWriting) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Deleting sends "Note deleted" (with Undo) then Close: go back.
    EventEffect(vm.events, vm::onAction, onClose = onBack)
    LaunchedEffect(vm) { vm.openNote.collect { onOpenNote(it) } }
    // Autosave: write the pending draft when the app goes to the background or the screen leaves.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushNow() }
    DisposableEffect(vm) { onDispose { vm.flushNow() } }
    val open: (String, String) -> Unit = { kind, target ->
        when (kind) {
            "Goal" -> onOpenGoal(target)
            "Task" -> onOpenTask(target)
            "Project" -> onOpenProject(target)
            else -> onOpenNote(target)
        }
    }
    NoteEditorScreen(state, vm::onAction, onBack, open)
}

/** Web `NoteEditorScreen`, full screen: read view (checklists, [[links]]) or write view with the formatting toolbar above the keyboard. */
@Composable
fun NoteEditorScreen(
    state: NoteEditorUiState,
    onAction: (NoteEditorAction) -> Unit,
    onBack: () -> Unit,
    onOpen: (kind: String, id: String) -> Unit,
) {
    val c = Mitra.colors
    var menu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val found = state.loaded && !state.notFound

    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar(if (found) "" else "Note", onBack = onBack) {
            if (found) {
                Text(state.status, style = Mitra.type.xs, color = c.faint)
                IconButton(onClick = { onAction(NoteEditorAction.Write(!state.writing)) }) {
                    Icon(
                        if (state.writing) MitraIcons.Eye else MitraIcons.Edit,
                        contentDescription = if (state.writing) "Preview" else "Edit", tint = c.text,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Move to folder…", MitraIcons.Folder) { menu = false; folderMenu = true }
                        MenuItem("Duplicate", MitraIcons.Columns) { menu = false; onAction(NoteEditorAction.Duplicate) }
                        MenuItem("Delete", MitraIcons.Trash, danger = true) { menu = false; confirmDelete = true }
                    }
                    DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                        state.folders.forEach { f ->
                            MenuItem(f, MitraIcons.byName(NOTE_FOLDER_ICON[f] ?: "folder")) {
                                folderMenu = false
                                if (f != state.folder) onAction(NoteEditorAction.MoveTo(f))
                            }
                        }
                    }
                }
            }
        }
        when {
            !state.loaded -> Unit
            state.notFound -> MitraCard(Modifier.padding(MitraDimens.pad)) {
                EmptyState(MitraIcons.Note, "This note doesn't exist", "It may have been deleted on another device.")
            }
            state.writing -> WriteView(state, onAction)
            else -> ReadView(state, onAction, onOpen)
        }
    }
    if (confirmDelete) {
        MitraConfirmDialog(
            title = "Delete “${state.title}”?", body = "You can undo for a few seconds.", confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onAction(NoteEditorAction.Delete) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WriteView(state: NoteEditorUiState, onAction: (NoteEditorAction) -> Unit) {
    val c = Mitra.colors
    // The field owns text + cursor while typing; it re-reads the body only when the VM changes it (bodyRevision).
    var body by remember(state.bodyRevision) { mutableStateOf(TextFieldValue(state.body, TextRange(state.body.length))) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad)) {
        BasicTextField(
            value = state.title,
            onValueChange = { onAction(NoteEditorAction.Title(it)) },
            textStyle = Mitra.type.h1.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            decorationBox = { inner ->
                if (state.title.isEmpty()) Text("Title", style = Mitra.type.h1, color = c.faint)
                inner()
            },
        )
        BasicTextField(
            value = body,
            onValueChange = {
                val changed = it.text != body.text
                body = it
                if (changed) onAction(NoteEditorAction.Body(it.text))
            },
            textStyle = Mitra.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            decorationBox = { inner ->
                if (body.text.isEmpty()) {
                    Text("Write in Markdown-ish:\n# Heading · - bullet · - [ ] checklist · [[Link to a note, goal or task]]", style = Mitra.type.body, color = c.faint)
                }
                inner()
            },
        )
    }
    // Formatting toolbar: last in the imePadding column, so it sits right above the keyboard.
    HorizontalDivider(color = c.border)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NoteFormat.entries.forEach { f ->
            TextButton(onClick = {
                val out = applyFormat(FormatEdit(body.text, body.selection.min, body.selection.max), f)
                body = TextFieldValue(out.text, TextRange(out.selStart, out.selEnd))
                onAction(NoteEditorAction.Body(out.text))
            }) { Text(f.label, style = Mitra.type.bodyBold, color = c.text) }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { onAction(NoteEditorAction.Write(false)) }) { Icon(MitraIcons.Eye, contentDescription = "Preview", tint = c.muted) }
    }
}

@Composable
private fun ReadView(state: NoteEditorUiState, onAction: (NoteEditorAction) -> Unit, onOpen: (String, String) -> Unit) {
    val c = Mitra.colors
    var tag by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "chips") {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MitraChip(state.folder, tone = ChipTone.Outline, icon = MitraIcons.byName(NOTE_FOLDER_ICON[state.folder] ?: "folder"))
                state.tags.forEach { t -> MitraChip("#$t", tone = ChipTone.Outline, onRemove = { onAction(NoteEditorAction.RemoveTag(t)) }) }
                BasicTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    singleLine = true,
                    textStyle = Mitra.type.sm.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(NoteEditorAction.AddTag(tag)); tag = "" }),
                    modifier = Modifier.width(90.dp).padding(vertical = 6.dp),
                    decorationBox = { inner -> if (tag.isEmpty()) Text("+ tag", style = Mitra.type.sm, color = c.faint); inner() },
                )
                if (state.ai) MitraChip("AI draft", tone = ChipTone.Accent, icon = MitraIcons.Sparkles)
            }
        }
        item(key = "title") {
            Text(state.title, style = Mitra.type.h1, color = c.text, modifier = Modifier.fillMaxWidth().clickable { onAction(NoteEditorAction.Write(true)) }.padding(vertical = 8.dp))
        }
        if (state.body.isEmpty()) {
            item(key = "empty") {
                Text("Empty note — start writing", style = Mitra.type.sm, color = c.accent, modifier = Modifier.clickable { onAction(NoteEditorAction.Write(true)) })
            }
        }
        items(state.lines, key = { "l:${it.index}" }) { l -> BodyLine(l, onAction, onOpen) }
        if (state.linked.isNotEmpty()) {
            item(key = "linked-h") { SectionHeader("Linked") }
            items(state.linked, key = { "ln:${it.kind}:${it.id}" }) { LinkRow(it, onOpen) }
        }
        item(key = "back-h") { SectionHeader("Backlinks · ${state.backlinks.size}") }
        if (state.backlinks.isEmpty()) {
            item(key = "back-none") { Text("No other note links here yet.", style = Mitra.type.sm, color = c.muted) }
        }
        items(state.backlinks, key = { "bl:${it.id}" }) { LinkRow(it, onOpen) }
        item(key = "footer") { Text(state.footer, style = Mitra.type.xs, color = c.faint, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun BodyLine(l: BodyLineUi, onAction: (NoteEditorAction) -> Unit, onOpen: (String, String) -> Unit) {
    val c = Mitra.colors
    when (val line = l.line) {
        NoteLine.Blank -> Spacer(Modifier.height(8.dp))
        is NoteLine.H1 -> InlineText(l.inline, Mitra.type.h2, onOpen)
        is NoteLine.H2 -> InlineText(l.inline, Mitra.type.h3, onOpen)
        is NoteLine.Check -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckCircle(line.done, { onAction(NoteEditorAction.ToggleCheck(l.index)) }, label = line.text)
            InlineText(l.inline, Mitra.type.body.copy(textDecoration = if (line.done) TextDecoration.LineThrough else null, color = if (line.done) c.muted else c.text), onOpen)
        }
        is NoteLine.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", style = Mitra.type.body, color = c.muted)
            InlineText(l.inline, Mitra.type.body, onOpen)
        }
        is NoteLine.Para -> InlineText(l.inline, Mitra.type.body, onOpen)
    }
}

/** Text with tappable `[[links]]` (unresolved links are shown but inert). */
@Composable
private fun InlineText(runs: List<InlineUi>, style: androidx.compose.ui.text.TextStyle, onOpen: (String, String) -> Unit) {
    val c = Mitra.colors
    val links = runs.filter { it.link && it.target != null }
    if (links.isEmpty()) {
        Text(
            buildAnnotatedString { runs.forEach { r -> if (r.link) withStyle(SpanStyle(color = c.muted)) { append(r.text) } else append(r.text) } },
            style = style.copy(color = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) c.text else style.color),
        )
        return
    }
    // Lines with links: plain text then one chip-like link per target (keeps every link a proper touch target).
    Column {
        Text(
            buildAnnotatedString {
                runs.forEach { r -> if (r.link) withStyle(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)) { append(r.text) } else append(r.text) }
            },
            style = style.copy(color = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) c.text else style.color),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            links.forEach { r ->
                val (kind, id) = when (val t = r.target!!) {
                    is WikiTarget.ToNote -> "Note" to t.id
                    is WikiTarget.ToGoal -> "Goal" to t.id
                    is WikiTarget.ToTask -> "Task" to t.id
                }
                MitraChip(r.text, tone = ChipTone.Accent, icon = MitraIcons.Link, onClick = { onOpen(kind, id) })
            }
        }
    }
}

@Composable
private fun LinkRow(item: LinkedNoteUi, onOpen: (String, String) -> Unit) {
    val c = Mitra.colors
    val icon = when (item.kind) {
        "Goal" -> MitraIcons.Target
        "Task" -> MitraIcons.CheckSquare
        "Project" -> MitraIcons.Layers
        else -> MitraIcons.Note
    }
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(item.kind, item.id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.muted)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Mitra.type.body, color = c.text)
            Text(item.kind, style = Mitra.type.xs, color = c.muted)
        }
        Icon(MitraIcons.ChevronRight, contentDescription = null, tint = c.faint)
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Mitra.colors.danger else Mitra.colors.text
    DropdownMenuItem(
        text = { Text(text, style = Mitra.type.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}
