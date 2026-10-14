package tech.beyondscale.mitra.feature.auth

import androidx.compose.ui.autofill.ContentType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraTextField

@Composable
fun LoginRoute(onSignup: () -> Unit, onForgot: () -> Unit, vm: LoginViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LoginScreen(state, vm::onAction, onSignup, onForgot)
}

@Composable
fun LoginScreen(state: LoginUiState, onAction: (LoginAction) -> Unit, onSignup: () -> Unit, onForgot: () -> Unit) {
    AuthScaffold(title = "Welcome back", subtitle = "Sign in to your Mitra account.") {
        MitraTextField(
            value = state.identifier, onValueChange = { onAction(LoginAction.Identifier(it)) },
            label = "Email or username", error = state.fieldErrors["identifier"], keyboardType = KeyboardType.Email,
            contentType = ContentType.Username + ContentType.EmailAddress,
        )
        MitraTextField(
            value = state.password, onValueChange = { onAction(LoginAction.Password(it)) },
            label = "Password", error = state.fieldErrors["password"], isPassword = true, contentType = ContentType.Password,
            imeAction = ImeAction.Done, onImeAction = { onAction(LoginAction.Submit) },
        )
        state.error?.let { MitraBanner(it) }
        MitraButton("Sign in", onClick = { onAction(LoginAction.Submit) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg, loading = state.loading)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MitraButton("Forgot password?", onClick = onForgot, kind = ButtonKind.Ghost)
            MitraButton("Create account", onClick = onSignup, kind = ButtonKind.Ghost)
        }
    }
}
