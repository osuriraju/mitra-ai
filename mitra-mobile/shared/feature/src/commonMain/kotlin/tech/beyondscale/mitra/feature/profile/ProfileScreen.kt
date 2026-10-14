package tech.beyondscale.mitra.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.Avatar
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.LocalSnackbar
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.StatCard
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.feature.common.LocalFileSaver
import tech.beyondscale.mitra.feature.common.SaveResult

@Composable
fun ProfileRoute(onBack: () -> Unit, vm: ProfileViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val saver = LocalFileSaver.current
    var open by rememberSaveable { mutableStateOf<ProfileForm?>(null) }
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is ProfileEvent.Done -> { if (open == e.form) open = null; snackbar.show(e.message) }
                is ProfileEvent.SaveFile -> when (saver.save(e.fileName, "application/json", e.json)) {
                    SaveResult.Saved -> snackbar.show("Export saved (JSON)")
                    SaveResult.Failed -> snackbar.show("Couldn't save the export")
                    SaveResult.Unsupported -> snackbar.show("Saving files isn't supported here")
                    SaveResult.Cancelled -> Unit
                }
            }
        }
    }
    ProfileScreen(state, vm::onAction, onBack, open) { open = it; vm.onAction(ProfileAction.ClearError) }
}

@Composable
fun ProfileScreen(state: ProfileUiState, onAction: (ProfileAction) -> Unit, onBack: () -> Unit, open: ProfileForm?, onOpen: (ProfileForm?) -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Profile", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            MitraCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(state.initials, size = 52.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(state.name, style = Mitra.type.h3, color = c.text)
                        Text(state.email, style = Mitra.type.sm, color = c.muted)
                        if (state.memberSince.isNotEmpty()) Text("Member since ${state.memberSince}", style = Mitra.type.xs, color = c.muted)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MitraDimens.gap)) {
                StatCard("Tasks done", "${state.tasksDone}", "all time", Modifier.weight(1f))
                StatCard("Best streak", "${state.bestStreak} days", "current", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MitraDimens.gap)) {
                StatCard("Goals active", "${state.goalsActive}", "", Modifier.weight(1f))
                if (state.moneyOn) StatCard("Transactions", "${state.transactions}", "on this device", Modifier.weight(1f))
            }
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                SetRow(MitraIcons.User, "Personal details", state.name + if (state.phone.isNotEmpty()) " · ${state.phone}" else "") { onOpen(ProfileForm.Details) }
                HorizontalDivider(color = c.border)
                SetRow(MitraIcons.Lock, "Password", "Change your password") { onOpen(ProfileForm.Password) }
                HorizontalDivider(color = c.border)
                SetRow(MitraIcons.Download, "Export my data", if (state.busy == ProfileForm.Export) "Preparing…" else "JSON, includes all modules") { onAction(ProfileAction.Export) }
                HorizontalDivider(color = c.border)
                SetRow(MitraIcons.Trash, "Delete account", "Permanent. 30-day grace period.", danger = true) { onOpen(ProfileForm.Delete) }
            }
        }
    }

    val err = state.error?.takeIf { it.first == open }?.second
    val busy = state.busy != null && state.busy == open
    when (open) {
        ProfileForm.Details -> {
            var name by rememberSaveable { mutableStateOf(state.name) }
            var email by rememberSaveable { mutableStateOf(state.email) }
            var phone by rememberSaveable { mutableStateOf(state.phone) }
            FormDialog("Personal details", if (busy) "Saving…" else "Save", onDismiss = { onOpen(null) }, onConfirm = { onAction(ProfileAction.SaveDetails(name, email, phone)) }) {
                MitraTextField(name, { name = it }, "Full name")
                MitraTextField(email, { email = it }, "Email", error = err)
                MitraTextField(phone, { phone = it }, "Phone", placeholder = "Optional")
            }
        }
        ProfileForm.Password -> {
            var cur by rememberSaveable { mutableStateOf("") }
            var new by rememberSaveable { mutableStateOf("") }
            FormDialog("Change password", if (busy) "Saving…" else "Change password", onDismiss = { onOpen(null) }, onConfirm = { onAction(ProfileAction.ChangePassword(cur, new)) }) {
                MitraTextField(cur, { cur = it }, "Current password", isPassword = true)
                MitraTextField(new, { new = it }, "New password", isPassword = true, placeholder = "At least 8 characters", error = err)
                Text("Changing it signs you out everywhere else.", style = Mitra.type.xs, color = c.muted)
            }
        }
        ProfileForm.Delete -> {
            var confirm by rememberSaveable { mutableStateOf("") }
            var pw by rememberSaveable { mutableStateOf("") }
            val mismatch = confirm.isNotEmpty() && confirm.trim() != state.email
            FormDialog(
                "Delete account", if (busy) "Deleting…" else "Delete permanently", danger = true,
                enabled = !mismatch && confirm.isNotEmpty() && pw.isNotEmpty(),
                onDismiss = { onOpen(null) }, onConfirm = { onAction(ProfileAction.Delete(confirm, pw)) },
            ) {
                MitraBanner("This removes everything after a 30-day grace period. Export first if you want a copy. Signing in again within 30 days cancels it.")
                MitraTextField(confirm, { confirm = it }, "Type your email (${state.email}) to confirm", error = if (mismatch) "Does not match" else null)
                MitraTextField(pw, { pw = it }, "Your password", isPassword = true, error = err)
            }
        }
        ProfileForm.Export, null -> Unit
    }
}

@Composable
private fun FormDialog(
    title: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit,
    danger: Boolean = false, enabled: Boolean = true, content: @Composable () -> Unit,
) {
    val c = Mitra.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Mitra.type.h3, color = c.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirmLabel, color = if (!enabled) c.faint else if (danger) c.danger else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        containerColor = c.surface,
    )
}

@Composable
private fun SetRow(icon: ImageVector, title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Mitra.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBox(icon, if (danger) Tone.Danger else Tone.Neutral, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = Mitra.type.bodyBold, color = if (danger) c.danger else c.text)
            if (sub.isNotEmpty()) Text(sub, style = Mitra.type.xs, color = c.muted)
        }
        Icon(MitraIcons.ChevronRight, contentDescription = null, tint = c.faint)
    }
}
