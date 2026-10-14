package tech.beyondscale.mitra.feature.auth

import androidx.compose.ui.autofill.ContentType
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.theme.Mitra

@Composable
fun ForgotPasswordRoute(onBack: () -> Unit, vm: ForgotPasswordViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ForgotPasswordScreen(state, vm::onAction, onBack)
}

@Composable
fun ForgotPasswordScreen(state: ForgotUiState, onAction: (ForgotAction) -> Unit, onBack: () -> Unit) {
    AuthScaffold(title = "Reset your password", subtitle = "We'll email you a link to choose a new one.") {
        if (state.sent) {
            MitraCard(tone = CardTone.Accent) {
                Text("Check your inbox", style = Mitra.type.h3, color = Mitra.colors.text)
                Text(
                    "If an account exists for ${state.email.trim()}, a reset link is on its way. Open it on any device to set a new password, then sign in here.",
                    style = Mitra.type.sm, color = Mitra.colors.muted,
                )
            }
        } else {
            MitraTextField(
                state.email, { onAction(ForgotAction.Email(it)) }, label = "Email", error = state.fieldError,
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, onImeAction = { onAction(ForgotAction.Submit) },
                contentType = ContentType.EmailAddress,
            )
            state.error?.let { MitraBanner(it) }
            MitraButton("Send reset link", onClick = { onAction(ForgotAction.Submit) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg, loading = state.loading)
        }
        MitraButton("Back to sign in", onClick = onBack, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}
