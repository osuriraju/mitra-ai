package tech.beyondscale.mitra.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tech.beyondscale.mitra.android.MainActivity
import tech.beyondscale.mitra.android.R
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.data.di.AppScope
import tech.beyondscale.mitra.domain.habits.habitComplete
import tech.beyondscale.mitra.domain.habits.habitValue
import tech.beyondscale.mitra.domain.notify.Reminder
import tech.beyondscale.mitra.domain.notify.ReminderKind
import tech.beyondscale.mitra.domain.notify.isReminderLive
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.complete
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Posts due reminders and handles their actions — Complete (task done / habit done for its day / bill marked paid,
 * written locally and queued for sync like any in-app change) and Snooze (10 min; 1 h for bills). Also re-arms all
 * reminders on boot, app update, clock or time-zone change, exact-alarm permission change and the daily refresh.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        get<AppScope>().scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> intent.reminder()?.let { show(app, it) }
                    ACTION_COMPLETE -> intent.reminder()?.let { complete(app, it) }
                    ACTION_SNOOZE -> intent.reminder()?.let { snooze(app, it) }
                    else -> scheduler(app).rescheduleNow()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun show(context: Context, r: Reminder) {
        val clock = get<AppClock>()
        // Still relevant? (done since, switched off, signed out, time moved…)
        if (!isReminderLive(r.key, clock.now(), clock.zone, scheduler(context).inputs().first())) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(deepLink(r)?.let(Uri::parse))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val builder = NotificationCompat.Builder(context, channelOf(r.kind))
            .setSmallIcon(R.drawable.ic_stat_mitra)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, r.key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        completeLabel(r.kind)?.let { builder.addAction(0, it, actionIntent(context, ACTION_COMPLETE, r)) }
        builder.addAction(0, if (r.kind.isMoney()) "Snooze 1 h" else "Snooze 10 min", actionIntent(context, ACTION_SNOOZE, r))
        try {
            nm.notify(r.key.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post.
        }
    }

    private suspend fun complete(context: Context, r: Reminder) {
        NotificationManagerCompat.from(context).cancel(r.key.hashCode())
        when (r.kind) {
            ReminderKind.Task -> get<TasksRepository>().complete(r.entityId)
            ReminderKind.Habit -> {
                val repo = get<HabitsRepository>()
                val h = repo.habits.first().firstOrNull { it.id == r.entityId } ?: return
                val tap = habitComplete(h, r.date)
                if (tap.value != habitValue(h, r.date)) repo.setLog(h.id, r.date, tap.value)
            }
            ReminderKind.Bill -> get<MoneyRepository>().payRecurring(r.entityId)
            ReminderKind.Subscription -> Unit
        }
    }

    private fun snooze(context: Context, r: Reminder) {
        NotificationManagerCompat.from(context).cancel(r.key.hashCode())
        val delay = if (r.kind.isMoney()) 1.hours else 10.minutes
        val at = get<AppClock>().now() + delay
        // Its own PendingIntent identity, so re-arming the schedule (which drops past reminders) never cancels it.
        scheduler(context).arm(at.toEpochMilliseconds(), firePendingIntent(context, r.key, r, snoozed = true))
    }

    companion object : KoinComponent {
        const val ACTION_FIRE = "tech.beyondscale.mitra.REMINDER_FIRE"
        const val ACTION_COMPLETE = "tech.beyondscale.mitra.REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "tech.beyondscale.mitra.REMINDER_SNOOZE"
        const val ACTION_REFRESH = "tech.beyondscale.mitra.REMINDER_REFRESH"

        private const val CH_TASKS = "tasks"
        private const val CH_HABITS = "habits"
        private const val CH_BILLS = "bills"

        @Volatile private var instance: ReminderScheduler? = null

        /** The process-wide scheduler (one lock around the armed-alarm bookkeeping). */
        fun scheduler(context: Context): ReminderScheduler = instance ?: synchronized(this) {
            instance ?: ReminderScheduler(context.applicationContext, get(), get(), get(), get(), get(), get(), get<AppScope>().scope).also { instance = it }
        }

        /** Alarm for [key]; extras are ignored for matching, so [r] may be null when cancelling. */
        fun firePendingIntent(context: Context, key: String, r: Reminder?, snoozed: Boolean = false): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.parse("mitra-reminder://${if (snoozed) "snooze" else "due"}/${Uri.encode(key)}"))
            r?.let { intent.putReminder(it) }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun actionIntent(context: Context, action: String, r: Reminder): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
                .setData(Uri.parse("mitra-reminder://action/${Uri.encode(r.key)}"))
                .putReminder(r)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CH_TASKS, "Tasks", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Tasks due at a set time" },
                    NotificationChannel(CH_HABITS, "Habits", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Habit nudges at the time you set" },
                    NotificationChannel(CH_BILLS, "Bills", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Bills and renewals due tomorrow" },
                ),
            )
        }

        private fun channelOf(kind: ReminderKind) = when (kind) {
            ReminderKind.Task -> CH_TASKS
            ReminderKind.Habit -> CH_HABITS
            ReminderKind.Bill, ReminderKind.Subscription -> CH_BILLS
        }

        private fun completeLabel(kind: ReminderKind): String? = when (kind) {
            ReminderKind.Task -> "Complete"
            ReminderKind.Habit -> "Done"
            ReminderKind.Bill -> "Mark paid"
            ReminderKind.Subscription -> null
        }

        private fun ReminderKind.isMoney() = this == ReminderKind.Bill || this == ReminderKind.Subscription

        private fun deepLink(r: Reminder): String? = when (r.kind) {
            ReminderKind.Task -> "mitra://task/${r.entityId}"
            ReminderKind.Bill, ReminderKind.Subscription -> "mitra://money"
            ReminderKind.Habit -> null
        }

        private fun Intent.putReminder(r: Reminder): Intent = putExtra("key", r.key).putExtra("kind", r.kind.name).putExtra("id", r.entityId)
            .putExtra("date", r.date).putExtra("at", r.at.toEpochMilliseconds()).putExtra("title", r.title).putExtra("text", r.text)

        private fun Intent.reminder(): Reminder? {
            val key = getStringExtra("key") ?: return null
            val kind = getStringExtra("kind")?.let { k -> ReminderKind.entries.firstOrNull { it.name == k } } ?: return null
            return Reminder(
                key, kind, getStringExtra("id") ?: return null, getStringExtra("date").orEmpty(),
                kotlin.time.Instant.fromEpochMilliseconds(getLongExtra("at", 0)), getStringExtra("title").orEmpty(), getStringExtra("text").orEmpty(),
            )
        }
    }
}
