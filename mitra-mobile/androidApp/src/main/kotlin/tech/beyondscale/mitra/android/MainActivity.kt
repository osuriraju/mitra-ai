package tech.beyondscale.mitra.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.domain.repository.AuthRepository
import org.koin.android.ext.android.inject
import tech.beyondscale.mitra.app.LaunchRequests
import tech.beyondscale.mitra.app.MitraApp
import tech.beyondscale.mitra.app.LaunchTarget
import tech.beyondscale.mitra.app.parseLaunchUri
import tech.beyondscale.mitra.app.sharedNoteTarget
import tech.beyondscale.mitra.domain.repository.ModuleRefresher
import tech.beyondscale.mitra.domain.repository.SyncController

class MainActivity : ComponentActivity() {
    private val sync: SyncController by inject()
    private val refresher: ModuleRefresher by inject()
    private val launches: LaunchRequests by inject()
    private val auth: AuthRepository by inject()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Widget / shortcut deep links (mitra://…) and shares. Not on recreation: the intent was already handled before the config change.
        if (savedInstanceState == null) launches.submit(intent.launchTarget())
        askForNotificationsOnceSignedIn()
        enableEdgeToEdge()
        setContent {
            MitraApp(onDarkTheme = { dark ->
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launches.submit(intent.launchTarget())
    }

    /** A share (text / URL → Quick Add Note) or a `mitra://` deep link. */
    private fun Intent.launchTarget(): LaunchTarget? =
        if (action == Intent.ACTION_SEND && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            sharedNoteTarget(getStringExtra(Intent.EXTRA_SUBJECT), getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        } else {
            parseLaunchUri(launchUri())
        }

    /** A relaunch from Recents replays the original intent; its deep link was already handled, so it is ignored. */
    private fun Intent.launchUri(): String? =
        if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null else dataString

    /** Android 13+: ask once, the first time someone is signed in (reminders need it); never again after an answer. */
    private fun askForNotificationsOnceSignedIn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val store = getSharedPreferences("mitra_reminders", MODE_PRIVATE)
        if (store.getBoolean("askedPermission", false)) return
        lifecycleScope.launch {
            auth.hasLocalAccount.first { it }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@launch
            store.edit().putBoolean("askedPermission", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        sync.requestSync()
        refresher.onForeground()
    }
}
