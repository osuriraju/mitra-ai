package tech.beyondscale.mitra.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.Avatar
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.ModuleKey
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.SettingChange

@Composable
fun SettingsRoute(
    onOpenProfile: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenAiLog: () -> Unit = {},
    vm: SettingsViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(state, vm::onAction, onOpenProfile, onOpenNotifications, onOpenAiLog)
}

private val TODAY_SECTION_LABELS = listOf(
    "priorities" to "Priorities", "activity" to "Activity", "habits" to "Habits", "checkin" to "Check-in", "money" to "Money", "goals" to "Goals",
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenAiLog: () -> Unit = {},
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.pending) { if (state.pending == 0L) confirmSignOut = false }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MitraDimens.pad),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        MitraCard(Modifier.fillMaxWidth().clickable(onClick = onOpenProfile)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.initials, size = 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(state.name, style = Mitra.type.h3, color = Mitra.colors.text)
                    Text(state.email, style = Mitra.type.sm, color = Mitra.colors.muted)
                }
                Spacer(Modifier.weight(1f))
                Text("Profile ›", style = Mitra.type.sm, color = Mitra.colors.accent)
            }
        }

        SectionHeader("Appearance")
        val modes = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.Auto)
        MitraSegmented(listOf("Dark", "Light", "Auto"), modes.indexOf(state.theme), { onAction(SettingsAction.SetTheme(modes[it])) })

        SectionHeader("Modules")
        MitraCard {
            MitraSwitchRow("Money", state.modules.money, { onAction(SettingsAction.SetModule(ModuleKey.Money, it)) }, sub = "Expenses, budgets, bills")
            MitraSwitchRow("Wellness", state.modules.wellness, { onAction(SettingsAction.SetModule(ModuleKey.Wellness, it)) }, sub = "Check-ins, sleep, trends")
            MitraSwitchRow("AI assistant", state.modules.ai, { onAction(SettingsAction.SetModule(ModuleKey.Ai, it)) }, sub = "Ask Mitra, suggestions")
        }

        val st = state.settings
        fun change(c: SettingChange) = onAction(SettingsAction.Change(c))
        SectionHeader("Today screen")
        MitraCard {
            TODAY_SECTION_LABELS.forEach { (key, label) ->
                MitraSwitchRow(label, key in st.todaySections, { on ->
                    change(SettingChange.TodaySections(if (on) st.todaySections + key else st.todaySections - key))
                })
            }
        }
        Text("Start of week", style = Mitra.type.sm, color = Mitra.colors.muted)
        val weeks = listOf("Monday", "Sunday")
        MitraSegmented(weeks, weeks.indexOf(st.weekStart).coerceAtLeast(0), { change(SettingChange.WeekStart(weeks[it])) })

        SectionHeader("Notifications")
        MitraCard {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenNotifications).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reminders by module", style = Mitra.type.bodyBold, color = Mitra.colors.text)
                    Text("Tasks, habits, bills · quiet hours", style = Mitra.type.xs, color = Mitra.colors.muted)
                }
                Text("›", style = Mitra.type.h3, color = Mitra.colors.faint)
            }
            MitraSwitchRow("Daily digest", st.digest, { change(SettingChange.Digest(it)) }, sub = "8:00 AM · summary instead of individual pings")
        }

        if (state.modules.ai) {
            SectionHeader("AI & privacy")
            Text("Auto-save AI captures", style = Mitra.type.sm, color = Mitra.colors.muted)
            val saves = listOf("Always ask", "Small only", "Never")
            MitraSegmented(saves, saves.indexOf(st.autoSave).coerceAtLeast(0), { change(SettingChange.AutoSave(saves[it])) })
            MitraCard {
                Row(Modifier.fillMaxWidth().clickable(onClick = onOpenAiLog).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI activity log", Modifier.weight(1f), style = Mitra.type.bodyBold, color = Mitra.colors.text)
                    Text("›", style = Mitra.type.h3, color = Mitra.colors.faint)
                }
            }
        }
        Text("Focus session length (min) · used when a task has no estimate", style = Mitra.type.sm, color = Mitra.colors.muted)
        val focus = listOf(15, 25, 45)
        MitraSegmented(focus.map { "$it" }, focus.indexOf(st.focusMinutes).coerceAtLeast(0), { change(SettingChange.FocusMinutes(focus[it])) })

        SectionHeader("Sync")
        Text(
            if (state.pending == 0L) "All changes are synced." else "${state.pending} change${if (state.pending == 1L) "" else "s"} waiting to sync.",
            style = Mitra.type.sm, color = Mitra.colors.muted,
        )

        MitraButton(
            "Sign out", icon = MitraIcons.Logout, kind = ButtonKind.Danger, loading = state.signingOut,
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (state.pending > 0) confirmSignOut = true else onAction(SettingsAction.SignOut) },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out now?") },
            text = { Text("${state.pending} change${if (state.pending == 1L) " hasn't" else "s haven't"} synced yet and will be lost. Connect to the internet first to keep them.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onAction(SettingsAction.SignOut) }) { Text("Sign out", color = Mitra.colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
            containerColor = Mitra.colors.surface,
        )
    }
}
