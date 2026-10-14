package tech.beyondscale.mitra.android.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.nextMidnightEpochMs
import tech.beyondscale.mitra.data.di.AppScope

/**
 * Redraws the widget when the local date rolls over (or the clock / time zone changes) even if the app process is
 * not running, then arms the next midnight. Inexact RTC alarm: no exact-alarm permission, no device wake-up.
 */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Companion.get<AppScope>().scope.launch {
            try {
                TasksWidget().updateAll(app)
            } finally {
                schedule(app)
                pending.finish()
            }
        }
    }

    companion object : KoinComponent {
        const val ACTION = "tech.beyondscale.mitra.WIDGET_MIDNIGHT"

        fun schedule(context: Context) {
            val intent = Intent(context, MidnightReceiver::class.java).setAction(ACTION)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.RTC, get<AppClock>().nextMidnightEpochMs() + 60_000, pi)
        }
    }
}
