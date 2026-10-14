package tech.beyondscale.mitra.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MigrationTest {
    /** The exact schema Phase 1 shipped (Outbox.sq, Pref.sq, User.sq at commit befbccc). */
    private val v1 = listOf(
        """CREATE TABLE outbox (
          seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, entity TEXT NOT NULL, method TEXT NOT NULL,
          path TEXT NOT NULL, body TEXT, coalesceKey TEXT, inFlight INTEGER NOT NULL DEFAULT 0,
          attempts INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)""",
        "CREATE TABLE pref (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE cached_user (id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)",
    )

    @Test fun phase1DatabaseUpgradesInPlace() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1.forEach { driver.execute(null, it, 0) }
        driver.execute(null, "INSERT INTO cached_user(id, json) VALUES ('u1', '{\"id\":\"u1\"}')", 0)
        driver.execute(null, "INSERT INTO pref(key, value) VALUES ('lastUserId', 'u1')", 0)
        driver.execute(
            null,
            // Phase 1 counted every claim as an attempt; Phase 2 counts only `500 INTERNAL` answers.
            "INSERT INTO outbox(id, entity, method, path, body, coalesceKey, attempts, createdAt) " +
                "VALUES ('o1', 'user-settings', 'PATCH', 'users/me/settings', '{}', 'user-settings', 5, 0)",
            0,
        )

        assertEquals(4L, MitraDb.Schema.version)
        MitraDb.Schema.migrate(driver, 1, MitraDb.Schema.version)
        val db = MitraDb(driver)

        // Session and queued change survive.
        assertNotNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertEquals("u1", db.prefQueries.get("lastUserId").executeAsOneOrNull())
        val row = db.outboxQueries.selectAll().executeAsList().single()
        assertEquals("o1", row.id)
        assertNull(row.entityId)
        // Inherited claim counts must not push the row towards the poison-row drop.
        assertEquals(0L, row.attempts)

        // New tables work through the generated queries.
        db.tasksQueries.upsert(Tasks("t1", "Write", null, null, null, "2026-10-14", null, 30, "high", "todo", 0, null, null, "", null, null, 0, "user", "2026-10-14T04:30:00Z"))
        db.projectsQueries.upsert(Projects("p1", "Work", "💼", null, "2026-10-14T04:30:00Z"))
        assertEquals("Write", db.tasksQueries.selectById("t1").executeAsOne().title)
        assertEquals(listOf("p1"), db.projectsQueries.selectIds().executeAsList())

        db.outboxQueries.insert("o2", "task", "PATCH", "tasks/t1", "{}", "task:t1", 1, "t1")
        assertEquals(listOf("t1"), db.outboxQueries.pendingEntityIds("task").executeAsList())
    }

    @Test fun phase2DatabaseUpgradesInPlace() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1.forEach { driver.execute(null, it, 0) }
        MitraDb.Schema.migrate(driver, 1, 2) // now a genuine v2 database
        driver.execute(
            null,
            "INSERT INTO tasks(id, title, status, done, notes, someday, source, createdAt) " +
                "VALUES ('t1', 'Write', 'todo', 0, '', 0, 'user', '2026-10-14T04:30:00Z')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO outbox(id, entity, method, path, body, coalesceKey, createdAt, entityId) " +
                "VALUES ('o1', 'task', 'PATCH', 'tasks/t1', '{}', 'task:t1', 0, 't1')",
            0,
        )

        MitraDb.Schema.migrate(driver, 2, MitraDb.Schema.version)
        val db = MitraDb(driver)

        assertEquals(4L, MitraDb.Schema.version)
        assertEquals("Write", db.tasksQueries.selectById("t1").executeAsOne().title)
        assertEquals("o1", db.outboxQueries.selectAll().executeAsList().single().id)
        assertEquals(0L, db.transactionsQueries.selectIds().executeAsList().size.toLong())
        val count = driver.executeQuery(null, "SELECT count(*) FROM transactions", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null)
        }, 0).value
        assertEquals(0L, count)
        db.accountsQueries.upsert(Accounts("a1", "HDFC", "1234", "bank", 100, 0, 0))
        assertEquals(listOf("a1"), db.accountsQueries.selectIds().executeAsList())
    }

    @Test fun phase3DatabaseUpgradesInPlace() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1.forEach { driver.execute(null, it, 0) }
        MitraDb.Schema.migrate(driver, 1, 3) // now a genuine v3 database
        driver.execute(
            null,
            "INSERT INTO transactions(id, kind, amountPaise, accountId, merchant, date, time, source, tags) " +
                "VALUES ('tx1', 'expense', 12345, 'a1', 'Cafe', '2026-10-14', '09:00', 'user', '[]')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO outbox(id, entity, method, path, body, coalesceKey, createdAt, entityId) " +
                "VALUES ('o1', 'transaction', 'POST', 'money/transactions', '{}', NULL, 0, 'tx1')",
            0,
        )

        MitraDb.Schema.migrate(driver, 3, 4)
        val db = MitraDb(driver)

        assertEquals(4L, MitraDb.Schema.version)
        assertEquals(12345L, db.transactionsQueries.selectById("tx1").executeAsOne().amountPaise)
        assertEquals("o1", db.outboxQueries.selectAll().executeAsList().single().id)
        val count = driver.executeQuery(null, "SELECT count(*) FROM habits", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null)
        }, 0).value
        assertEquals(0L, count)

        // New tables work through the generated queries.
        db.habitsQueries.upsert(Habits("h1", "Read", "binary", 1, "", "book", "anytime", "1111111", null, null, 1, 0, "", 0))
        db.habitLogsQueries.upsert(Habit_logs("h1", "2026-10-14", 1))
        db.goalsQueries.upsert(
            Goals("g1", "Save", "numeric", "Medium-term", "2027-03-31", "2026-10-01", 0.0, 100.0, "", null, "", "Monthly", "2026-11-01", "active", "", "", 0),
        )
        db.milestonesQueries.upsert(Milestones("m1", "g1", "Half", 50.0, null, null, null, 0))
        db.goalProgressQueries.insert(Goal_progress("g1", 0, "2026-10-14", 10.0, null))
        assertEquals(listOf("h1"), db.habitsQueries.selectIds().executeAsList())
        assertEquals(1, db.habitLogsQueries.selectForHabit("h1").executeAsList().size)
        assertEquals(listOf("m1"), db.milestonesQueries.selectForGoal("g1").executeAsList().map { it.id })
        assertEquals(1, db.goalProgressQueries.selectAll().executeAsList().size)
    }
}
