package tech.beyondscale.mitra.android.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
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
 * The alarm is only armed while at least one Tasks or Money widget is placed; each receiver's onDisabled re-checks.
 */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Companion.get<AppScope>().scope.launch {
            try {
                TasksWidget().updateAll(app)
            } finally {
                try {
                    // Safe to spend per day changes with the day (fewer days left).
                    MoneyWidget().updateAll(app)
                } finally {
                    try {
                        scheduleIfPlaced(app)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object : KoinComponent {
        const val ACTION = "tech.beyondscale.mitra.WIDGET_MIDNIGHT"

        /** Arms the next local-midnight alarm (replacing any earlier one). */
        fun schedule(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.RTC, get<AppClock>().nextMidnightEpochMs() + 60_000, pendingIntent(context))
        }

        /** Arms the alarm when a Tasks or Money widget is on the home screen, cancels it otherwise. */
        suspend fun scheduleIfPlaced(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val placed = manager.getGlanceIds(TasksWidget::class.java).isNotEmpty() ||
                manager.getGlanceIds(MoneyWidget::class.java).isNotEmpty()
            if (placed) schedule(context) else cancel(context)
        }

        /** [scheduleIfPlaced] from a receiver callback (not suspending), on the app scope. */
        fun rescheduleAsync(context: Context) {
            val app = context.applicationContext
            get<AppScope>().scope.launch { scheduleIfPlaced(app) }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, MidnightReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
