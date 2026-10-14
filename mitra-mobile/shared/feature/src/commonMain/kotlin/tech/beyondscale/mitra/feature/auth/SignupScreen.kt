package tech.beyondscale.mitra.feature.auth

import androidx.compose.ui.autofill.ContentType
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
fun SignupRoute(onSignIn: () -> Unit, vm: SignupViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SignupScreen(state, vm::onAction, onSignIn)
}

@Composable
fun SignupScreen(state: SignupUiState, onAction: (SignupAction) -> Unit, onSignIn: () -> Unit) {
    AuthScaffold(title = "Create your account", subtitle = "Tasks, habits, money and more — in one place.") {
        MitraTextField(state.name, { onAction(SignupAction.Name(it)) }, label = "Name", error = state.fieldErrors["name"], contentType = ContentType.PersonFullName)
        MitraTextField(state.email, { onAction(SignupAction.Email(it)) }, label = "Email", error = state.fieldErrors["email"], keyboardType = KeyboardType.Email, contentType = ContentType.EmailAddress)
        MitraTextField(
            state.password, { onAction(SignupAction.Password(it)) }, label = "Password", error = state.fieldErrors["password"],
            isPassword = true, imeAction = ImeAction.Done, onImeAction = { onAction(SignupAction.Submit) }, contentType = ContentType.NewPassword,
        )
        state.error?.let { MitraBanner(it) }
        MitraButton("Create account", onClick = { onAction(SignupAction.Submit) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg, loading = state.loading)
        MitraButton("I already have an account", onClick = onSignIn, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}
