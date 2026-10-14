package tech.beyondscale.mitra.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun OnboardingRoute(firstName: String, vm: OnboardingViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    OnboardingScreen(state, firstName, vm::onAction)
}

private fun OnbArea.icon(): ImageVector = when (this) {
    OnbArea.Tasks -> MitraIcons.CheckSquare
    OnbArea.Money -> MitraIcons.Wallet
    OnbArea.Habits -> MitraIcons.Repeat
    OnbArea.Goals -> MitraIcons.Target
    OnbArea.Wellness -> MitraIcons.Heart
    OnbArea.Notes -> MitraIcons.Note
}

@Composable
fun OnboardingScreen(state: OnboardingUiState, firstName: String, onAction: (OnboardingAction) -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(MitraDimens.pad)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STEP ${state.step} OF 4", Modifier.weight(1f), style = Mitra.type.xs, color = c.muted)
            TextButton(onClick = { onAction(OnboardingAction.Skip) }, enabled = !state.saving) { Text("Skip", color = c.accent) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..4).forEach { i ->
                Box(Modifier.width(if (i == state.step) 22.dp else 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (i == state.step) c.accent else c.border))
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            when (state.step) {
                1 -> {
                    Heading("What do you want Mitra to help with first?", "Pick a few. This sets up your Today screen and Quick Add — you can change it any time.")
                    OnbArea.entries.forEach { a ->
                        val on = a in state.picked
                        MitraCard(Modifier.fillMaxWidth(), onClick = { onAction(OnboardingAction.Toggle(a)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconBox(a.icon(), if (on) Tone.Accent else Tone.Neutral)
                                Column(Modifier.weight(1f)) {
                                    Text(a.title, style = Mitra.type.bodyBold, color = c.text)
                                    Text(a.sub, style = Mitra.type.xs, color = c.muted)
                                }
                                if (on) Icon(MitraIcons.CheckCircle, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(22.dp))
                                else Icon(MitraIcons.Circle, contentDescription = null, tint = c.faint, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
                2 -> {
                    Heading("Try a first capture", "Type naturally. Mitra previews before saving anything.")
                    MitraTextField(state.capture, { onAction(OnboardingAction.Capture(it)) }, "First capture", placeholder = "Spent ₹450 on dinner · Call John tomorrow")
                    state.preview?.let { MitraCard(Modifier.fillMaxWidth()) { Text("Preview: $it", style = Mitra.type.sm, color = c.text) } }
                    Text("Try “Call John tomorrow” or “Spent ₹120 on coffee”.", style = Mitra.type.sm, color = c.muted)
                }
                3 -> {
                    Heading("Notifications", "Only what’s actionable. Quiet hours 10:30 PM – 7:00 AM by default.")
                    val n = state.notif
                    MitraCard {
                        MitraSwitchRow("Task reminders", n.tasks, { onAction(OnboardingAction.Notif(n.copy(tasks = it))) }, sub = "Due and overdue")
                        MitraSwitchRow("Habit reminders", n.habits, { onAction(OnboardingAction.Notif(n.copy(habits = it))) }, sub = "At the time you set per habit")
                        MitraSwitchRow("Bills & subscriptions", n.bills, { onAction(OnboardingAction.Notif(n.copy(bills = it))) }, sub = "Before they're due")
                        MitraSwitchRow("Daily digest", n.digest, { onAction(OnboardingAction.Notif(n.copy(digest = it))) }, sub = "8:00 AM summary instead of pings")
                    }
                }
                else -> {
                    Heading("All set, $firstName.", "Today shows ${state.picked.size} modules. Quick add is always one tap away.")
                    MitraCard(Modifier.fillMaxWidth()) {
                        Text("✓ " + OnbArea.entries.filter { it in state.picked }.joinToString(", ") { it.title }, style = Mitra.type.sm, color = c.text)
                        Text("✦ First capture: ${state.preview ?: "skipped"}", style = Mitra.type.sm, color = c.text)
                        Text("🔔 ${state.notif.on} notification types on", style = Mitra.type.sm, color = c.text)
                    }
                }
            }
            state.error?.let { MitraBanner(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.step > 1) MitraButton("Back", onClick = { onAction(OnboardingAction.Back) }, kind = ButtonKind.Outline)
            MitraButton(
                if (state.step < 4) "Continue" else "Open Today", onClick = { onAction(OnboardingAction.Next) },
                modifier = Modifier.weight(1f), loading = state.saving,
            )
        }
    }
}

@Composable
private fun Heading(title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = Mitra.type.h1, color = Mitra.colors.text)
        Text(sub, style = Mitra.type.body, color = Mitra.colors.muted)
    }
}
