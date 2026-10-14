package tech.beyondscale.mitra.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Widget / shortcut deep links (mitra://…) and shares. Not on recreation: the intent was already handled before the config change.
        if (savedInstanceState == null) launches.submit(intent.launchTarget())
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

    override fun onStart() {
        super.onStart()
        sync.requestSync()
        refresher.onForeground()
    }
}
