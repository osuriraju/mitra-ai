package tech.beyondscale.mitra.android.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.nextMidnightEpochMs
import tech.beyondscale.mitra.domain.notify.Reminder
import tech.beyondscale.mitra.domain.notify.ReminderInputs
import tech.beyondscale.mitra.domain.notify.computeReminders
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository

/**
 * Arms one AlarmManager alarm per upcoming [Reminder] (next 48 h) and a daily refresh, re-arming whenever local data,
 * the signed-in user's settings, the notification switches or the day change (debounced). Sign-out cancels every
 * alarm and clears posted reminders. Exact alarms when the user allows them, inexact (may be minutes late) otherwise.
 * [ReminderReceiver] posts the notification and re-checks at fire time that it still applies.
 */
class ReminderScheduler(
    private val context: Context,
    private val tasks: TasksRepository,
    private val habits: HabitsRepository,
    private val money: MoneyRepository,
    private val auth: AuthRepository,
    private val prefs: PreferencesRepository,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val store get() = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    /** Local data as reminder inputs (readable without the UI having run, like the widgets). */
    fun inputs(): Flow<ReminderInputs> = combine(
        combine(tasks.tasks, habits.habits, money.recurring, money.subscriptions) { t, h, r, s -> ReminderInputs(t, h, r, s) },
        auth.localUser,
        prefs.notificationPrefs,
    ) { base, user, p -> base.copy(settings = user?.settings, prefs = p) }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(inputs(), clock.dayFlow()) { i, _ -> i }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { apply(it) }
        }
    }

    /** One-off re-arm from current data (boot, time-zone change, daily refresh, exact-alarm permission change). */
    suspend fun rescheduleNow() = apply(inputs().first())

    private suspend fun apply(inputs: ReminderInputs) = lock.withLock {
        val reminders = computeReminders(clock.now(), clock.zone, inputs)
        val previous = store.getStringSet(KEYS, emptySet()).orEmpty()
        val next = reminders.map { it.key }.toSet()
        for (key in previous - next) alarms.cancel(ReminderReceiver.firePendingIntent(context, key, null))
        for (r in reminders) arm(r.at.toEpochMilliseconds(), ReminderReceiver.firePendingIntent(context, r.key, r))
        store.edit().putStringSet(KEYS, next).apply()
        if (inputs.settings == null) {
            alarms.cancel(refreshPendingIntent(context))
            NotificationManagerCompat.from(context).cancelAll()
        } else {
            // Inexact is fine: it only re-arms the next 48 h.
            alarms.set(AlarmManager.RTC, clock.nextMidnightEpochMs() + REFRESH_OFFSET_MS, refreshPendingIntent(context))
        }
    }

    /** Exact while-idle alarm when allowed (Android 12+ may deny SCHEDULE_EXACT_ALARM), inexact otherwise. */
    fun arm(atMs: Long, pi: PendingIntent) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    companion object {
        private const val STORE = "mitra_reminders"
        private const val KEYS = "armed"
        private const val DEBOUNCE_MS = 1_000L
        /** A few minutes after midnight, staggered from the widget's refresh. */
        private const val REFRESH_OFFSET_MS = 5 * 60_000L

        fun refreshPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
