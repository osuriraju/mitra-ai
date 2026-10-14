package tech.beyondscale.mitra.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

/** Project icons offered by the form (web `EMOJIS` used by `ProjectForm`). */
val PROJECT_EMOJIS = listOf("🚀", "💼", "🙂", "🏠", "📚", "👨‍👩‍👧", "🎯", "💡", "🏋️", "✈️", "🎨", "🧾", "🌱", "🛠️")

internal fun validateProjectName(name: String): String? = if (name.isBlank()) "Name the project" else null

/**
 * Create/edit project form (web `ProjectForm`) as a bottom sheet. Show it only while open; [onSave] gets the trimmed
 * name and is called only when it is valid. The web's "Contributes to goal" field arrives with Goals (Phase 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFormSheet(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    initialEmoji: String = "🚀",
    onSave: (name: String, emoji: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Mitra.colors.bg) {
        ProjectForm(title, confirmLabel, initialName, initialEmoji, onCancel = close) { name, emoji ->
            onSave(name, emoji)
            close()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectForm(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialEmoji: String,
    onCancel: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val c = Mitra.colors
    var name by rememberSaveable { mutableStateOf(initialName) }
    var emoji by rememberSaveable { mutableStateOf(initialEmoji) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }
    val save = {
        val err = validateProjectName(name)
        error = err
        if (err == null) onSave(name.trim(), emoji)
    }

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.padding(top = 24.dp).size(56.dp).clip(RoundedCornerShape(MitraDimens.radiusSm)).background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(MitraDimens.radiusSm))
                        .semantics { contentDescription = "Icon $emoji" },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
                MitraTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (error != null) error = null
                    },
                    label = "Name",
                    placeholder = "Project name",
                    error = error,
                    imeAction = ImeAction.Done,
                    onImeAction = save,
                    fieldModifier = Modifier.focusRequester(nameFocus),
                    modifier = Modifier.weight(1f),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Icon", style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PROJECT_EMOJIS.forEach { e ->
                        val on = e == emoji
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(MitraDimens.radiusSm))
                                .background(if (on) c.accentSoft else c.surface)
                                .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(MitraDimens.radiusSm))
                                .selectable(selected = on, role = Role.RadioButton, onClick = { emoji = e })
                                .semantics { contentDescription = "Icon $e" },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 18.sp) }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MitraButton("Cancel", onClick = onCancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
            MitraButton(confirmLabel, onClick = save, modifier = Modifier.weight(1f))
        }
    }
}
