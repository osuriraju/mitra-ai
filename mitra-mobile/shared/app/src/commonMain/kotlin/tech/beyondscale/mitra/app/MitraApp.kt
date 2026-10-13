package tech.beyondscale.mitra.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.MitraMark
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraTheme
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.feature.auth.ForgotPasswordRoute
import tech.beyondscale.mitra.feature.auth.LoginRoute
import tech.beyondscale.mitra.feature.auth.SignupRoute

/** Root of the app on every platform. [onDarkTheme] lets the host style system bars. */
@Composable
fun MitraApp(onDarkTheme: (Boolean) -> Unit = {}, vm: RootViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dark = when (state.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.Auto -> isSystemInDarkTheme()
    }
    LaunchedEffect(dark) { onDarkTheme(dark) }

    MitraTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(Mitra.colors.bg)) {
            when (val s = state.session) {
                SessionState.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MitraMark() }
                SessionState.SignedOut -> AuthNav()
                is SessionState.SignedIn -> MainShell(s.user, state.sync, vm.messages)
            }
        }
    }
}

@Composable
private fun AuthNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = LoginDest) {
        composable<LoginDest> { LoginRoute(onSignup = { nav.navigate(SignupDest) }, onForgot = { nav.navigate(ForgotDest) }) }
        composable<SignupDest> { SignupRoute(onSignIn = { nav.popBackStack() }) }
        composable<ForgotDest> { ForgotPasswordRoute(onBack = { nav.popBackStack() }) }
    }
}
