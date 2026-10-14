package tech.beyondscale.mitra.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun NotificationSettingsRoute(vm: NotificationSettingsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    NotificationSettingsScreen(state, vm::onAction)
}

@Composable
fun NotificationSettingsScreen(state: NotificationSettingsUiState, onAction: (NotificationSettingsAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MitraDimens.pad),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        Text("Only what’s actionable. Reminders come from what is on this phone, even offline.", style = Mitra.type.sm, color = Mitra.colors.muted)
        SectionHeader("Reminders")
        MitraCard {
            MitraSwitchRow("Task reminders", state.prefs.tasks, { onAction(NotificationSettingsAction.SetTasks(it)) }, sub = "At the due time, for tasks with a time")
            MitraSwitchRow("Habit reminders", state.prefs.habits, { onAction(NotificationSettingsAction.SetHabits(it)) }, sub = "At the time you set per habit, unless already done")
            if (state.moneyOn) {
                MitraSwitchRow("Bills & subscriptions", state.prefs.bills, { onAction(NotificationSettingsAction.SetBills(it)) }, sub = "The day before, at 9:00 AM")
            }
        }
        SectionHeader("Quiet hours")
        MitraCard {
            MitraSwitchRow("Quiet hours", state.quietHours, { onAction(NotificationSettingsAction.SetQuietHours(it)) }, sub = "10:30 PM – 7:00 AM · reminders wait until 7:00")
        }
    }
}
