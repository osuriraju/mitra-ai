package tech.beyondscale.mitra.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import tech.beyondscale.mitra.domain.model.ThemeMode

@Composable
fun SettingsRoute(vm: SettingsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(state, vm::onAction)
}

@Composable
fun SettingsScreen(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.pending) { if (state.pending == 0L) confirmSignOut = false }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MitraDimens.pad),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        MitraCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.initials, size = 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(state.name, style = Mitra.type.h3, color = Mitra.colors.text)
                    Text(state.email, style = Mitra.type.sm, color = Mitra.colors.muted)
                }
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
