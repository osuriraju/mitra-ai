package tech.beyondscale.mitra.data.local

import app.cash.turbine.test
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.data.db.Accounts
import tech.beyondscale.mitra.data.db.Activity
import tech.beyondscale.mitra.data.db.Ai_actions
import tech.beyondscale.mitra.data.db.Ai_messages
import tech.beyondscale.mitra.data.db.Categories
import tech.beyondscale.mitra.data.db.Goal_progress
import tech.beyondscale.mitra.data.db.Goals
import tech.beyondscale.mitra.data.db.Habit_logs
import tech.beyondscale.mitra.data.db.Habits
import tech.beyondscale.mitra.data.db.Milestones
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.db.Notes
import tech.beyondscale.mitra.data.db.Recurring
import tech.beyondscale.mitra.data.db.Subscriptions
import tech.beyondscale.mitra.data.db.Suggestions
import tech.beyondscale.mitra.data.db.Transactions
import tech.beyondscale.mitra.data.db.Projects
import tech.beyondscale.mitra.data.db.Tasks
import tech.beyondscale.mitra.data.db.Wellness_entries
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.data.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalStoreTest {
    private val db = MitraDb(testDriver())

    @Test fun prefStoreRoundTripsAndObserves() = runTest {
        val prefs = PrefStore(db, UnconfinedTestDispatcher(testScheduler))
        prefs.observe(PrefKeys.THEME).test {
            assertNull(awaitItem())
            prefs.put(PrefKeys.THEME, "Light")
            assertEquals("Light", awaitItem())
            prefs.remove(PrefKeys.THEME)
            assertNull(awaitItem())
        }
    }

    @Test fun clearUserDataKeepsDevicePrefs() {
        val prefs = PrefStore(db, UnconfinedTestDispatcher())
        prefs.put(PrefKeys.THEME, "Light")
        db.userQueries.upsert("u1", "{}")
        db.outboxQueries.insert("o1", "user-settings", "PATCH", "users/me/settings", "{}", null, 0, null)
        db.tasksQueries.upsert(Tasks("t1", "Write", null, null, null, null, null, null, null, "todo", 0, null, null, "", null, null, 0, "user", ""))
        db.projectsQueries.upsert(Projects("p1", "Work", "💼", null, ""))

        LocalData(db).clearUserData()

        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertEquals(0L, db.outboxQueries.count().executeAsOne())
        assertEquals(emptyList(), db.tasksQueries.selectIds().executeAsList())
        assertEquals(emptyList(), db.projectsQueries.selectIds().executeAsList())
        assertEquals("Light", prefs.get(PrefKeys.THEME))
    }

    @Test fun clearUserDataRemovesMoney() {
        db.accountsQueries.upsert(Accounts("a1", "HDFC", "1234", "bank", 100, 0, 0))
        db.categoriesQueries.upsert(Categories("c1", "Food", "🍜", "expense", "food", null, 0, 0))
        db.transactionsQueries.upsert(Transactions("t1", "expense", 100, "c1", "a1", null, "Cafe", null, "2026-10-14", "09:00", "user", null, "[]"))
        db.recurringQueries.upsert(Recurring("r1", "Rent", "🏠", 100, 5, "UPI", "bill", 1, null, "a1", null, null))
        db.subscriptionsQueries.upsert(Subscriptions("s1", "Netflix", 100, "Monthly", "2026-11-01", "#e50914", "N", null, null, "a1"))

        LocalData(db).clearUserData()

        assertEquals(0, db.accountsQueries.selectIds().executeAsList().size)
        assertEquals(0, db.categoriesQueries.selectIds().executeAsList().size)
        assertEquals(0, db.transactionsQueries.selectIds().executeAsList().size)
        assertEquals(0, db.recurringQueries.selectIds().executeAsList().size)
        assertEquals(0, db.subscriptionsQueries.selectIds().executeAsList().size)
    }

    @Test fun clearUserDataRemovesHabitsAndGoals() {
        db.habitsQueries.upsert(Habits("h1", "Read", "binary", 1, "", "book", "anytime", "1111111", null, null, 1, 0, "", 0))
        db.habitLogsQueries.upsert(Habit_logs("h1", "2026-10-14", 1))
        db.goalsQueries.upsert(
            Goals("g1", "Save", "numeric", "Medium-term", "2027-03-31", "2026-10-01", 0.0, 100.0, "", null, "", "Monthly", "2026-11-01", "active", "", "", 0),
        )
        db.milestonesQueries.upsert(Milestones("m1", "g1", "Half", 50.0, null, null, null, 0))
        db.goalProgressQueries.insert(Goal_progress("g1", 0, "2026-10-14", 10.0, null))

        LocalData(db).clearUserData()

        assertEquals(0, db.habitsQueries.selectIds().executeAsList().size)
        assertEquals(0, db.habitLogsQueries.selectAll().executeAsList().size)
        assertEquals(0, db.goalsQueries.selectIds().executeAsList().size)
        assertEquals(0, db.milestonesQueries.selectAll().executeAsList().size)
        assertEquals(0, db.goalProgressQueries.selectAll().executeAsList().size)
    }

    @Test fun clearUserDataRemovesWellnessNotesAndAi() {
        db.wellnessEntriesQueries.upsert(Wellness_entries("2026-10-14", 3, "23:30", "06:30", 8000, 6, 72.5, null, "2026-10-14"))
        db.wellnessSettingsQueries.upsert(WellnessSettings().toRow())
        db.notesQueries.upsert(Notes("n1", "Idea", "", "Ideas", "[]", null, 0, null, "2026-10-14", "2026-10-14", 1))
        db.activityQueries.upsert(Activity("ac1", "Check-in", "heart", "info", "wellness", null, "2026-10-14T04:30:00.000Z"))
        db.aiActionsQueries.upsert(Ai_actions("x1", "2026-10-14T04:30:00.000Z", "Created task", "", "Approved by you", "success", 1, 0, null))
        db.aiMessagesQueries.upsert(Ai_messages("m1", "user", "hi", null, null, 0, 1))
        db.suggestionsQueries.upsert(Suggestions("sg-1", "bell", "Remind", "", "Habits", "", null, null, "open", 0))

        LocalData(db).clearUserData()

        assertEquals(0, db.wellnessEntriesQueries.selectDates().executeAsList().size)
        assertEquals(null, db.wellnessSettingsQueries.select().executeAsOneOrNull())
        assertEquals(0, db.notesQueries.selectIds().executeAsList().size)
        assertEquals(0, db.activityQueries.selectAll().executeAsList().size)
        assertEquals(0, db.aiActionsQueries.selectIds().executeAsList().size)
        assertEquals(0, db.aiMessagesQueries.selectAll().executeAsList().size)
        assertEquals(0, db.suggestionsQueries.selectAll().executeAsList().size)
    }
}
