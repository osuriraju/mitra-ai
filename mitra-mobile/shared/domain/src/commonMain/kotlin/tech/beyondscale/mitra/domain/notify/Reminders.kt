package tech.beyondscale.mitra.domain.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.domain.habits.habitDone
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Task
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/* Local reminders, computed from what is on the device (no server push in v1). Pure, so the app shell only arms alarms. */

/** Device-local switches per reminder type (the web's onboarding "Notifications" step). */
data class NotificationPrefs(val tasks: Boolean = true, val habits: Boolean = true, val bills: Boolean = true)

enum class ReminderKind { Task, Habit, Bill, Subscription }

/**
 * One notification to post at [at]. [key] is stable for the same occurrence (kind, entity, date), so rescheduling
 * replaces rather than duplicates. [date] names the occurrence: the habit's day, the bill's due day,
 * a task's "<due>T<time>" (so moving a task's time is a new occurrence).
 */
data class Reminder(
    val key: String,
    val kind: ReminderKind,
    val entityId: String,
    val date: String,
    val at: Instant,
    val title: String,
    val text: String,
)

/** What reminders are computed from; [settings] is null when nobody is signed in on the device (nothing is scheduled). */
data class ReminderInputs(
    val tasks: List<Task> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val recurring: List<Recurring> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val settings: Settings? = null,
    val prefs: NotificationPrefs = NotificationPrefs(),
)

/** Bills and renewals are announced the day before, at this local time. */
val BILL_REMINDER_TIME = LocalTime(9, 0)
/** Web quiet hours: 10:30 PM – 7:00 AM. A reminder inside them is held until 7:00. */
val QUIET_START = LocalTime(22, 30)
val QUIET_END = LocalTime(7, 0)
/** How far ahead alarms are armed; a daily refresh (and every data change) re-arms the rest. */
val REMINDER_HORIZON = 48.hours
/** Keeps well below Android's per-app alarm cap. */
const val MAX_REMINDERS = 64

/** The reminders to arm now: future ones within [REMINDER_HORIZON], soonest first, at most [MAX_REMINDERS]. */
fun computeReminders(now: Instant, zone: TimeZone, inputs: ReminderInputs): List<Reminder> {
    val today = now.toLocalDateTime(zone).date.toString()
    return candidates(inputs, zone, today, addDays(today, 2))
        .filter { it.at > now && it.at <= now + REMINDER_HORIZON }
        .sortedBy { it.at }
        .take(MAX_REMINDERS)
}

/**
 * Whether the reminder [key] (possibly snoozed, so its time may have passed) should still be shown at [now]: the task
 * is still open, the habit not yet done that day, the bill not paid, the type still switched on, the user signed in.
 */
fun isReminderLive(key: String, now: Instant, zone: TimeZone, inputs: ReminderInputs): Boolean {
    val today = now.toLocalDateTime(zone).date.toString()
    return candidates(inputs, zone, addDays(today, -1), addDays(today, 2)).any { it.key == key }
}

/** Every reminder occurrence for local days [from]..[to] (inclusive), quiet hours applied, deduplicated by key. */
private fun candidates(inputs: ReminderInputs, zone: TimeZone, from: String, to: String): List<Reminder> {
    val settings = inputs.settings ?: return emptyList()
    val days = generateSequence(from) { addDays(it, 1) }.takeWhile { it <= to }.toList()
    val out = mutableListOf<Reminder>()
    fun add(kind: ReminderKind, id: String, date: String, local: LocalDateTime, title: String, text: String) {
        val shifted = if (settings.quietHours) outOfQuietHours(local) else local
        out += Reminder("${kind.name.lowercase()}:$id:$date", kind, id, date, shifted.toInstant(zone), title, text)
    }

    if (inputs.prefs.tasks) {
        for (t in inputs.tasks) {
            if (t.done || t.someday) continue
            val due = t.due ?: continue
            val time = parseHm(t.time) ?: continue
            val date = runCatching { LocalDate.parse(due) }.getOrNull() ?: continue
            if (due !in days) continue
            add(ReminderKind.Task, t.id, "${due}T${t.time}", LocalDateTime(date, time), t.title, "Due ${fmtTime(t.time)}")
        }
    }
    if (inputs.prefs.habits) {
        for (h in inputs.habits) {
            if (h.archived) continue
            val time = parseHm(h.reminder) ?: continue
            for (d in days) {
                if (h.days.getOrNull(dowMon(d)) != true || habitDone(h, d)) continue
                add(ReminderKind.Habit, h.id, d, LocalDateTime(LocalDate.parse(d), time), h.name, "Time for ${h.name}")
            }
        }
    }
    if (inputs.prefs.bills && settings.modules.money) {
        for (d in days) {
            val due = addDays(d, 1)
            val at = LocalDateTime(LocalDate.parse(d), BILL_REMINDER_TIME)
            for (r in inputs.recurring) {
                if (r.kind != RecurringKind.Bill || !r.reminder || dayOfMonth(due) != r.day) continue
                if (r.lastPaidOn != null && monthKey(r.lastPaidOn) == monthKey(due)) continue
                add(ReminderKind.Bill, r.id, due, at, "${r.name} due tomorrow", inrPaise(r.amountPaise))
            }
            for (s in inputs.subscriptions) {
                if (s.next != due) continue
                add(ReminderKind.Subscription, s.id, due, at, "${s.name} renews tomorrow", inrPaise(s.amountPaise))
            }
        }
    }
    return out.distinctBy { it.key }
}

/** Holds a time inside quiet hours (22:30 – 7:00) until 7:00. */
fun outOfQuietHours(t: LocalDateTime): LocalDateTime = when {
    t.time >= QUIET_START -> LocalDateTime(LocalDate.parse(addDays(t.date.toString(), 1)), QUIET_END)
    t.time < QUIET_END -> LocalDateTime(t.date, QUIET_END)
    else -> t
}

/** "HH:MM" → time; null for anything else. */
private fun parseHm(s: String?): LocalTime? {
    val parts = s?.split(":")?.takeIf { it.size >= 2 } ?: return null
    val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return LocalTime(h, m)
}
