package tech.beyondscale.mitra.domain.notify

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import tech.beyondscale.mitra.domain.habits.habit
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import tech.beyondscale.mitra.domain.money.bill
import tech.beyondscale.mitra.domain.money.sub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemindersTest {
    private val ist = TimeZone.of("Asia/Kolkata")
    private fun at(s: String, zone: TimeZone = ist) = LocalDateTime.parse(s).toInstant(zone)
    /** Wednesday 14 Oct 2026, 10:00 IST. */
    private val now = at("2026-10-14T10:00")
    private val noQuiet = Settings(quietHours = false)

    private fun keys(inputs: ReminderInputs, n: kotlin.time.Instant = now, zone: TimeZone = ist) = computeReminders(n, zone, inputs).map { it.key }

    @Test fun `only open tasks with a due date and time in the future are reminded`() {
        val tasks = listOf(
            Task("a", "Call bank", due = "2026-10-14", time = "17:00"),
            Task("b", "No time", due = "2026-10-14"),
            Task("c", "Done", due = "2026-10-14", time = "17:00", done = true),
            Task("d", "Earlier today", due = "2026-10-14", time = "09:00"),
            Task("e", "Tomorrow", due = "2026-10-15", time = "08:30"),
            Task("f", "Too far", due = "2026-10-20", time = "08:30"),
            Task("g", "Bad time", due = "2026-10-14", time = "25:00"),
        )
        val r = computeReminders(now, ist, ReminderInputs(tasks = tasks, settings = noQuiet))
        assertEquals(listOf("task:a:2026-10-14T17:00", "task:e:2026-10-15T08:30"), r.map { it.key })
        assertEquals(at("2026-10-14T17:00"), r[0].at)
        assertEquals("Call bank", r[0].title)
        assertEquals("Due 5:00 PM", r[0].text)
    }

    @Test fun `times are local to the given zone`() {
        val tasks = listOf(Task("a", "x", due = "2026-10-14", time = "17:00"))
        val utc = TimeZone.UTC
        assertEquals(at("2026-10-14T17:00", utc), computeReminders(now, utc, ReminderInputs(tasks = tasks, settings = noQuiet)).single().at)
    }

    @Test fun `habit nudges on scheduled days, skipping a day already done and archived habits`() {
        val h = habit("h", "Walk", days = WEEKDAYS).copy(reminder = "18:00")
        // Wed 14 done already; Thu 15 open; Fri 16 is beyond the 48h horizon.
        val done = h.copy(logs = mapOf("2026-10-14" to 1))
        assertEquals(listOf("habit:h:2026-10-14", "habit:h:2026-10-15"), keys(ReminderInputs(habits = listOf(h), settings = noQuiet)))
        assertEquals(listOf("habit:h:2026-10-15"), keys(ReminderInputs(habits = listOf(done), settings = noQuiet)))
        assertEquals(emptyList(), keys(ReminderInputs(habits = listOf(h.copy(archived = true)), settings = noQuiet)))
        assertEquals(emptyList(), keys(ReminderInputs(habits = listOf(h.copy(reminder = null)), settings = noQuiet)))
        // Friday evening: Saturday and Sunday are rest days.
        assertEquals(emptyList(), keys(ReminderInputs(habits = listOf(h), settings = noQuiet), n = at("2026-10-16T19:00")))
    }

    @Test fun `bills and renewals are announced the day before at 9`() {
        val inputs = ReminderInputs(
            recurring = listOf(bill("rent", day = 16), bill("paid", day = 16, lastPaidOn = "2026-10-02"), bill("later", day = 25), bill("off", day = 16).copy(reminder = false)),
            subscriptions = listOf(sub("nf", next = "2026-10-16")),
            settings = noQuiet,
        )
        val r = computeReminders(now, ist, inputs)
        assertEquals(listOf("bill:rent:2026-10-16", "subscription:nf:2026-10-16"), r.map { it.key })
        assertEquals(at("2026-10-15T09:00"), r[1].at)
        assertEquals("Rent due tomorrow", r[0].title)
        assertEquals("₹15,000", r[0].text)
    }

    @Test fun `bills follow the money module and every type follows its switch`() {
        val inputs = ReminderInputs(
            tasks = listOf(Task("a", "x", due = "2026-10-14", time = "17:00")),
            habits = listOf(habit("h").copy(reminder = "18:00")),
            recurring = listOf(bill("rent", day = 16)),
            settings = noQuiet,
        )
        assertEquals(4, keys(inputs).size)
        assertTrue(keys(inputs.copy(settings = noQuiet.copy(modules = Modules(money = false)))).none { it.startsWith("bill") })
        assertTrue(keys(inputs.copy(prefs = NotificationPrefs(tasks = false))).none { it.startsWith("task") })
        assertTrue(keys(inputs.copy(prefs = NotificationPrefs(habits = false))).none { it.startsWith("habit") })
        assertTrue(keys(inputs.copy(prefs = NotificationPrefs(bills = false))).none { it.startsWith("bill") })
        assertEquals(emptyList(), keys(inputs.copy(settings = null)), "signed out")
    }

    @Test fun `quiet hours hold reminders until 7`() {
        val tasks = listOf(Task("late", "x", due = "2026-10-14", time = "23:00"), Task("early", "y", due = "2026-10-15", time = "06:00"))
        val r = computeReminders(now, ist, ReminderInputs(tasks = tasks, settings = Settings(quietHours = true)))
        assertEquals(listOf(at("2026-10-15T07:00"), at("2026-10-15T07:00")), r.map { it.at })
        assertEquals(2, r.map { it.key }.distinct().size)
    }

    @Test fun `duplicate ids yield one reminder`() {
        val t = Task("a", "x", due = "2026-10-14", time = "17:00")
        assertEquals(1, keys(ReminderInputs(tasks = listOf(t, t), settings = noQuiet)).size)
    }

    @Test fun `a snoozed reminder stays live until its item is done`() {
        val t = Task("a", "x", due = "2026-10-14", time = "09:00")
        val inputs = ReminderInputs(tasks = listOf(t), settings = noQuiet)
        val key = "task:a:2026-10-14T09:00"
        assertTrue(isReminderLive(key, now, ist, inputs))
        assertFalse(isReminderLive(key, now, ist, inputs.copy(tasks = listOf(t.copy(done = true)))))
        assertFalse(isReminderLive(key, now, ist, inputs.copy(settings = null)))
        assertFalse(isReminderLive("task:a:2026-10-14T10:00", now, ist, inputs), "time was moved")
    }
}
