package tech.beyondscale.mitra.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.EventEffect

/** Full-screen focus mode (no shell bars). [taskId] `""` starts with the first open task for today. */
@Composable
fun FocusRoute(
    taskId: String,
    onClose: () -> Unit,
    onOpenTask: (String) -> Unit,
    vm: FocusViewModel = koinViewModel { parametersOf(taskId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction, onClose)
    val open by rememberUpdatedState(onOpenTask)
    LaunchedEffect(vm) { vm.openTask.collect { open(it) } }
    val koin = getKoin()
    LaunchedEffect(vm) { vm.attachKeepAlive(koin.getOrNull<FocusKeepAlive>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }
    FocusScreen(state, vm::onAction, onClose)
}

@Composable
fun FocusScreen(state: FocusUiState, onAction: (FocusAction) -> Unit, onClose: () -> Unit) {
    val c = Mitra.colors
    var menu by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(MitraIcons.X, contentDescription = "Exit focus", tint = c.muted) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (state.position.isNotEmpty() && !state.empty) MitraChip(state.position)
            }
            if (state.loaded && !state.empty) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions", tint = c.muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        OverflowItem("Reset timer", MitraIcons.Refresh) { menu = false; onAction(FocusAction.Reset) }
                        OverflowItem("+5 minutes", MitraIcons.Plus) { menu = false; onAction(FocusAction.AddFive) }
                        OverflowItem("Open task", MitraIcons.CheckSquare) { menu = false; onAction(FocusAction.OpenTask) }
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        if (!state.loaded) return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = MitraDimens.pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            if (state.empty) {
                Eyebrow("Focus")
                Text("Nothing left for today", style = Mitra.type.h1, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text("Add a task or pull one from Upcoming to start a session.", style = Mitra.type.sm, color = c.muted, textAlign = TextAlign.Center)
                MitraButton("Back to tasks", onClick = onClose, size = ButtonSize.Lg)
                return@Column
            }
            Eyebrow("Now")
            Text(state.title.orEmpty(), style = Mitra.type.h1, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(
                state.clock,
                style = Mitra.type.numLg.copy(fontSize = 56.sp, lineHeight = 64.sp),
                color = if (state.finished) c.success else c.text,
                modifier = Modifier.semantics {
                    contentDescription = timeLeftDescription(state.clock, state.finished)
                },
            )
            Segments(state.segments)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    if (state.running) MitraIcons.Pause else MitraIcons.Play,
                    label = if (state.running) "Pause" else "Resume",
                    onClick = { onAction(FocusAction.PauseResume) },
                )
                MitraButton("Done", onClick = { onAction(FocusAction.Done) }, icon = MitraIcons.Check, size = ButtonSize.Lg, modifier = Modifier.clip(CircleShape))
                RoundIconButton(MitraIcons.ArrowRight, label = "Skip", onClick = { onAction(FocusAction.Skip) })
            }
            Text((state.next ?: "") + "Time is logged when you finish.", style = Mitra.type.sm, color = c.muted, textAlign = TextAlign.Center)
        }
    }
}

private fun timeLeftDescription(clock: String, finished: Boolean): String {
    if (finished) return "Session complete"
    val parts = clock.split(":")
    val m = parts.getOrNull(0)?.toIntOrNull() ?: return clock
    val s = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return "$m minute${if (m == 1) "" else "s"} $s second${if (s == 1) "" else "s"} left"
}

@Composable
private fun Segments(on: Int) {
    val c = Mitra.colors
    Row(
        Modifier.width(200.dp).semantics { contentDescription = "$on of 4 quarters done" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(4) { i ->
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < on) c.accent else c.track))
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Mitra.colors
    Box(
        Modifier.size(56.dp).clip(CircleShape).border(1.dp, c.border, CircleShape).background(Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
    }
}

/** One overflow-menu entry (48dp tall); shared by Focus and Project detail. */
@Composable
internal fun OverflowItem(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Mitra.colors.danger else Mitra.colors.text
    DropdownMenuItem(
        text = { Text(text, style = Mitra.type.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    )
}
