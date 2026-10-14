# Mitra Android — Phase 2 (Tasks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Tasks module on Android — Inbox / Today / Upcoming / Projects, project detail, task detail, task form, focus timer, the Quick Add sheet (all five type chips, Task fully working with the natural-language parser), swipe gestures with Undo + haptics, long-press bulk actions, real Tasks numbers and "Now · priorities" on Today, and a home-screen Tasks widget that completes tasks in place — all offline-first through the Phase 1 outbox.

**Architecture:** Same layering as Phase 1 (feature → domain ← data, MVVM). New SQLDelight tables `tasks` / `projects` (schema v2 via a migration so Phase 1 installs upgrade in place) are the source of truth; every write goes local-first + outbox, `TasksRepositoryImpl` registers its sync handlers at DI time and restores server state on rejection. Web selectors from `mitra-frontend/src/store/selectors.ts` are ported 1:1 into `domain/tasks`. The widget is Jetpack Glance in `androidApp`, reading the same repository.

**Tech Stack:** Phase 1 stack unchanged (Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.1, CMP 1.12.1 / material3 1.9.0, JetBrains Navigation 2.9.2, Lifecycle 2.11.0, Koin 4.2.2, Ktor 3.6.0, SQLDelight 2.4.1, coroutines 1.11.0, serialization 1.11.0, datetime 0.8.0, WorkManager 2.12.0, Turbine 1.2.1) **plus Jetpack Glance `androidx.glance:glance-appwidget:1.2.0`** (latest stable on Google Maven on 2026-10-14; 1.3.0 is still alpha).

**Spec:** `docs/superpowers/specs/2026-10-13-android-app-design.md` — this plan covers §13 item 2 (Tasks) plus the Phase 1 follow-ups assigned to it (§6 periodic sync + foreground refresh, debug-only network security config, sync-message replay).

## Global Constraints

- All code lives under `mitra-mobile/`; repo root is `/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai` — **the path contains a space; always quote it**. Never stage the repo-root `README.md` (it has unrelated local edits); `git add` only the files a task names.
- Branch: keep working on `feat/android-phase1`.
- Kotlin package root `tech.beyondscale.mitra`. compileSdk **37**, targetSdk **36**, minSdk **26**, JDK 17, Gradle wrapper only.
- Versions are exactly those in `gradle/libs.versions.toml`; the only addition in this phase is `glance = "1.2.0"` (Task 15). Do not bump anything else.
- `shared/*/commonMain` must not import Android APIs. `domain` depends only on `core`. `feature` never depends on `data`. Only `app` and `androidApp` wire `data` in.
- ViewModels depend on domain interfaces (repositories / `AppClock`), never on `data` classes. Screens are `Route` (collects state, owns `koinViewModel`) + stateless `Screen(state, onAction, …)`; one-off effects go through `EventQueue`/`EventEffect` (Task 6).
- API paths are relative with **no leading slash** (`"tasks/bootstrap"`), joined to a base URL ending in `/api/`. Every POST/PATCH without a payload sends `{}`. DELETE sends no body.
- Server contract (from `mitra-backend/src/tasks/*`): `GET tasks/bootstrap → { tasks, projects }`; `POST tasks` (client UUID `id`), `PATCH tasks/:id`, `DELETE tasks/:id` (subtasks cascade), `POST projects`, `PATCH projects/:id`, `DELETE projects/:id` (tasks fall back to Inbox). Task enums on the wire: `pri` = `high|med|low`, `status` = `todo|inprogress|blocked|done`, `recurring` = `Daily|Weekly|Monthly|Quarterly`, `source` = `user|import|ai|recurring`. `amount` is rupees on the wire, stored as paise (`Long`) on the device.
- **Completion uses `PATCH tasks/:id { status, done, completedAt }`, never `POST tasks/:id/toggle`**: the toggle endpoint is not idempotent, and the outbox resends a row whose response was lost. `completedAt` is always the device's *local* date (the server would otherwise use its UTC date). Completing a recurring task spawns the next occurrence on the device (port of the server's `nextDue`, including JS month overflow).
- Every write that reaches the outbox carries `entityId`; PATCHes to the same entity coalesce (`coalesceKey = "task:<id>"` / `"project:<id>"`) only into the newest queued row.
- "Today" is always the device's local date from `AppClock` (`clock.today()`), never UTC; screens that show "today" re-render at local midnight (`clock.dayFlow()`).
- UI copy, icons and ordering follow `mitra-frontend/src/screens/tasks.tsx` / `today.tsx` unless this plan says otherwise. Icons come from `MitraIcons` (generated); colours from `Mitra.colors`.
- Tests: `./gradlew testAndroidHostTest` from `mitra-mobile/` runs every shared module's `commonTest` (plus `androidHostTest`) on the JVM. Keep the Phase 1 harnesses: `MockServer` + `testDriver()` + `FixedClock` + `ViewModelTest` + Turbine.
- Commit after every task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `local.properties`.
- *(Deviations flagged for the user)*: (a) Project detail is list-only on the phone (the web's kanban board is not ported); (b) the task form is a bottom sheet without the web's "Links to goal" field (Goals arrive in Phase 4); (c) refresh-after-drain is throttled to once per 60 s per module like foreground refresh; (d) a row that gets a plain `500 INTERNAL` three times is dropped and its module refreshed (otherwise one poisoned row — e.g. a create whose first response was lost — blocks the whole queue forever).

## Review Focus

1. **Upgrading a Phase 1 install** — the user's phone has a v1 database (signed-in user, maybe queued settings). Opening the Phase 2 build must migrate it (new tables + `outbox.entityId`) without losing the session or the queue, never crash with "no such table". Pinned in Task 2 (`MigrationTest.phase1DatabaseUpgradesInPlace`).
2. **Offline: move a task into a project created offline** — writes are replayed in order, and a later PATCH must never be folded into an older queued PATCH that would then run before the project's POST (server answers 404 and the move is lost). Pinned in Task 3 (`OutboxWriterTest.doesNotCoalesceAcrossLaterRows`) and Task 4 (`TasksRepositoryImplTest.moveIntoProjectCreatedOfflineReachesServerInOrder`).
3. **A request that reached the server but whose response was lost is resent** — completing must stay completed (idempotent PATCH, not toggle), and a duplicate create (server answers `500 INTERNAL` on the existing id) must not block the queue forever. Pinned in Task 3 (`SyncEngineTest.internalErrorRowIsDroppedAfterThreeAttempts`, `gatewayErrorsAreNeverDropped`) and Task 4 (`completeSendsIdempotentPatchWithLocalDate`).
4. **Pull-to-refresh (or foreground refresh) racing a local edit** — a bootstrap response computed before the edit reached the server must not revert the edit, resurrect a deleted task, or (after sign-out) write another account's tasks into the database. Pinned in Task 4 (`refreshKeepsRowsWithPendingChanges`, `refreshRacingAWriteIsDiscarded`, `refreshAfterSignOutWritesNothing`).
5. **Late evening in India / midnight rollover** — at 00:30 IST the UTC date is still yesterday; "today", overdue, `completedAt`, the Today stat and the widget must all use the local date, and roll over at local midnight without an app restart. Pinned in Task 1 (`TaskSelectorsTest.usesTheDateItIsGiven`, `DayFlowTest.emitsAgainAtLocalMidnight`) and Task 4 (`completeSendsIdempotentPatchWithLocalDate`).

---

## File Map (all paths relative to `mitra-mobile/`)

`…` = `src/commonMain/kotlin/tech/beyondscale/mitra` (tests: `src/commonTest/kotlin/tech/beyondscale/mitra`).

```
gradle/libs.versions.toml                                   + glance (Task 15)

shared/core/          …/core/Dates.kt                        + diffDays, dowMon, fmtDay, fmtTime, minsToHm, addMonthsJs        (T1)
                      …/core/AppClock.kt                      + today()                                                          (T1)
                      …/core/DayFlow.kt                       dayFlow(), nextMidnightEpochMs()                                   (T1)
shared/domain/        …/domain/model/Tasks.kt                 Task, Project, Priority, TaskStatus, TaskDraft, TaskChange          (T1)
                      …/domain/tasks/TaskSelectors.kt         ported selectors, row labels, widget model, focus queue            (T1)
                      …/domain/tasks/QuickAddParser.kt        §9 parser                                                          (T1)
                      …/domain/repository/TasksRepository.kt  (T4) · ModuleRefresher.kt (T5) · AuthRepository.kt + hasLocalAccount (T15)
shared/testing/       …/testing/FakeTasksRepository.kt                                                                            (T4)
shared/data/          sqldelight/…/data/db/{Task,Project}.sq, Outbox.sq (+entityId), sqldelight/migrations/1.sqm                  (T2)
                      …/data/local/LocalData.kt (+tables) · …/data/local/TaskRows.kt                                             (T2, T4)
                      …/data/sync/{OutboxWriter,SyncEngine}.kt                                                                    (T2, T3)
                      …/data/remote/TaskDtos.kt · …/data/repository/{TasksRepositoryImpl,TaskPatches}.kt                         (T4)
                      …/data/sync/{ModuleRefresherImpl,PeriodicSync}.kt · …/data/di/DataModule.kt                                (T5)
                      androidHostTest …/data/db/MigrationTest.kt                                                                  (T2)
shared/designsystem/  …/designsystem/components/{Chips,Progress,Check,SwipeRow,Snackbar}.kt, TextField.kt (+params)              (T6)
shared/feature/       …/feature/common/{UiEvent,DatePickers}.kt                                                                  (T6, T8)
                      …/feature/tasks/{TaskRow,TasksViewModel,TasksScreen}.kt                                                    (T7, T9, T14)
                      …/feature/tasks/{TaskEditorViewModel,TaskFormSheet,TaskDetailViewModel,TaskDetailScreen}.kt                (T8)
                      …/feature/tasks/{ProjectFormSheet,ProjectDetailViewModel,ProjectDetailScreen}.kt                           (T9)
                      …/feature/tasks/{FocusViewModel,FocusScreen}.kt                                                            (T10)
                      …/feature/quickadd/{QuickAddViewModel,QuickAddSheet}.kt                                                    (T11)
                      …/feature/today/{TodayViewModel,TodayScreen}.kt                                                            (T12)
                      …/feature/di/FeatureModule.kt                                                                              (T7–T12)
shared/app/           …/app/{Destinations,MainShell,AppModules,LaunchTarget}.kt                                                  (T13)
androidApp/           build.gradle.kts (+dev network security config task, glance) · src/debug/AndroidManifest.xml               (T5, T15)
                      src/main/AndroidManifest.xml · kotlin/…/android/{MitraApplication,MainActivity,SyncWorker}.kt              (T5, T15)
                      kotlin/…/android/widget/{TasksWidget,TasksWidgetReceiver,ToggleTaskAction,WidgetUpdater,MidnightReceiver}.kt (T15)
                      res/xml/tasks_widget_info.xml · res/values/strings.xml                                                     (T15)
README.md                                                                                                                         (T16)
```

---
### Task 1: Domain — dates, task models, ported selectors, Quick Add parser

**Files:**
- Modify: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/Dates.kt` (full replacement below)
- Modify: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/AppClock.kt` (add `today()`)
- Create: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/DayFlow.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/model/Tasks.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/tasks/TaskSelectors.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/tasks/QuickAddParser.kt`
- Test: `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/DatesTest.kt` (extend), `…/core/DayFlowTest.kt`
- Test: `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/tasks/TaskSelectorsTest.kt`, `…/QuickAddParserTest.kt`

**Interfaces:**
- Consumes: Phase 1 `AppClock`, `localNow()`, `addDays`, `fmtClock`, `fmtLong`, `greeting`.
- Produces (core): `diffDays(a, b): Int`, `dowMon(iso): Int`, `addMonthsJs(iso, n): String`, `fmtDay(iso, today, relative = true): String`, `fmtTime(t: String?): String`, `minsToHm(m: Int): String`, `AppClock.today(): String`, `AppClock.dayFlow(): Flow<String>`, `AppClock.nextMidnightEpochMs(): Long`.
- Produces (domain model): `Priority { High, Med, Low }` (`wire`, `label`, `fromWire`), `TaskStatus { Todo, InProgress, Blocked, Done }` (`wire`, `label`, `fromWire`), `Task`, `Project`, `TaskDraft`, `sealed interface TaskChange` (`Title, ProjectId, Due, Time, EstMin, Pri, Status, Notes, Recurring, Someday, Parent`), `RECURRENCES`.
- Produces (domain/tasks): `Task.isInbox()`, `Task.isOverdue(today)`, `Task.isToday(today)`, `Task.isUpcoming(today)`, `openToday`, `doneToday`, `overdueTasks`, `priorityTasks`, `plannedMin`, `doneMin`, `children`, `subProgress → SubProgress(done,total)`, `groupUpcoming → List<Pair<String, List<Task>>>`, `upcomingLabel(key, today)`, `todaySorted`, `taskCounts → TaskCounts(inbox,today,upcoming,projects)`, `DueFilter`, `TaskFilters`, `List<Task>.applyFilters(f, today)`, `dueLabel(t, today)`, `focusLine(tasks, today)`, `focusQueue(tasks, today, pinnedId)`, `projectStats → ProjectStats(open, done, pct)`, `nextDue(due, recurring, today)`, `nextMonday(today)`, `TasksWidgetModel` / `WidgetTaskRow` / `tasksWidgetModel(tasks, today, signedIn)`, `priRank(p)`; `ParsedField { Due, Pri, Project }`, `ParsedTask(title, due, pri, project)`, `QuickAddParser.parse(input, today, projects, ignore = emptySet())`.

- [ ] **Step 1: Write the failing core tests** — append to `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/DatesTest.kt` (inside the class, before the final `}`):

```kotlin
    @Test fun fmtDayIsRelativeToTheGivenToday() {
        val today = "2026-10-14" // a Wednesday
        assertEquals("Today", fmtDay("2026-10-14", today))
        assertEquals("Tomorrow", fmtDay("2026-10-15", today))
        assertEquals("Yesterday", fmtDay("2026-10-13", today))
        assertEquals("Mon 19 Oct", fmtDay("2026-10-19", today))
        assertEquals("Wed 14 Oct", fmtDay("2026-10-14", today, relative = false))
    }

    @Test fun fmtTimeMatchesWebAndToleratesJunk() {
        assertEquals("5:00 PM", fmtTime("17:00"))
        assertEquals("12:05 AM", fmtTime("00:05"))
        assertEquals("", fmtTime(null))
        assertEquals("", fmtTime("soon"))
    }

    @Test fun minsToHmMatchesWeb() {
        assertEquals("0m", minsToHm(0))
        assertEquals("45m", minsToHm(45))
        assertEquals("1h", minsToHm(60))
        assertEquals("1h 05m", minsToHm(65))
        assertEquals("2h 30m", minsToHm(150))
    }

    @Test fun diffDaysAndDowMon() {
        assertEquals(7, diffDays("2026-10-20", "2026-10-13"))
        assertEquals(-1, diffDays("2026-10-12", "2026-10-13"))
        assertEquals(1, dowMon("2026-10-13")) // Tuesday
        assertEquals(6, dowMon("2026-10-18")) // Sunday
    }

    @Test fun addMonthsJsOverflowsLikeTheServer() {
        assertEquals("2026-03-03", addMonthsJs("2026-01-31", 1)) // JS: 31 Feb → 3 Mar
        assertEquals("2026-11-15", addMonthsJs("2026-10-15", 1))
        assertEquals("2027-03-02", addMonthsJs("2026-11-30", 3))
    }
```

Create `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/DayFlowTest.kt`:

```kotlin
package tech.beyondscale.mitra.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayFlowTest {
    // core can't use shared:testing (it depends on core), so a local clock.
    private class TestClock(var instant: Instant, override val zone: TimeZone) : AppClock {
        override fun now(): Instant = instant
    }

    private val ist = TimeZone.of("Asia/Kolkata")

    @Test fun todayIsTheLocalDateNotUtc() {
        // 19:00 UTC on the 13th is 00:30 IST on the 14th.
        assertEquals("2026-10-14", TestClock(Instant.parse("2026-10-13T19:00:00Z"), ist).today())
    }

    @Test fun nextMidnightIsLocal() {
        val clock = TestClock(Instant.parse("2026-10-13T18:00:00Z"), ist) // 23:30 IST
        assertEquals(Instant.parse("2026-10-13T18:30:00Z").toEpochMilliseconds(), clock.nextMidnightEpochMs())
    }

    @Test fun emitsAgainAtLocalMidnight() = runTest {
        val clock = TestClock(Instant.parse("2026-10-13T18:00:00Z"), ist) // 23:30 IST
        val seen = mutableListOf<String>()
        backgroundScope.launch { clock.dayFlow().collect { seen += it } }
        runCurrent()
        assertEquals(listOf("2026-10-13"), seen)

        clock.instant = Instant.parse("2026-10-13T18:30:00.002Z") // just past local midnight
        advanceTimeBy(30 * 60_000L + 2)
        runCurrent()
        assertEquals(listOf("2026-10-13", "2026-10-14"), seen)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile" && ./gradlew :shared:core:testAndroidHostTest`
Expected: compilation FAILS (`Unresolved reference: fmtDay`, `today`, `dayFlow`, …).

- [ ] **Step 3: Implement the core helpers**

Replace `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/Dates.kt` with:

```kotlin
package tech.beyondscale.mitra.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

/* Ports of mitra-frontend/src/lib/dates.ts — days are ISO strings ("2026-09-17") everywhere. */

private val DOW_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val DOW_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MON_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val MON_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun addDays(iso: String, n: Int): String = LocalDate.parse(iso).plus(n, DateTimeUnit.DAY).toString()

/** Whole days from [b] to [a] — positive when [a] is later (web `diffDays(a, b)`). */
fun diffDays(a: String, b: String): Int = LocalDate.parse(b).daysUntil(LocalDate.parse(a))

/** Monday-based weekday index 0..6 (web `dowMon`). */
fun dowMon(iso: String): Int = LocalDate.parse(iso).dayOfWeek.isoDayNumber - 1

/**
 * Month arithmetic with JavaScript `Date#setUTCMonth` semantics: the day is kept and overflows into the
 * next month (31 Jan + 1 month = 3 Mar). The server's recurring-task `nextDue` behaves this way.
 */
fun addMonthsJs(iso: String, n: Int): String {
    val d = LocalDate.parse(iso)
    return LocalDate(d.year, d.month, 1).plus(n, DateTimeUnit.MONTH).plus(d.day - 1, DateTimeUnit.DAY).toString()
}

/** "Tuesday, 13 October 2026" */
fun fmtLong(iso: String): String {
    val d = LocalDate.parse(iso)
    return "${DOW_LONG[d.dayOfWeek.isoDayNumber - 1]}, ${d.day} ${MON_LONG[d.month.number - 1]} ${d.year}"
}

/** "Today" · "Tomorrow" · "Yesterday" · "Mon 21 Sep" — relative to [today] (the device's local date). */
fun fmtDay(iso: String, today: String, relative: Boolean = true): String {
    if (relative) when (diffDays(iso, today)) {
        0 -> return "Today"
        1 -> return "Tomorrow"
        -1 -> return "Yesterday"
    }
    val d = LocalDate.parse(iso)
    return "${DOW_SHORT[d.dayOfWeek.isoDayNumber - 1]} ${d.day} ${MON_SHORT[d.month.number - 1]}"
}

/** "4:05 PM" */
fun fmtClock(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

/** "17:00" → "5:00 PM"; empty for null or anything that isn't HH:MM. */
fun fmtTime(t: String?): String {
    val parts = t?.split(":") ?: return ""
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return ""
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return ""
    return fmtClock(h, m)
}

/** 45 → "45m" · 60 → "1h" · 65 → "1h 05m" (web `minsToHm`). */
fun minsToHm(m: Int): String =
    if (m >= 60) "${m / 60}h ${if (m % 60 != 0) (m % 60).toString().padStart(2, '0') + "m" else ""}".trim() else "${m}m"

fun greeting(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
```

Append to `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/AppClock.kt`:

```kotlin

/** The device's local date as ISO ("2026-10-14") — what every "today" in the app means. */
fun AppClock.today(): String = localNow().date.toString()
```

Create `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/DayFlow.kt`:

```kotlin
package tech.beyondscale.mitra.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** Epoch millis of the next local midnight (start of tomorrow in [AppClock.zone]). */
fun AppClock.nextMidnightEpochMs(): Long =
    localNow().date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()

/** The local date now, and again whenever it changes at local midnight — keeps "today" fresh without a restart. */
fun AppClock.dayFlow(): Flow<String> = flow {
    while (true) {
        emit(today())
        delay((nextMidnightEpochMs() - now().toEpochMilliseconds()).coerceAtLeast(0) + 1)
    }
}.distinctUntilChanged()
```

- [ ] **Step 4: Run the core tests**

Run: `./gradlew :shared:core:testAndroidHostTest`
Expected: PASS (all `DatesTest`, `DayFlowTest`, `MoneyTest`).

- [ ] **Step 5: Write the failing domain tests**

Create `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/tasks/TaskSelectorsTest.kt`:

```kotlin
package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TODAY = "2026-10-14" // Wednesday

private fun t(
    id: String, due: String? = null, done: Boolean = false, completedAt: String? = null, pri: Priority? = null,
    estMin: Int? = null, projectId: String? = null, time: String? = null, parentId: String? = null,
    someday: Boolean = false, title: String = id, recurring: String? = null,
) = Task(id = id, title = title, due = due, done = done, completedAt = completedAt, pri = pri, estMin = estMin,
    projectId = projectId, time = time, parentId = parentId, someday = someday, recurring = recurring)

class TaskSelectorsTest {
    @Test fun inboxMeansUnfiledOpenTopLevel() {
        assertTrue(t("a").isInbox())
        assertFalse(t("a", due = TODAY).isInbox())
        assertFalse(t("a", projectId = "p").isInbox())
        assertFalse(t("a", someday = true).isInbox())
        assertFalse(t("a", done = true).isInbox())
        assertFalse(t("a", parentId = "x").isInbox())
        assertFalse(t("a").copy(goalId = "g").isInbox())
    }

    @Test fun overdueTodayUpcoming() {
        assertTrue(t("a", due = "2026-10-13").isOverdue(TODAY))
        assertFalse(t("a", due = "2026-10-13", done = true).isOverdue(TODAY))
        assertTrue(t("a", due = TODAY).isToday(TODAY))
        assertTrue(t("a", due = "2026-10-15").isUpcoming(TODAY))
        assertFalse(t("a", due = "2026-10-15", done = true).isUpcoming(TODAY))
    }

    @Test fun usesTheDateItIsGiven() {
        // 00:30 IST on the 14th is still the 13th in UTC: the caller's local date decides.
        val task = t("a", due = "2026-10-13")
        assertTrue(task.isToday("2026-10-13"))
        assertTrue(task.isOverdue("2026-10-14"))
        assertEquals(listOf(task), openToday(listOf(task), "2026-10-13"))
        assertEquals(emptyList(), openToday(listOf(task), "2026-10-14"))
    }

    @Test fun doneTodayCountsByCompletionDate() {
        val tasks = listOf(
            t("a", due = "2026-10-01", done = true, completedAt = TODAY),
            t("b", due = TODAY, done = true, completedAt = "2026-10-13"),
            t("c", due = TODAY),
        )
        assertEquals(listOf("a"), doneToday(tasks, TODAY).map { it.id })
        assertEquals(listOf("c"), openToday(tasks, TODAY).map { it.id })
    }

    @Test fun priorityTasksPutOverdueFirstThenPriorityAndKeepThree() {
        val tasks = listOf(
            t("a", due = TODAY, pri = Priority.Low),
            t("b", due = "2026-10-10"),
            t("c", due = TODAY, pri = Priority.High),
            t("d", due = TODAY, pri = Priority.Med),
            t("e", due = TODAY, pri = Priority.High, done = true),
        )
        assertEquals(listOf("b", "c", "d"), priorityTasks(tasks, TODAY).map { it.id })
    }

    @Test fun plannedAndDoneMinutesCountOnlyToday() {
        val tasks = listOf(
            t("a", due = TODAY, estMin = 30, done = true, completedAt = TODAY),
            t("b", due = TODAY, estMin = 45),
            t("c", due = TODAY),
            t("d", due = "2026-10-13", estMin = 60),
        )
        assertEquals(75, plannedMin(tasks, TODAY))
        assertEquals(30, doneMin(tasks, TODAY))
    }

    @Test fun groupUpcomingMatchesWebBuckets() {
        val tasks = listOf(
            t("x", due = "2026-10-22"), t("a", due = "2026-10-15"), t("b", due = "2026-10-15"),
            t("c", due = "2026-10-21"), t("y", due = "2026-10-28"), t("z", due = "2026-10-29"), t("old", due = "2026-10-01"),
        )
        val groups = groupUpcoming(tasks, TODAY)
        assertEquals(listOf("2026-10-15", "2026-10-21", "next-week", "later"), groups.map { it.first })
        assertEquals(listOf("a", "b"), groups[0].second.map { it.id })
        assertEquals(listOf("x", "y"), groups[2].second.map { it.id })
        assertEquals("Tomorrow · Thu 15 Oct", upcomingLabel("2026-10-15", TODAY))
        assertEquals("Wed 21 Oct", upcomingLabel("2026-10-21", TODAY))
        assertEquals("Next week", upcomingLabel("next-week", TODAY))
        assertEquals("Later", upcomingLabel("later", TODAY))
    }

    @Test fun todaySortedPutsOpenFirstThenByTime() {
        val tasks = listOf(
            t("late", due = TODAY, time = "18:00"), t("untimed", due = TODAY), t("done", due = TODAY, time = "07:00", done = true),
            t("early", due = TODAY, time = "09:30"),
        )
        assertEquals(listOf("early", "late", "untimed", "done"), todaySorted(tasks, TODAY).map { it.id })
    }

    @Test fun countsAndFilters() {
        val tasks = listOf(
            t("inbox"), t("over", due = "2026-10-13", projectId = "p1", pri = Priority.High),
            t("today", due = TODAY), t("soon", due = "2026-10-20", pri = Priority.High), t("far", due = "2026-11-30"),
        )
        assertEquals(TaskCounts(inbox = 1, today = 2, upcoming = 2, projects = 1), taskCounts(tasks, listOf(Project("p1", "Work")), TODAY))
        assertEquals(listOf("over"), tasks.applyFilters(TaskFilters(projectId = "p1"), TODAY).map { it.id })
        assertEquals(listOf("over", "soon"), tasks.applyFilters(TaskFilters(pri = Priority.High), TODAY).map { it.id })
        assertEquals(listOf("over", "today", "soon"), tasks.applyFilters(TaskFilters(due = DueFilter.Week), TODAY).map { it.id })
        assertEquals(listOf("over"), tasks.applyFilters(TaskFilters(due = DueFilter.Overdue), TODAY).map { it.id })
    }

    @Test fun dueLabelsMatchTaskVm() {
        assertEquals("Overdue · Mon 12 Oct", dueLabel(t("a", due = "2026-10-12"), TODAY))
        assertEquals("5:00 PM", dueLabel(t("a", due = TODAY, time = "17:00"), TODAY))
        assertEquals("Tomorrow 9:00 AM", dueLabel(t("a", due = "2026-10-15", time = "09:00"), TODAY))
        assertEquals("Tomorrow", dueLabel(t("a", due = "2026-10-15"), TODAY))
        assertEquals(null, dueLabel(t("a"), TODAY))
    }

    @Test fun focusLineMatchesWeb() {
        val tasks = listOf(
            t("a", due = TODAY, title = "Email Ravi"),
            t("b", due = TODAY, title = "Write report", pri = Priority.High, time = "17:00", estMin = 45),
        )
        assertEquals("Start with the Write report — due 5:00 PM, 45m.", focusLine(tasks, TODAY))
        assertEquals("Start with The deck.", focusLine(listOf(t("c", due = TODAY, title = "The deck")), TODAY))
        assertEquals("All done for today. Enjoy the evening.", focusLine(emptyList(), TODAY))
    }

    @Test fun focusQueuePinsTheChosenTaskUnlessDone() {
        val tasks = listOf(t("low", due = TODAY, pri = Priority.Low), t("high", due = TODAY, pri = Priority.High), t("pinned", due = "2026-10-20"))
        assertEquals(listOf("pinned", "high", "low"), focusQueue(tasks, TODAY, "pinned").map { it.id })
        assertEquals(listOf("high", "low"), focusQueue(tasks.map { if (it.id == "pinned") it.copy(done = true) else it }, TODAY, "pinned").map { it.id })
        assertEquals(listOf("high", "low"), focusQueue(tasks, TODAY, null).map { it.id })
    }

    @Test fun projectStatsAndSubProgress() {
        val tasks = listOf(t("a", projectId = "p", done = true), t("b", projectId = "p"), t("c", projectId = "p"), t("s1", parentId = "b", done = true), t("s2", parentId = "b"))
        assertEquals(ProjectStats(open = 2, done = 1, pct = 33), projectStats(tasks, "p"))
        assertEquals(ProjectStats(open = 0, done = 0, pct = 0), projectStats(tasks, "none"))
        assertEquals(SubProgress(done = 1, total = 2), subProgress(tasks, "b"))
    }

    @Test fun nextDueUsesServerArithmetic() {
        assertEquals("2026-10-15", nextDue(TODAY, "Daily", TODAY))
        assertEquals("2026-10-21", nextDue(TODAY, "Weekly", TODAY))
        assertEquals("2026-03-03", nextDue("2026-01-31", "Monthly", TODAY))
        assertEquals("2027-01-14", nextDue(TODAY, "Quarterly", TODAY))
        assertEquals("2026-10-15", nextDue(null, "Daily", TODAY))
        assertEquals("2026-10-19", nextMonday(TODAY))
        assertEquals("2026-10-26", nextMonday("2026-10-19"))
    }

    @Test fun widgetModelListsOverdueThenTodayByPriorityThenTime() {
        val tasks = listOf(
            t("today-low", due = TODAY, pri = Priority.Low),
            t("today-high-late", due = TODAY, pri = Priority.High, time = "18:00"),
            t("today-high-early", due = TODAY, pri = Priority.High, time = "08:00"),
            t("over-new", due = "2026-10-13"), t("over-old", due = "2026-10-10", title = "Old one"),
            t("done", due = TODAY, done = true, completedAt = TODAY),
        )
        val m = tasksWidgetModel(tasks, TODAY, signedIn = true)
        assertEquals(listOf("over-old", "over-new", "today-high-early", "today-high-late", "today-low"), m.rows.map { it.id })
        assertEquals(WidgetTaskRow("over-old", "Old one", overdue = true, meta = "Overdue · Sat 10 Oct"), m.rows[0])
        assertEquals("8:00 AM", m.rows[2].meta)
        assertEquals(1, m.done)
        assertEquals(4, m.total) // open today (3) + done today (1), like the Today stat
        assertEquals(5, m.left)
        assertFalse(tasksWidgetModel(tasks, TODAY, signedIn = false).signedIn)
    }
}
```

Create `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/tasks/QuickAddParserTest.kt`:

```kotlin
package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickAddParserTest {
    private val today = "2026-10-14" // Wednesday
    private val work = Project("p1", "Work", "💼")
    private val deep = Project("p2", "Deep work", "🎯")
    private val design = Project("p3", "Design", "🎨")
    private val side = Project("p4", "Side project", "🚀")
    private val projects = listOf(work, deep, design, side)

    private fun parse(s: String, ignore: Set<ParsedField> = emptySet()) = QuickAddParser.parse(s, today, projects, ignore)

    @Test fun parsesAllTokens() =
        assertEquals(ParsedTask("Call John", "2026-10-15", Priority.High, work), parse("Call John tomorrow p1 #work"))

    @Test fun todayAndCaseInsensitive() {
        assertEquals(ParsedTask("Pay rent", today), parse("Pay rent today"))
        assertEquals(ParsedTask("Ship it", "2026-10-15", Priority.Med), parse("Ship it TOMORROW P2"))
        assertEquals(Priority.Low, parse("Stretch p3").pri)
    }

    @Test fun weekdayMeansTheNextOneWithinAWeek() {
        assertEquals("2026-10-16", parse("Gym friday").due)
        assertEquals("2026-10-19", parse("Plan monday").due)
        assertEquals("2026-10-21", parse("Review wednesday").due) // typed on a Wednesday → next week
    }

    @Test fun projectTagsMatchNormalisedNamesOrAUniquePrefix() {
        assertEquals(deep, parse("Outline #deepwork").project)
        assertEquals(side, parse("Fix bug #side-project").project)
        assertEquals(deep, parse("Outline #deep").project)
        assertEquals(ParsedTask("Sketch #d"), parse("Sketch #d")) // ambiguous: Deep work / Design
        assertEquals(ParsedTask("Read #novel"), parse("Read #novel")) // unknown tag stays in the title
    }

    @Test fun onlyWholeWordsCount() {
        assertEquals(ParsedTask("Prep today's standup"), parse("Prep today's standup"))
        assertEquals(ParsedTask("Buy p10 cable"), parse("Buy p10 cable"))
    }

    @Test fun ignoredFieldsStayInTheTitle() {
        val r = parse("Buy milk tomorrow p1", ignore = setOf(ParsedField.Due))
        assertEquals("Buy milk tomorrow", r.title)
        assertNull(r.due)
        assertEquals(Priority.High, r.pri)
    }

    @Test fun collapsesWhitespaceAndMayLeaveAnEmptyTitle() {
        assertEquals(ParsedTask("Call John"), parse("  Call   John  "))
        assertEquals(ParsedTask("", today, Priority.High), parse("today p1"))
    }
}
```

- [ ] **Step 6: Run them to see them fail**

Run: `./gradlew :shared:domain:testAndroidHostTest`
Expected: compilation FAILS (`Unresolved reference: Task`, `QuickAddParser`, …).

- [ ] **Step 7: Implement the domain models** — `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/model/Tasks.kt`

```kotlin
package tech.beyondscale.mitra.domain.model

enum class Priority(val wire: String, val label: String) {
    High("high", "High"), Med("med", "Medium"), Low("low", "Low");

    companion object { fun fromWire(s: String?): Priority? = entries.firstOrNull { it.wire == s } }
}

enum class TaskStatus(val wire: String, val label: String) {
    Todo("todo", "To do"), InProgress("inprogress", "Doing"), Blocked("blocked", "Blocked"), Done("done", "Done");

    companion object { fun fromWire(s: String?): TaskStatus = entries.firstOrNull { it.wire == s } ?: Todo }
}

/** Mirrors `Task` in mitra-frontend/src/store/types.ts; money is kept in paise. Dates are ISO days, `time` is "HH:MM". */
data class Task(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val goalId: String? = null,
    val habitId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val estMin: Int? = null,
    val pri: Priority? = null,
    val status: TaskStatus = TaskStatus.Todo,
    val done: Boolean = false,
    val completedAt: String? = null,
    val parentId: String? = null,
    val notes: String = "",
    val recurring: String? = null,
    val amountPaise: Long? = null,
    val someday: Boolean = false,
    /** user · import · ai · recurring */
    val source: String = "user",
    /** Server timestamp (ISO instant); local creates use the device clock. */
    val createdAt: String = "",
)

data class Project(val id: String, val name: String, val emoji: String = "🚀", val goalId: String? = null, val createdAt: String = "")

val RECURRENCES = listOf("Daily", "Weekly", "Monthly", "Quarterly")

/** What a new task starts with (form, Quick Add, inbox composer, subtask field). */
data class TaskDraft(
    val title: String,
    val projectId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val estMin: Int? = null,
    val pri: Priority? = null,
    val status: TaskStatus = TaskStatus.Todo,
    val notes: String = "",
    val recurring: String? = null,
    val parentId: String? = null,
    val someday: Boolean = false,
    val goalId: String? = null,
)

/** One field edit. A null value clears the field on the server (sent as JSON null). */
sealed interface TaskChange {
    data class Title(val value: String) : TaskChange
    data class ProjectId(val value: String?) : TaskChange
    data class Due(val value: String?) : TaskChange
    data class Time(val value: String?) : TaskChange
    data class EstMin(val value: Int?) : TaskChange
    data class Pri(val value: Priority?) : TaskChange
    /** Also sets `done` / `completedAt` coherently, like the server does. */
    data class Status(val value: TaskStatus) : TaskChange
    data class Notes(val value: String) : TaskChange
    data class Recurring(val value: String?) : TaskChange
    data class Someday(val value: Boolean) : TaskChange
    data class Parent(val value: String?) : TaskChange
}
```

- [ ] **Step 8: Implement the selectors** — `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/tasks/TaskSelectors.kt`

```kotlin
package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.addMonthsJs
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import kotlin.math.roundToInt

/* 1:1 ports of the task selectors in mitra-frontend/src/store/selectors.ts and screens/tasks.tsx.
   The web reads todayISO() implicitly; here every function takes the device's local date. */

/** high 0 · med 1 · low 2 · none 3 (the web's `order` map). */
fun priRank(p: Priority?): Int = when (p) {
    Priority.High -> 0
    Priority.Med -> 1
    Priority.Low -> 2
    null -> 3
}

fun Task.isInbox(): Boolean = projectId == null && due == null && !someday && goalId == null && !done && parentId == null
fun Task.isOverdue(today: String): Boolean = due != null && due < today && !done
fun Task.isToday(today: String): Boolean = due == today
fun Task.isUpcoming(today: String): Boolean = due != null && due > today && !done

fun openToday(tasks: List<Task>, today: String): List<Task> = tasks.filter { it.isToday(today) && !it.done }
fun doneToday(tasks: List<Task>, today: String): List<Task> = tasks.filter { it.done && it.completedAt == today }
fun overdueTasks(tasks: List<Task>, today: String): List<Task> = tasks.filter { it.isOverdue(today) }

/** Up to three open overdue/today tasks: overdue first, then by priority (stable, like the web's sort). */
fun priorityTasks(tasks: List<Task>, today: String): List<Task> =
    tasks.filter { !it.done && (it.isOverdue(today) || it.isToday(today)) }
        .sortedWith(compareBy<Task> { if (it.isOverdue(today)) 0 else 1 }.thenBy { priRank(it.pri) })
        .take(3)

fun plannedMin(tasks: List<Task>, today: String): Int = tasks.filter { it.isToday(today) }.sumOf { it.estMin ?: 0 }
fun doneMin(tasks: List<Task>, today: String): Int = tasks.filter { it.isToday(today) && it.done }.sumOf { it.estMin ?: 0 }

fun children(tasks: List<Task>, id: String): List<Task> = tasks.filter { it.parentId == id }

data class SubProgress(val done: Int, val total: Int)
fun subProgress(tasks: List<Task>, id: String): SubProgress = children(tasks, id).let { c -> SubProgress(c.count { it.done }, c.size) }

/** Buckets: an ISO day (≤ 7 days out), "next-week" (8–14), "later"; ordered days → next week → later. */
fun groupUpcoming(tasks: List<Task>, today: String): List<Pair<String, List<Task>>> {
    val groups = linkedMapOf<String, MutableList<Task>>()
    tasks.filter { it.isUpcoming(today) }.sortedBy { it.due }.forEach { t ->
        val n = diffDays(t.due!!, today)
        val key = when {
            n <= 7 -> t.due
            n <= 14 -> "next-week"
            else -> "later"
        }
        groups.getOrPut(key) { mutableListOf() }.add(t)
    }
    val rank = { k: String -> when (k) { "next-week" -> 1; "later" -> 2; else -> 0 } }
    return groups.entries.sortedWith(compareBy({ rank(it.key) }, { it.key })).map { it.key to it.value.toList() }
}

fun upcomingLabel(key: String, today: String): String = when (key) {
    "next-week" -> "Next week"
    "later" -> "Later"
    else -> fmtDay(key, today) + if (diffDays(key, today) == 1) " · " + fmtDay(key, today, relative = false) else ""
}

/** The Today list: open before done, then by time with untimed last. */
fun todaySorted(tasks: List<Task>, today: String): List<Task> =
    tasks.filter { it.isToday(today) }.sortedWith(compareBy<Task> { it.done }.thenBy { it.time ?: "99" })

data class TaskCounts(val inbox: Int, val today: Int, val upcoming: Int, val projects: Int)

fun taskCounts(tasks: List<Task>, projects: List<Project>, today: String) = TaskCounts(
    inbox = tasks.count { it.isInbox() },
    today = tasks.count { it.isToday(today) || it.isOverdue(today) },
    upcoming = tasks.count { it.isUpcoming(today) },
    projects = projects.size,
)

enum class DueFilter(val label: String) { Overdue("Overdue"), Today("Due today"), Week("This week") }

data class TaskFilters(val projectId: String? = null, val pri: Priority? = null, val due: DueFilter? = null) {
    val active: Boolean get() = projectId != null || pri != null || due != null
}

fun List<Task>.applyFilters(f: TaskFilters, today: String): List<Task> = filter { t ->
    (f.projectId == null || t.projectId == f.projectId) &&
        (f.pri == null || t.pri == f.pri) &&
        when (f.due) {
            null -> true
            DueFilter.Overdue -> t.isOverdue(today)
            DueFilter.Today -> t.isToday(today)
            DueFilter.Week -> t.due != null && t.due <= addDays(today, 7)
        }
}

/** Row due label (web `taskVM`): "Overdue · Mon 12 Oct" · "5:00 PM" (today) · "Tomorrow 9:00 AM" · "Fri 16 Oct". */
fun dueLabel(t: Task, today: String): String? {
    val due = t.due ?: return null
    return when {
        t.isOverdue(today) -> "Overdue · ${fmtDay(due, today, relative = false)}"
        !t.time.isNullOrBlank() -> fmtDay(due, today).let { d -> if (d == "Today") fmtTime(t.time) else "$d ${fmtTime(t.time)}" }
        else -> fmtDay(due, today)
    }
}

/** The Focus card sentence on Tasks · Today. */
fun focusLine(tasks: List<Task>, today: String): String {
    val first = openToday(tasks, today).sortedBy { priRank(it.pri) }.firstOrNull() ?: return "All done for today. Enjoy the evening."
    val the = if (first.title.lowercase().startsWith("the")) "" else "the "
    val time = first.time?.takeIf { it.isNotBlank() }?.let { " — due ${fmtTime(it)}" } ?: ""
    val est = first.estMin?.takeIf { it > 0 }?.let { ", ${minsToHm(it)}" } ?: ""
    return "Start with $the${first.title}$time$est."
}

/** Focus mode queue: the chosen task first (unless it is already done), then today's open tasks by priority. */
fun focusQueue(tasks: List<Task>, today: String, pinnedId: String?): List<Task> {
    val open = openToday(tasks, today).sortedBy { priRank(it.pri) }
    val pinned = pinnedId?.let { id -> tasks.firstOrNull { it.id == id && !it.done } }
    return if (pinned != null) listOf(pinned) + open.filter { it.id != pinned.id } else open
}

data class ProjectStats(val open: Int, val done: Int, val pct: Int)

fun projectStats(tasks: List<Task>, projectId: String): ProjectStats {
    val ts = tasks.filter { it.projectId == projectId }
    val done = ts.count { it.done }
    return ProjectStats(ts.size - done, done, if (ts.isEmpty()) 0 else (done * 100.0 / ts.size).roundToInt())
}

/** Next occurrence of a recurring task — same arithmetic as `nextDue` in mitra-backend/src/tasks/tasks.service.ts. */
fun nextDue(due: String?, recurring: String, today: String): String {
    val d = due ?: today
    return when (recurring) {
        "Daily" -> addDays(d, 1)
        "Weekly" -> addDays(d, 7)
        "Monthly" -> addMonthsJs(d, 1)
        "Quarterly" -> addMonthsJs(d, 3)
        else -> d
    }
}

/** The Monday after [today] (a Monday maps to next week's), like the task detail's "Next Monday" chip. */
fun nextMonday(today: String): String = addDays(today, 7 - dowMon(today))

/* ---------- home-screen widget (spec §10) ---------- */

data class WidgetTaskRow(val id: String, val title: String, val overdue: Boolean, val meta: String?)

data class TasksWidgetModel(val signedIn: Boolean, val done: Int, val total: Int, val rows: List<WidgetTaskRow>) {
    val left: Int get() = rows.size
}

/** Overdue first (oldest first), then due today by priority then time; header numbers match the Today stat. */
fun tasksWidgetModel(tasks: List<Task>, today: String, signedIn: Boolean): TasksWidgetModel {
    val over = overdueTasks(tasks, today).sortedWith(compareBy<Task> { it.due }.thenBy { priRank(it.pri) })
    val open = openToday(tasks, today).sortedWith(compareBy<Task> { priRank(it.pri) }.thenBy { it.time ?: "99" })
    val done = doneToday(tasks, today).size
    val rows = over.map { WidgetTaskRow(it.id, it.title, true, "Overdue · ${fmtDay(it.due!!, today, relative = false)}") } +
        open.map { WidgetTaskRow(it.id, it.title, false, it.time?.takeIf { t -> t.isNotBlank() }?.let(::fmtTime)) }
    return TasksWidgetModel(signedIn, done, open.size + done, rows)
}
```

- [ ] **Step 9: Implement the parser** — `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/tasks/QuickAddParser.kt`

```kotlin
package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project

enum class ParsedField { Due, Pri, Project }

data class ParsedTask(val title: String, val due: String? = null, val pri: Priority? = null, val project: Project? = null)

/**
 * Spec §9 Quick Add parsing for tasks: whole words `today`, `tomorrow`, weekday names, `p1`–`p3` and `#project`
 * become due / priority / project and are removed from the title. Matching is case-insensitive. Fields in [ignore]
 * (chips the user dismissed) are left in the title as plain words. Later tokens win.
 */
object QuickAddParser {
    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    fun parse(input: String, today: String, projects: List<Project>, ignore: Set<ParsedField> = emptySet()): ParsedTask {
        var due: String? = null
        var pri: Priority? = null
        var project: Project? = null
        val kept = mutableListOf<String>()
        for (word in input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val w = word.lowercase()
            val asDue = if (ParsedField.Due in ignore) null else when (w) {
                "today" -> today
                "tomorrow" -> addDays(today, 1)
                in WEEKDAYS -> nextWeekday(today, WEEKDAYS.indexOf(w))
                else -> null
            }
            val asPri = if (ParsedField.Pri in ignore) null else when (w) {
                "p1" -> Priority.High
                "p2" -> Priority.Med
                "p3" -> Priority.Low
                else -> null
            }
            val asProject = if (ParsedField.Project in ignore || !w.startsWith("#")) null else matchProject(w.drop(1), projects)
            when {
                asDue != null -> due = asDue
                asPri != null -> pri = asPri
                asProject != null -> project = asProject
                else -> kept += word
            }
        }
        return ParsedTask(kept.joinToString(" "), due, pri, project)
    }

    /** 1–7 days ahead: a weekday typed on that same weekday means next week's. */
    internal fun nextWeekday(today: String, targetMon0: Int): String {
        val diff = (targetMon0 - dowMon(today) + 7) % 7
        return addDays(today, if (diff == 0) 7 else diff)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Exact match on the normalised name ("#deepwork" → "Deep work"), else a unique prefix. */
    internal fun matchProject(tag: String, projects: List<Project>): Project? {
        val k = norm(tag)
        if (k.isEmpty()) return null
        return projects.firstOrNull { norm(it.name) == k } ?: projects.filter { norm(it.name).startsWith(k) }.singleOrNull()
    }
}
```

- [ ] **Step 10: Run the domain tests**

Run: `./gradlew :shared:domain:testAndroidHostTest`
Expected: PASS (`TaskSelectorsTest`, `QuickAddParserTest`, `AuthUseCasesTest`).

- [ ] **Step 11: Commit**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai"
git add mitra-mobile/shared/core mitra-mobile/shared/domain
git commit -m "feat(mobile): task models, ported task selectors, local-date helpers and Quick Add parser

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Data — schema v2 (tasks, projects, outbox.entityId) with an in-place migration

**Files:**
- Create: `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Task.sq`
- Create: `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Project.sq`
- Create: `shared/data/src/commonMain/sqldelight/migrations/1.sqm`
- Modify: `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Outbox.sq` (new column last + queries)
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/sync/OutboxWriter.kt` (`entityId` parameter)
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/LocalData.kt`
- Modify: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/local/LocalStoreTest.kt`, `…/data/sync/SyncEngineTest.kt` (positional `insert` gains an argument)
- Test: `shared/data/src/androidHostTest/kotlin/tech/beyondscale/mitra/data/db/MigrationTest.kt`

**Interfaces:**
- Consumes: Phase 1 `MitraDb`, `OutboxWriter.enqueue(entity, method, path, body, coalesceKey)`.
- Produces: generated `db.tasksQueries` (`selectAll`, `selectById`, `upsert(Tasks)`, `delete`, `clearProject`, `selectIds`, `deleteAll`) and `db.projectsQueries` (`selectAll`, `selectById`, `upsert(Projects)`, `delete`, `selectIds`, `deleteAll`); generated row classes `Tasks` / `Projects` (column order as below; `done`, `someday`, `estMin` are `Long`); outbox column `entityId: String?` and queries `pendingEntityIds(entity): List<String>`, `lastSeq(): Long`, `exists(id): Long`; `insert(id, entity, method, path, body, coalesceKey, createdAt, entityId)`; `OutboxWriter.enqueue(…, coalesceKey: String? = null, entityId: String? = null)`; `MitraDb.Schema.version == 2`.

- [ ] **Step 1: Write the failing migration test** — `shared/data/src/androidHostTest/kotlin/tech/beyondscale/mitra/data/db/MigrationTest.kt`

(JVM-only on purpose: it builds a raw Phase 1 database with the JDBC driver.)

```kotlin
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
            "INSERT INTO outbox(id, entity, method, path, body, coalesceKey, createdAt) " +
                "VALUES ('o1', 'user-settings', 'PATCH', 'users/me/settings', '{}', 'user-settings', 0)",
            0,
        )

        assertEquals(2L, MitraDb.Schema.version)
        MitraDb.Schema.migrate(driver, 1, MitraDb.Schema.version)
        val db = MitraDb(driver)

        // Session and queued change survive.
        assertNotNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertEquals("u1", db.prefQueries.get("lastUserId").executeAsOneOrNull())
        val row = db.outboxQueries.selectAll().executeAsList().single()
        assertEquals("o1", row.id)
        assertNull(row.entityId)

        // New tables work through the generated queries.
        db.tasksQueries.upsert(Tasks("t1", "Write", null, null, null, "2026-10-14", null, 30, "high", "todo", 0, null, null, "", null, null, 0, "user", "2026-10-14T04:30:00Z"))
        db.projectsQueries.upsert(Projects("p1", "Work", "💼", null, "2026-10-14T04:30:00Z"))
        assertEquals("Write", db.tasksQueries.selectById("t1").executeAsOne().title)
        assertEquals(listOf("p1"), db.projectsQueries.selectIds().executeAsList())

        db.outboxQueries.insert("o2", "task", "PATCH", "tasks/t1", "{}", "task:t1", 1, "t1")
        assertEquals(listOf("t1"), db.outboxQueries.pendingEntityIds("task").executeAsList())
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :shared:data:testAndroidHostTest --tests '*MigrationTest*'`
Expected: compilation FAILS (`Unresolved reference: tasksQueries`, `Tasks`, `entityId`).

- [ ] **Step 3: Add the tables** — `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Task.sq`

```sql
-- Tasks as the server shapes them (mitra-backend/src/tasks/mapper.ts); amount kept in paise, booleans as 0/1.
CREATE TABLE tasks (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  projectId TEXT,
  goalId TEXT,
  habitId TEXT,
  due TEXT,
  time TEXT,
  estMin INTEGER,
  pri TEXT,
  status TEXT NOT NULL,
  done INTEGER NOT NULL,
  completedAt TEXT,
  parentId TEXT,
  notes TEXT NOT NULL,
  recurring TEXT,
  amountPaise INTEGER,
  someday INTEGER NOT NULL,
  source TEXT NOT NULL,
  createdAt TEXT NOT NULL
);

-- Newest first, like the server's list and the web store.
selectAll:
SELECT * FROM tasks ORDER BY createdAt DESC, id;

selectById:
SELECT * FROM tasks WHERE id = ?;

upsert:
INSERT OR REPLACE INTO tasks VALUES ?;

delete:
DELETE FROM tasks WHERE id = ?;

-- Deleting a project sends its tasks back to the Inbox (server FK is SET NULL).
clearProject:
UPDATE tasks SET projectId = NULL WHERE projectId = ?;

selectIds:
SELECT id FROM tasks;

deleteAll:
DELETE FROM tasks;
```

`shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Project.sq`:

```sql
-- Active (non-archived) projects, as returned by the server.
CREATE TABLE projects (
  id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  emoji TEXT NOT NULL,
  goalId TEXT,
  createdAt TEXT NOT NULL
);

selectAll:
SELECT * FROM projects ORDER BY createdAt, id;

selectById:
SELECT * FROM projects WHERE id = ?;

upsert:
INSERT OR REPLACE INTO projects VALUES ?;

delete:
DELETE FROM projects WHERE id = ?;

selectIds:
SELECT id FROM projects;

deleteAll:
DELETE FROM projects;
```

- [ ] **Step 4: Extend the outbox** — replace `Outbox.sq` with (note `entityId` is the **last** column so a migrated table and a fresh one have the same layout):

```sql
-- Local writes waiting to reach the server, sent strictly in `seq` order.
CREATE TABLE outbox (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  entity TEXT NOT NULL,
  method TEXT NOT NULL,
  path TEXT NOT NULL,
  body TEXT,
  coalesceKey TEXT,
  inFlight INTEGER NOT NULL DEFAULT 0,
  attempts INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL,
  entityId TEXT
);

insert:
INSERT INTO outbox(id, entity, method, path, body, coalesceKey, createdAt, entityId) VALUES (?, ?, ?, ?, ?, ?, ?, ?);

selectNext:
SELECT * FROM outbox ORDER BY seq LIMIT 1;

selectAll:
SELECT * FROM outbox ORDER BY seq;

-- Only rows not currently being sent can absorb a newer patch.
pendingByCoalesceKey:
SELECT * FROM outbox WHERE coalesceKey = ? AND inFlight = 0 ORDER BY seq DESC LIMIT 1;

-- Ids of entities with a queued or in-flight write: a server refresh must not overwrite or delete them.
pendingEntityIds:
SELECT DISTINCT ifnull(entityId, '') FROM outbox WHERE entity = ? AND entityId IS NOT NULL;

lastSeq:
SELECT seq FROM outbox ORDER BY seq DESC LIMIT 1;

exists:
SELECT COUNT(*) FROM outbox WHERE id = ?;

updateBody:
UPDATE outbox SET body = ? WHERE id = ?;

markInFlight:
UPDATE outbox SET inFlight = 1, attempts = attempts + 1 WHERE id = ?;

clearInFlight:
UPDATE outbox SET inFlight = 0 WHERE id = ?;

resetInFlight:
UPDATE outbox SET inFlight = 0;

delete:
DELETE FROM outbox WHERE id = ?;

count:
SELECT COUNT(*) FROM outbox;

deleteAll:
DELETE FROM outbox;
```

- [ ] **Step 5: Write the migration** — `shared/data/src/commonMain/sqldelight/migrations/1.sqm`

(SQLDelight derives `Schema.version = 2` from this file; `AndroidSqliteDriver` runs it on first open of a Phase 1 database.)

```sql
-- v1 (Phase 1) → v2 (Phase 2 Tasks)
ALTER TABLE outbox ADD COLUMN entityId TEXT;

CREATE TABLE tasks (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  projectId TEXT,
  goalId TEXT,
  habitId TEXT,
  due TEXT,
  time TEXT,
  estMin INTEGER,
  pri TEXT,
  status TEXT NOT NULL,
  done INTEGER NOT NULL,
  completedAt TEXT,
  parentId TEXT,
  notes TEXT NOT NULL,
  recurring TEXT,
  amountPaise INTEGER,
  someday INTEGER NOT NULL,
  source TEXT NOT NULL,
  createdAt TEXT NOT NULL
);

CREATE TABLE projects (
  id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  emoji TEXT NOT NULL,
  goalId TEXT,
  createdAt TEXT NOT NULL
);
```

- [ ] **Step 6: Pass `entityId` through the writer** — in `OutboxWriter.kt` change the signature and the insert call:

```kotlin
    fun enqueue(entity: String, method: String, path: String, body: JsonObject?, coalesceKey: String? = null, entityId: String? = null) {
```

```kotlin
        db.outboxQueries.insert(
            id = newId(), entity = entity, method = method, path = path,
            body = body?.let { json.encodeToString(JsonObject.serializer(), it) },
            coalesceKey = coalesceKey, createdAt = clock.now().toEpochMilliseconds(), entityId = entityId,
        )
```

Also add to its KDoc: `[entityId] marks which record the row writes, so refreshes can keep that record's local copy until the row is acknowledged.`

- [ ] **Step 7: Clear the new tables on logout / account switch** — `LocalData.kt`:

```kotlin
package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.MitraDb

class LocalData(private val db: MitraDb) {
    /** Removes everything that belongs to the signed-in account. Each later module adds its tables here. */
    fun clearUserData() = db.transaction {
        db.outboxQueries.deleteAll()
        db.userQueries.deleteAll()
        db.tasksQueries.deleteAll()
        db.projectsQueries.deleteAll()
    }
}
```

- [ ] **Step 8: Update the two positional `insert` calls and extend `LocalStoreTest`**

In `SyncEngineTest.unparseableBodyIsRejectedAndQueueContinues`:
```kotlin
        db.outboxQueries.insert("bad", "t", "PATCH", "a", "{bad", null, 0L, null)
```

In `LocalStoreTest.clearUserDataKeepsDevicePrefs` replace the insert line and add task/project rows + assertions:
```kotlin
        db.outboxQueries.insert("o1", "user-settings", "PATCH", "users/me/settings", "{}", null, 0, null)
        db.tasksQueries.upsert(Tasks("t1", "Write", null, null, null, null, null, null, null, "todo", 0, null, null, "", null, null, 0, "user", ""))
        db.projectsQueries.upsert(Projects("p1", "Work", "💼", null, ""))
```
```kotlin
        assertEquals(emptyList(), db.tasksQueries.selectIds().executeAsList())
        assertEquals(emptyList(), db.projectsQueries.selectIds().executeAsList())
```
with imports `tech.beyondscale.mitra.data.db.Tasks` and `tech.beyondscale.mitra.data.db.Projects`.

- [ ] **Step 9: Run the data tests**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: PASS — including `MigrationTest`, `LocalStoreTest`, and every Phase 1 data test (they use `Schema.create`, which now builds v2 directly).

- [ ] **Step 10: Commit**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai"
git add mitra-mobile/shared/data
git commit -m "feat(mobile): schema v2 with tasks/projects tables and outbox entityId, migrated in place from v1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Data — outbox ordering and SyncEngine hardening (echo guard, poison rows, message replay, ack counter)

**Files:**
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/sync/OutboxWriter.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/sync/SyncEngine.kt` (full replacement below)
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/sync/OutboxWriterTest.kt`, `…/SyncEngineTest.kt` (add tests)

**Interfaces:**
- Consumes: Task 2 outbox queries `lastSeq`, `exists`, `insert(… entityId)`.
- Produces: `SyncEngine.acknowledged: StateFlow<Long>` (bumped **before** a sent row leaves the outbox — success or rejection); `SyncEngine.drained: Flow<Unit>` (a drain that sent at least one row emptied the queue); `SyncEngine.messages` now buffered (`Channel`, 16, drop-oldest) so a rejection that happens while no screen collects is shown later; handlers are only called if the row was still queued when its response arrived; a row answered `500 INTERNAL` on its 3rd attempt is rejected. `OutboxWriter` coalesces only into the newest row.

Why each change (keep these as code comments):
- *Echo guard*: logout clears the outbox; a response for a row sent before logout must not write the old account's data back.
- *Poison rows*: this backend answers a plain `500 INTERNAL` for unhandled errors — e.g. a create retried after its first response was lost (duplicate primary key). Those never succeed; retrying forever would block every later write. Gateway errors (502/503/504) and 429 are outages and are always retried.
- *Replay*: the shell may not be composed when a write is rejected (app in background, Login screen); messages must wait for a collector.
- *Ack counter*: lets repositories detect that a refresh raced a write (Task 4).

- [ ] **Step 1: Write the failing tests**

Add to `OutboxWriterTest`:

```kotlin
    @Test fun doesNotCoalesceAcrossLaterRows() {
        // Patch task, create a project, patch the task again: folding the 2nd patch into the 1st would send it
        // before the project exists on the server.
        writer.enqueue("task", "PATCH", "tasks/t1", buildJsonObject { put("title", "A") }, "task:t1", "t1")
        writer.enqueue("project", "POST", "projects", buildJsonObject { put("id", "p1") }, entityId = "p1")
        writer.enqueue("task", "PATCH", "tasks/t1", buildJsonObject { put("projectId", "p1") }, "task:t1", "t1")
        val rows = db.outboxQueries.selectAll().executeAsList()
        assertEquals(listOf("tasks/t1", "projects", "tasks/t1"), rows.map { it.path })
        assertEquals("""{"projectId":"p1"}""", rows.last().body)
    }

    @Test fun recordsEntityId() {
        writer.enqueue("task", "DELETE", "tasks/t9", null, entityId = "t9")
        assertEquals("t9", db.outboxQueries.selectAll().executeAsList().single().entityId)
        assertEquals(listOf("t9"), db.outboxQueries.pendingEntityIds("task").executeAsList())
    }
```

Add to `SyncEngineTest` (imports to add: `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.launch`):

```kotlin
    @Test fun messagesAreBufferedUntilSomeoneCollects() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        enqueue("a")
        val engine = engine()
        assertEquals(SyncEngine.Result.Drained, engine.drain()) // nobody is collecting yet
        assertEquals("Couldn't save · Task not found", engine.messages.first())
    }

    @Test fun responseForARowClearedMidFlightIsIgnored() = runTest {
        server.routes["PATCH /api/a"] = {
            db.outboxQueries.deleteAll() // logout while the request is on the wire
            json("""{"id":"x"}""")
        }
        enqueue("a")
        assertEquals(SyncEngine.Result.Drained, engine().drain())
        assertEquals(0, successes.size)
    }

    @Test fun internalErrorRowIsDroppedAfterThreeAttempts() = runTest {
        server.routes["POST /api/a"] = { json(errorJson("INTERNAL", "Something went wrong"), HttpStatusCode.InternalServerError) }
        server.routes["PATCH /api/b"] = { json("{}") }
        writer.enqueue("t", "POST", "a", buildJsonObject { put("id", "dup") }, entityId = "dup")
        enqueue("b")
        val engine = engine()
        assertEquals(SyncEngine.Result.Retry, engine.drain())
        assertEquals(SyncEngine.Result.Retry, engine.drain())
        assertEquals(SyncEngine.Result.Drained, engine.drain()) // third 500: dropped, queue continues
        assertEquals(listOf<AppError>(AppError.Server(500, "INTERNAL", "Something went wrong")), rejections)
        assertEquals(listOf("POST /api/a", "POST /api/a", "POST /api/a", "PATCH /api/b"), server.log)
        assertEquals(0, pending().size)
    }

    @Test fun gatewayErrorsAreNeverDropped() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("ERROR", "Bad gateway"), HttpStatusCode.BadGateway) }
        enqueue("a")
        val engine = engine()
        repeat(5) { assertEquals(SyncEngine.Result.Retry, engine.drain()) }
        assertEquals(1, pending().size)
        assertEquals(0, rejections.size)
    }

    @Test fun ackCounterAndDrainedSignal() = runTest {
        server.routes["PATCH /api/a"] = { json("{}") }
        server.routes["PATCH /api/b"] = { json(errorJson("NOT_FOUND", "gone"), HttpStatusCode.NotFound) }
        enqueue("a"); enqueue("b")
        val engine = engine()
        var drainedSignals = 0
        backgroundScope.launch { engine.drained.collect { drainedSignals++ } }
        runCurrent()
        assertEquals(0L, engine.acknowledged.value)
        engine.drain(); runCurrent()
        assertEquals(2L, engine.acknowledged.value)
        assertEquals(1, drainedSignals)
        engine.drain(); runCurrent() // nothing sent → no signal
        assertEquals(1, drainedSignals)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :shared:data:testAndroidHostTest --tests '*OutboxWriterTest*' --tests '*SyncEngineTest*'`
Expected: FAIL / does not compile (`acknowledged`, `drained` unresolved; coalescing merges across rows; message lost with no collector).

- [ ] **Step 3: Coalesce only into the newest row** — in `OutboxWriter.enqueue` replace the `if (coalesceKey != null) { … }` block with:

```kotlin
        if (coalesceKey != null) {
            val queued = db.outboxQueries.pendingByCoalesceKey(coalesceKey).executeAsOneOrNull()
            // Merge only into the newest row: folding into an older one would send this change before the writes
            // queued after it (e.g. a task moved into a project whose POST is still queued).
            if (queued != null && queued.seq == db.outboxQueries.lastSeq().executeAsOneOrNull()) {
                val old = queued.body?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
                val merged = JsonObject(old + (body ?: JsonObject(emptyMap())))
                db.outboxQueries.updateBody(json.encodeToString(JsonObject.serializer(), merged), queued.id)
                return
            }
        }
```

- [ ] **Step 4: Replace `SyncEngine.kt`**

```kotlin
package tech.beyondscale.mitra.data.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.repository.SyncController

/** Platform hook to try again later (Android: WorkManager job that waits for a network). */
fun interface SyncScheduler { fun scheduleRetry() }

/** Per-entity reaction to a sent outbox row. Called only if the row was still queued when the answer came. */
interface SyncHandler {
    suspend fun onSuccess(response: JsonElement?)
    /** The server refused the write (4xx, or a repeated 500); the local copy should be restored from the server. */
    suspend fun onRejected(error: AppError)
}

/** Sends outbox rows one at a time, oldest first. See spec §6 for the failure rules. */
@OptIn(FlowPreview::class)
class SyncEngine(
    private val api: ApiClient,
    private val db: MitraDb,
    private val scheduler: SyncScheduler,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    debounceMs: Long = 500,
) : SyncController {
    enum class Result { Drained, Offline, Retry, SignedOut }

    private val handlers = mutableMapOf<String, SyncHandler>()
    private val mutex = Mutex()
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    // A Channel, not a SharedFlow: a rejection while no screen is collecting (app in background, Login showing)
    // must still reach the user once the shell is back.
    private val _messages = Channel<String>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _acks = MutableStateFlow(0L)
    private val _drained = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val messages: Flow<String> = _messages.receiveAsFlow()
    override val pendingCount: Flow<Long> = db.outboxQueries.count().asFlow().mapToOne(io)

    /** Number of rows that have left the outbox after a server answer; bumped *before* the row is removed. */
    val acknowledged: StateFlow<Long> = _acks.asStateFlow()

    /** Emits when a drain that sent at least one row has emptied the queue (modules may refresh). */
    val drained: Flow<Unit> = _drained.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            requests.debounce(debounceMs).collect {
                try { drain() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** Handlers must be registered at DI setup, before the first drain; the map is not synchronised. */
    fun register(entity: String, handler: SyncHandler) { handlers[entity] = handler }

    override fun requestSync() { requests.tryEmit(Unit) }

    /**
     * Sends queued rows until the outbox is empty or a send fails. Pass [scheduleRetryOnFailure] = false when the
     * caller retries on its own (the WorkManager job returns `Result.retry()`), so no extra job is chained.
     */
    suspend fun drain(scheduleRetryOnFailure: Boolean = true): Result = mutex.withLock { drainLocked(scheduleRetryOnFailure) }

    private suspend fun drainLocked(scheduleRetryOnFailure: Boolean): Result {
        var result: Result? = null
        var sent = 0
        while (result == null) {
            // Claim atomically so a concurrent enqueue can't merge into a row after we read its body.
            val claimed = withContext(io) {
                db.transactionWithResult {
                    val next = db.outboxQueries.selectNext().executeAsOneOrNull()
                    next?.also { db.outboxQueries.markInFlight(it.id) } to db.outboxQueries.count().executeAsOne()
                }
            }
            val entry = claimed.first
            val pending = claimed.second
            if (entry == null) {
                _status.value = SyncStatus.Idle
                if (sent > 0) _drained.tryEmit(Unit)
                result = Result.Drained
                continue
            }
            _status.value = SyncStatus.Syncing(pending)
            val attempt = entry.attempts + 1

            val body = try {
                entry.body?.let { api.json.parseToJsonElement(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sent++
                reject(entry.id, entry.entity, AppError.Unknown(e))
                continue
            }
            when (val r = api.send(HttpMethod.parse(entry.method), entry.path, body)) {
                is Outcome.Ok -> {
                    sent++
                    if (dequeue(entry.id)) safely { handlers[entry.entity]?.onSuccess(r.value) }
                }
                is Outcome.Err -> {
                    val e = r.error
                    // A plain 500 from this API is an unhandled exception (e.g. a create resent after its first
                    // response was lost hits the existing id). It will never succeed; don't block the queue on it.
                    val poisoned = e is AppError.Server && e.status == 500 && attempt >= POISON_ATTEMPTS
                    val transient = !poisoned &&
                        (e is AppError.Offline || (e is AppError.Server && (e.status >= 500 || e.status == 429)))
                    if (transient || e is AppError.Unauthenticated) {
                        withContext(io) { db.outboxQueries.clearInFlight(entry.id) }
                        if (transient && scheduleRetryOnFailure) scheduler.scheduleRetry()
                        _status.value = when (e) {
                            is AppError.Offline -> SyncStatus.Offline(pending)
                            is AppError.Unauthenticated -> SyncStatus.Idle
                            else -> SyncStatus.Retrying(pending)
                        }
                        result = when (e) {
                            is AppError.Offline -> Result.Offline
                            is AppError.Unauthenticated -> Result.SignedOut
                            else -> Result.Retry
                        }
                    } else {
                        sent++
                        reject(entry.id, entry.entity, e)
                    }
                }
            }
        }
        return result
    }

    /**
     * Removes a sent row; false if it was already gone (logout/account switch cleared the outbox while the request
     * was on the wire) — then its response belongs to a session that no longer exists and must be ignored.
     */
    private suspend fun dequeue(id: String): Boolean {
        _acks.update { it + 1 }
        return withContext(io) {
            db.transactionWithResult {
                val queued = db.outboxQueries.exists(id).executeAsOne() > 0
                db.outboxQueries.delete(id)
                queued
            }
        }
    }

    private suspend fun reject(id: String, entity: String, error: AppError) {
        if (!dequeue(id)) return
        safely { handlers[entity]?.onRejected(error) }
        _messages.trySend("Couldn't save · ${error.message}")
    }

    /** A misbehaving handler must not break the queue; the row outcome already applied stands. */
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private companion object { const val POISON_ATTEMPTS = 3 }
}
```

- [ ] **Step 5: Run the whole data suite**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: PASS — the new tests plus all Phase 1 tests (`rejectedWriteIsDroppedReportedAndQueueContinues` still sees its message: Turbine subscribes before `drain()`; `echoArrivingAfterLogoutDoesNotRecacheUser` still passes and is now also protected by the echo guard).

- [ ] **Step 6: Commit**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai"
git add mitra-mobile/shared/data
git commit -m "fix(mobile): keep outbox order when coalescing, ignore echoes after logout, drop poisoned rows, replay sync messages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Data — TasksRepository (DTOs, refresh, local-first writes, sync handlers) + test fake

**Files:**
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/repository/TasksRepository.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/remote/TaskDtos.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/TaskRows.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/TaskPatches.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImpl.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/sync/PendingSettings.kt` (`OutboxEntities.TASK/PROJECT`)
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/di/DataModule.kt`
- Create: `shared/testing/src/commonMain/kotlin/tech/beyondscale/mitra/testing/FakeTasksRepository.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImplTest.kt`, `…/TaskPatchesTest.kt`

**Interfaces:**
- Consumes: Task 1 models/selectors (`nextDue`), `AppClock.today()`; Task 2 queries; Task 3 `SyncEngine.acknowledged`, `register`, `requestSync`; Phase 1 `ApiClient.call`, `PrefStore`, `PrefKeys.LAST_USER_ID`, `OutboxWriter`.
- Produces: `interface TasksRepository` (below) — `tasks: Flow<List<Task>>` (newest first), `projects: Flow<List<Project>>`, `refresh(): Outcome<Unit>`, `create(TaskDraft): Task`, `update(id, List<TaskChange>)`, `setDone(id, done): String?` (spawned next-occurrence id), `delete(id): List<Task>`, `restore(List<Task>)`, `createProject(name, emoji): Project`, `updateProject(id, name, emoji)`, `deleteProject(id)`; Koin `single<TasksRepository>(createdAtStart = true)`; `FakeTasksRepository(tasks, projects, today = "2026-10-13")` with `taskFlow`, `projectFlow`, `calls: MutableList<String>`, `refreshResult`, `spawnOnComplete`. Fake call strings: `"refresh"`, `"create:<title>"`, `"update:<id>:<change.toString() joined by ','>"`, `"done:<id>:<bool>"`, `"delete:<id>"`, `"restore:<ids joined by ','>"`, `"createProject:<name>"`, `"updateProject:<id>:<name>:<emoji>"`, `"deleteProject:<id>"`. Fake ids: created tasks `"new1"`, `"new2"`…; created projects `"proj1"`…

- [ ] **Step 1: Domain interface** — `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/repository/TasksRepository.kt`

```kotlin
package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft

/** Offline-first: every write lands in the local DB immediately and reaches the server through the outbox. */
interface TasksRepository {
    /** All tasks on the device, newest first (server order). */
    val tasks: Flow<List<Task>>
    val projects: Flow<List<Project>>

    /** Re-reads tasks + projects from the server; local records with queued changes are kept. */
    suspend fun refresh(): Outcome<Unit>
    suspend fun create(draft: TaskDraft): Task
    suspend fun update(id: String, changes: List<TaskChange>)
    /** Completes or reopens; a no-op if already in that state. Completing a recurring task queues its next occurrence and returns its id. */
    suspend fun setDone(id: String, done: Boolean): String?
    /** Deletes the task and its subtasks; returns what was removed so the caller can offer Undo. */
    suspend fun delete(id: String): List<Task>
    /** Undo for [delete]: re-creates the tasks with their original ids. */
    suspend fun restore(removed: List<Task>)
    suspend fun createProject(name: String, emoji: String): Project
    suspend fun updateProject(id: String, name: String, emoji: String)
    /** Deletes the project; its tasks move to the Inbox (as on the server). */
    suspend fun deleteProject(id: String)
}
```

- [ ] **Step 2: Test fake** — `shared/testing/src/commonMain/kotlin/tech/beyondscale/mitra/testing/FakeTasksRepository.kt`

```kotlin
package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.repository.TasksRepository

class FakeTasksRepository(
    tasks: List<Task> = emptyList(),
    projects: List<Project> = emptyList(),
    private val today: String = "2026-10-13",
) : TasksRepository {
    val taskFlow = MutableStateFlow(tasks)
    val projectFlow = MutableStateFlow(projects)
    override val tasks: Flow<List<Task>> = taskFlow
    override val projects: Flow<List<Project>> = projectFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    /** What `setDone(…, true)` reports as the spawned next occurrence. */
    var spawnOnComplete: String? = null
    private var taskSeq = 0
    private var projectSeq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun create(draft: TaskDraft): Task {
        val t = Task(
            id = "new${++taskSeq}", title = draft.title.trim(), projectId = draft.projectId, due = draft.due, time = draft.time,
            estMin = draft.estMin, pri = draft.pri, status = draft.status, done = draft.status == TaskStatus.Done,
            notes = draft.notes, recurring = draft.recurring, parentId = draft.parentId, someday = draft.someday,
        )
        calls += "create:${t.title}"
        taskFlow.update { listOf(t) + it }
        return t
    }

    override suspend fun update(id: String, changes: List<TaskChange>) {
        calls += "update:$id:" + changes.joinToString(",")
        taskFlow.update { list -> list.map { if (it.id == id) changes.fold(it, ::applyChange) else it } }
    }

    override suspend fun setDone(id: String, done: Boolean): String? {
        calls += "done:$id:$done"
        taskFlow.update { list ->
            list.map { if (it.id == id) it.copy(done = done, status = if (done) TaskStatus.Done else TaskStatus.Todo, completedAt = if (done) today else null) else it }
        }
        return if (done) spawnOnComplete else null
    }

    override suspend fun delete(id: String): List<Task> {
        calls += "delete:$id"
        val removed = taskFlow.value.filter { it.id == id || it.parentId == id }
        taskFlow.update { list -> list.filter { it !in removed } }
        return removed
    }

    override suspend fun restore(removed: List<Task>) {
        calls += "restore:" + removed.joinToString(",") { it.id }
        taskFlow.update { removed + it }
    }

    override suspend fun createProject(name: String, emoji: String): Project {
        val p = Project("proj${++projectSeq}", name.trim(), emoji)
        calls += "createProject:${p.name}"
        projectFlow.update { it + p }
        return p
    }

    override suspend fun updateProject(id: String, name: String, emoji: String) {
        calls += "updateProject:$id:$name:$emoji"
        projectFlow.update { list -> list.map { if (it.id == id) it.copy(name = name, emoji = emoji) else it } }
    }

    override suspend fun deleteProject(id: String) {
        calls += "deleteProject:$id"
        projectFlow.update { list -> list.filter { it.id != id } }
        taskFlow.update { list -> list.map { if (it.projectId == id) it.copy(projectId = null) else it } }
    }

    private fun applyChange(t: Task, c: TaskChange): Task = when (c) {
        is TaskChange.Title -> t.copy(title = c.value)
        is TaskChange.ProjectId -> t.copy(projectId = c.value)
        is TaskChange.Due -> t.copy(due = c.value)
        is TaskChange.Time -> t.copy(time = c.value)
        is TaskChange.EstMin -> t.copy(estMin = c.value)
        is TaskChange.Pri -> t.copy(pri = c.value)
        is TaskChange.Status -> t.copy(status = c.value, done = c.value == TaskStatus.Done, completedAt = if (c.value == TaskStatus.Done) today else null)
        is TaskChange.Notes -> t.copy(notes = c.value)
        is TaskChange.Recurring -> t.copy(recurring = c.value)
        is TaskChange.Someday -> t.copy(someday = c.value)
        is TaskChange.Parent -> t.copy(parentId = c.value)
    }
}
```

- [ ] **Step 3: Write the failing tests**

`shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/repository/TaskPatchesTest.kt`:

```kotlin
package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskPatchesTest {
    private val base = Task("t1", "Write", due = "2026-10-13", pri = Priority.Low)

    @Test fun changesBecomeAPatchWithExplicitNulls() {
        val (t, patch) = applyChanges(base, listOf(TaskChange.Title("  Write v2 "), TaskChange.Due(null), TaskChange.Pri(Priority.High), TaskChange.EstMin(45)), "2026-10-14")
        assertEquals(base.copy(title = "Write v2", due = null, pri = Priority.High, estMin = 45), t)
        assertEquals("""{"title":"Write v2","due":null,"pri":"high","estMin":45}""", patch.toString())
    }

    @Test fun statusKeepsDoneAndCompletedAtCoherent() {
        val (done, p1) = applyChanges(base, listOf(TaskChange.Status(TaskStatus.Done)), "2026-10-14")
        assertEquals(true, done.done); assertEquals("2026-10-14", done.completedAt)
        assertEquals("""{"status":"done","done":true,"completedAt":"2026-10-14"}""", p1.toString())
        val (blocked, p2) = applyChanges(done, listOf(TaskChange.Status(TaskStatus.Blocked)), "2026-10-15")
        assertEquals(false, blocked.done); assertEquals(null, blocked.completedAt)
        assertEquals("""{"status":"blocked","done":false,"completedAt":null}""", p2.toString())
    }

    @Test fun completionReopensToTodoOnlyFromDone() {
        val doing = base.copy(status = TaskStatus.InProgress)
        val (closed, patch) = completion(doing, true, "2026-10-14")
        assertEquals(TaskStatus.Done, closed.status)
        assertEquals("""{"status":"done","done":true,"completedAt":"2026-10-14"}""", patch.toString())
        assertEquals(TaskStatus.Todo, completion(closed, false, "2026-10-14").first.status)
        assertEquals(TaskStatus.InProgress, completion(doing.copy(done = true), false, "2026-10-14").first.status)
    }
}
```

`shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImplTest.kt`:

```kotlin
package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TasksRepositoryImplTest {
    // 20:00 UTC on the 13th = 01:30 IST on the 14th: the device's "today" is 2026-10-14.
    private val clock = FixedClock(Instant.parse("2026-10-13T20:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: TasksRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        // Long debounce: tests drain explicitly.
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(TasksRepositoryImpl(api, db, prefs, writer, engine, clock, io), engine, prefs)
    }

    private fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
    private fun taskJson(id: String, title: String = id, done: Boolean = false, due: String? = null, projectId: String? = null, parentId: String? = null) =
        """{"id":"$id","title":"$title","status":"${if (done) "done" else "todo"}","done":$done,"due":${q(due)},"projectId":${q(projectId)},"parentId":${q(parentId)},"notes":"","source":"user","createdAt":"2026-10-01T00:00:00.000Z"}"""
    private fun projectJson(id: String, name: String) = """{"id":"$id","name":"$name","emoji":"💼","createdAt":"2026-10-01T00:00:00.000Z"}"""
    private fun bootstrap(tasks: List<String>, projects: List<String> = emptyList()) =
        """{"tasks":[${tasks.joinToString(",")}],"projects":[${projects.joinToString(",")}]}"""

    private fun seed(vararg tasks: Task) = tasks.forEach { db.tasksQueries.upsert(it.toRow()) }
    private fun local(id: String) = db.tasksQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    private fun ids() = db.tasksQueries.selectIds().executeAsList().sorted()
    private fun queue() = db.outboxQueries.selectAll().executeAsList()

    @Test fun refreshMirrorsTheServer() = runTest {
        val e = env()
        seed(Task("gone", "Deleted on the web"), Task("t1", "Stale title"))
        server.routes["GET /api/tasks/bootstrap"] = { json(bootstrap(listOf(taskJson("t1", "Fresh"), taskJson("t2")), listOf(projectJson("p1", "Work")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf("t1", "t2"), ids())
        assertEquals("Fresh", local("t1")!!.title)
        assertEquals(listOf(Project("p1", "Work", "💼", null, "2026-10-01T00:00:00.000Z")), e.repo.projects.first())
    }

    @Test fun refreshKeepsRowsWithPendingChanges() = runTest {
        val e = env()
        seed(Task("t1", "Server title"))
        server.offline = true
        e.repo.update("t1", listOf(TaskChange.Title("Local title")))
        val mine = e.repo.create(TaskDraft("Created offline"))
        server.offline = false
        server.routes["GET /api/tasks/bootstrap"] = { json(bootstrap(listOf(taskJson("t1", "Server title")))) }
        e.repo.refresh()
        assertEquals("Local title", local("t1")!!.title)
        assertNotNull(local(mine.id))
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        seed(Task("t1", "Write report", due = "2026-10-14"))
        var gets = 0
        server.routes["PATCH /api/tasks/t1"] = { json(taskJson("t1", "Write report", done = true, due = "2026-10-14")) }
        server.routes["GET /api/tasks/bootstrap"] = {
            gets++
            if (gets == 1) {
                // While the first snapshot is being built the user completes the task and that PATCH is acknowledged.
                e.repo.setDone("t1", true)
                e.engine.drain()
                json(bootstrap(listOf(taskJson("t1", "Write report", done = false, due = "2026-10-14")))) // stale
            } else {
                json(bootstrap(listOf(taskJson("t1", "Write report", done = true, due = "2026-10-14"))))
            }
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(2, gets)
        assertTrue(local("t1")!!.done)
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/tasks/bootstrap"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID) // logout while the request is on the wire
            json(bootstrap(listOf(taskJson("t1"))))
        }
        e.repo.refresh()
        assertEquals(emptyList(), ids())
        assertIs<Outcome.Err>(e.repo.refresh()) // no account on the device: nothing is fetched
        assertEquals(1, server.log.size)
    }

    @Test fun createQueuesPostWithClientIdAndExplicitNulls() = runTest {
        val e = env()
        server.offline = true
        val t = e.repo.create(TaskDraft("  Call John  ", due = "2026-10-15", pri = Priority.High))
        assertEquals("Call John", t.title)
        assertEquals(t, local(t.id))
        val row = queue().single()
        assertEquals(Triple("POST", "tasks", t.id), Triple(row.method, row.path, row.entityId))
        assertEquals(
            """{"id":"${t.id}","title":"Call John","projectId":null,"goalId":null,"habitId":null,"due":"2026-10-15","time":null,"estMin":null,"pri":"high","status":"todo","done":false,"completedAt":null,"parentId":null,"notes":"","recurring":null,"amount":null,"someday":false,"source":"user"}""",
            row.body,
        )
    }

    @Test fun completeSendsIdempotentPatchWithLocalDate() = runTest {
        val e = env()
        seed(Task("t1", "Write", due = "2026-10-14"))
        server.offline = true
        assertNull(e.repo.setDone("t1", true))
        e.repo.setDone("t1", true) // second tap / widget + app: no new write
        val row = queue().single()
        assertEquals("PATCH" to "tasks/t1", row.method to row.path)
        assertEquals("""{"status":"done","done":true,"completedAt":"2026-10-14"}""", row.body)
        assertEquals("2026-10-14", local("t1")!!.completedAt)

        server.offline = false
        val bodies = mutableListOf<String>()
        server.routes["PATCH /api/tasks/t1"] = { req -> bodies += req.body.toByteArray().decodeToString(); json(taskJson("t1", "Write", done = true, due = "2026-10-14")) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("""{"status":"done","done":true,"completedAt":"2026-10-14"}"""), bodies)
        assertTrue(local("t1")!!.done)
    }

    @Test fun completingRecurringSpawnsTheNextOccurrenceOnce() = runTest {
        val e = env()
        seed(Task("rent", "Pay rent", due = "2026-01-31", recurring = "Monthly", pri = Priority.High))
        server.offline = true
        val nextId = assertNotNull(e.repo.setDone("rent", true))
        val next = local(nextId)!!
        assertEquals("2026-03-03", next.due)
        assertEquals("recurring", next.source)
        assertFalse(next.done)
        assertEquals(Priority.High, next.pri)

        e.repo.setDone("rent", false)
        assertNull(e.repo.setDone("rent", true)) // an open occurrence already exists
        assertEquals(listOf("PATCH tasks/rent", "POST tasks", "PATCH tasks/rent"), queue().map { "${it.method} ${it.path}" })
    }

    @Test fun moveIntoProjectCreatedOfflineReachesServerInOrder() = runTest {
        val e = env()
        seed(Task("t1", "Draft deck"))
        server.offline = true
        e.repo.update("t1", listOf(TaskChange.Title("Draft pitch deck")))
        val p = e.repo.createProject("Pitch", "🚀")
        e.repo.update("t1", listOf(TaskChange.ProjectId(p.id)))

        server.offline = false
        val created = mutableSetOf<String>()
        server.routes["POST /api/projects"] = { created += p.id; json(projectJson(p.id, "Pitch")) }
        server.routes["PATCH /api/tasks/t1"] = { req ->
            val sent = req.body.toByteArray().decodeToString()
            if (p.id in sent && p.id !in created) json(errorJson("NOT_FOUND", "Project not found"), HttpStatusCode.NotFound)
            else json(taskJson("t1", "Draft pitch deck", projectId = if (p.id in sent) p.id else null))
        }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("PATCH /api/tasks/t1", "POST /api/projects", "PATCH /api/tasks/t1"), server.log)
        assertEquals(p.id, local("t1")!!.projectId)
        assertEquals("Draft pitch deck", local("t1")!!.title)
    }

    @Test fun rejectedWriteRestoresTheServerCopy() = runTest {
        val e = env()
        seed(Task("t1", "Server title"))
        server.offline = true
        e.repo.update("t1", listOf(TaskChange.Title("Local title")))
        server.offline = false
        server.routes["PATCH /api/tasks/t1"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        server.routes["GET /api/tasks/bootstrap"] = { json(bootstrap(listOf(taskJson("t1", "Server title")))) }
        e.engine.drain()
        assertEquals("Server title", local("t1")!!.title)
        assertEquals("Couldn't save · Task not found", e.engine.messages.first())
    }

    @Test fun deleteTakesSubtasksAndUndoRecreatesThem() = runTest {
        val e = env()
        seed(Task("p", "Parent"), Task("c", "Child", parentId = "p"), Task("other", "Other"))
        server.offline = true
        val removed = e.repo.delete("p")
        assertEquals(listOf("p", "c"), removed.map { it.id })
        assertEquals(listOf("other"), ids())

        // A refresh while the DELETE is still queued must not bring the parent or its subtask back.
        server.offline = false
        server.routes["GET /api/tasks/bootstrap"] = {
            json(bootstrap(listOf(taskJson("p", "Parent"), taskJson("c", "Child", parentId = "p"), taskJson("other", "Other"))))
        }
        e.repo.refresh()
        assertEquals(listOf("other"), ids())

        e.repo.restore(removed)
        assertEquals(listOf("c", "other", "p"), ids())
        assertEquals(listOf("DELETE tasks/p", "POST tasks", "POST tasks"), queue().map { "${it.method} ${it.path}" })
        assertEquals(listOf("p", "c"), queue().drop(1).map { it.entityId })
    }

    @Test fun deletingAProjectMovesItsTasksToTheInbox() = runTest {
        val e = env()
        db.projectsQueries.upsert(Project("p1", "Work").toRow())
        seed(Task("t1", "In project", projectId = "p1"))
        server.offline = true
        e.repo.deleteProject("p1")
        assertNull(local("t1")!!.projectId)
        assertEquals(emptyList(), e.repo.projects.first())
        assertEquals("DELETE projects/p1", queue().single().let { "${it.method} ${it.path}" })
    }
}
```

- [ ] **Step 4: Run them to see them fail**

Run: `./gradlew :shared:data:testAndroidHostTest --tests '*Task*'`
Expected: compilation FAILS (`TasksRepositoryImpl`, `applyChanges`, `toRow` unresolved).

- [ ] **Step 5: Entity names** — in `PendingSettings.kt` replace the `OutboxEntities` object:

```kotlin
object OutboxEntities {
    const val USER_SETTINGS = "user-settings"
    const val TASK = "task"
    const val PROJECT = "project"
}
```

- [ ] **Step 6: DTOs** — `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/remote/TaskDtos.kt`

```kotlin
package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import kotlin.math.roundToLong

/** Shape of `taskOut()` in mitra-backend/src/tasks/mapper.ts (undefined fields are simply absent). */
@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val goalId: String? = null,
    val habitId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val estMin: Int? = null,
    val pri: String? = null,
    val status: String = "todo",
    val done: Boolean = false,
    val completedAt: String? = null,
    val parentId: String? = null,
    val notes: String = "",
    val recurring: String? = null,
    /** Rupees. */
    val amount: Double? = null,
    val someday: Boolean? = null,
    val source: String = "user",
    val createdAt: String = "",
)

@Serializable
data class ProjectDto(val id: String, val name: String, val emoji: String = "🚀", val goalId: String? = null, val createdAt: String = "")

@Serializable
data class TasksBootstrapDto(val tasks: List<TaskDto> = emptyList(), val projects: List<ProjectDto> = emptyList())

fun TaskDto.toDomain(): Task = Task(
    id = id, title = title, projectId = projectId, goalId = goalId, habitId = habitId, due = due, time = time,
    estMin = estMin, pri = Priority.fromWire(pri), status = TaskStatus.fromWire(status), done = done,
    completedAt = completedAt, parentId = parentId, notes = notes, recurring = recurring,
    amountPaise = amount?.let { (it * 100).roundToLong() }, someday = someday == true, source = source, createdAt = createdAt,
)

fun ProjectDto.toDomain(): Project = Project(id, name, emoji, goalId, createdAt)

/** `POST tasks` body: every field present, unset ones as JSON null (the web's `nulls()`). */
fun Task.createBody(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("projectId", projectId)
    put("goalId", goalId)
    put("habitId", habitId)
    put("due", due)
    put("time", time)
    put("estMin", estMin)
    put("pri", pri?.wire)
    put("status", status.wire)
    put("done", done)
    put("completedAt", completedAt)
    put("parentId", parentId)
    put("notes", notes)
    put("recurring", recurring)
    put("amount", amountPaise?.let { it / 100.0 })
    put("someday", someday)
    put("source", source)
}
```

- [ ] **Step 7: Row mappers** — `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/TaskRows.kt`

```kotlin
package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Projects
import tech.beyondscale.mitra.data.db.Tasks
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus

fun Tasks.toDomain(): Task = Task(
    id = id, title = title, projectId = projectId, goalId = goalId, habitId = habitId, due = due, time = time,
    estMin = estMin?.toInt(), pri = Priority.fromWire(pri), status = TaskStatus.fromWire(status), done = done != 0L,
    completedAt = completedAt, parentId = parentId, notes = notes, recurring = recurring, amountPaise = amountPaise,
    someday = someday != 0L, source = source, createdAt = createdAt,
)

fun Task.toRow(): Tasks = Tasks(
    id, title, projectId, goalId, habitId, due, time, estMin?.toLong(), pri?.wire, status.wire, if (done) 1L else 0L,
    completedAt, parentId, notes, recurring, amountPaise, if (someday) 1L else 0L, source, createdAt,
)

fun Projects.toDomain(): Project = Project(id, name, emoji, goalId, createdAt)
fun Project.toRow(): Projects = Projects(id, name, emoji, goalId, createdAt)
```

- [ ] **Step 8: Pure patch builders** — `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/TaskPatches.kt`

```kotlin
package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskStatus

/** Applies field edits locally and builds the matching `PATCH tasks/:id` body (null = clear on the server). */
internal fun applyChanges(old: Task, changes: List<TaskChange>, today: String): Pair<Task, JsonObject> {
    var t = old
    val patch = linkedMapOf<String, JsonElement>()
    for (c in changes) when (c) {
        is TaskChange.Title -> c.value.trim().let { t = t.copy(title = it); patch["title"] = JsonPrimitive(it) }
        is TaskChange.ProjectId -> { t = t.copy(projectId = c.value); patch["projectId"] = JsonPrimitive(c.value) }
        is TaskChange.Due -> { t = t.copy(due = c.value); patch["due"] = JsonPrimitive(c.value) }
        is TaskChange.Time -> { t = t.copy(time = c.value); patch["time"] = JsonPrimitive(c.value) }
        is TaskChange.EstMin -> { t = t.copy(estMin = c.value); patch["estMin"] = JsonPrimitive(c.value) }
        is TaskChange.Pri -> { t = t.copy(pri = c.value); patch["pri"] = JsonPrimitive(c.value?.wire) }
        is TaskChange.Status -> {
            val done = c.value == TaskStatus.Done
            val completedAt = if (!done) null else if (t.done) t.completedAt ?: today else today
            t = t.copy(status = c.value, done = done, completedAt = completedAt)
            patch["status"] = JsonPrimitive(c.value.wire)
            patch["done"] = JsonPrimitive(done)
            patch["completedAt"] = JsonPrimitive(completedAt)
        }
        is TaskChange.Notes -> { t = t.copy(notes = c.value); patch["notes"] = JsonPrimitive(c.value) }
        is TaskChange.Recurring -> { t = t.copy(recurring = c.value); patch["recurring"] = JsonPrimitive(c.value) }
        is TaskChange.Someday -> { t = t.copy(someday = c.value); patch["someday"] = JsonPrimitive(c.value) }
        is TaskChange.Parent -> { t = t.copy(parentId = c.value); patch["parentId"] = JsonPrimitive(c.value) }
    }
    return t to JsonObject(patch)
}

/**
 * Complete / reopen. Always sends status + done + completedAt together so a coalesced earlier status change can't
 * leave the server with `done: true, status: blocked`; idempotent when resent. `completedAt` is the local date.
 */
internal fun completion(old: Task, done: Boolean, today: String): Pair<Task, JsonObject> {
    val status = when {
        done -> TaskStatus.Done
        old.status == TaskStatus.Done -> TaskStatus.Todo
        else -> old.status
    }
    val completedAt = if (done) today else null
    return old.copy(done = done, status = status, completedAt = completedAt) to buildJsonObject {
        put("status", status.wire)
        put("done", done)
        put("completedAt", completedAt)
    }
}
```

- [ ] **Step 9: The repository** — `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImpl.kt`

```kotlin
package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.ProjectDto
import tech.beyondscale.mitra.data.remote.TaskDto
import tech.beyondscale.mitra.data.remote.TasksBootstrapDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.nextDue

class TasksRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TasksRepository {
    /**
     * Bumped before every local write and every applied server echo. A refresh snapshots this and
     * [SyncEngine.acknowledged] before its GET and discards its result if either moved: the snapshot may predate a
     * change that has since been written locally or acknowledged by the server.
     */
    private val writes = MutableStateFlow(0L)

    override val tasks: Flow<List<Task>> =
        db.tasksQueries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }
    override val projects: Flow<List<Project>> =
        db.projectsQueries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    init {
        engine.register(TASK, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(TaskDto.serializer(), response) ?: return // DELETE answers { ok: true }
                applyEcho(TASK, dto.id) {
                    // Deleted locally while the write was in flight: don't resurrect it.
                    if (db.tasksQueries.selectById(dto.id).executeAsOneOrNull() != null) db.tasksQueries.upsert(dto.toDomain().toRow())
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(PROJECT, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(ProjectDto.serializer(), response) ?: return
                applyEcho(PROJECT, dto.id) {
                    if (db.projectsQueries.selectById(dto.id).executeAsOneOrNull() != null) db.projectsQueries.upsert(dto.toDomain().toRow())
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<TasksBootstrapDto>(HttpMethod.Get, "tasks/bootstrap")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { applyBootstrap(body); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        // Still racing writes; the refresh after the queue drains will reconcile.
        return Outcome.Ok(Unit)
    }

    /** Upsert everything the server has, delete what it no longer has — except records with queued changes. */
    private fun applyBootstrap(body: TasksBootstrapDto) {
        val pendingTasks = pendingIds(TASK)
        val pendingProjects = pendingIds(PROJECT)
        val localTasks = db.tasksQueries.selectIds().executeAsList().toSet()
        // Subtasks of a task whose DELETE is still queued: the server keeps them until that delete lands.
        val incoming = body.tasks.filterNot { it.parentId != null && it.parentId in pendingTasks && it.parentId !in localTasks }
        val serverTaskIds = incoming.map { it.id }.toSet()
        localTasks.filter { it !in serverTaskIds && it !in pendingTasks }.forEach { db.tasksQueries.delete(it) }
        incoming.filter { it.id !in pendingTasks }.forEach { db.tasksQueries.upsert(it.toDomain().toRow()) }

        val serverProjectIds = body.projects.map { it.id }.toSet()
        db.projectsQueries.selectIds().executeAsList().filter { it !in serverProjectIds && it !in pendingProjects }.forEach { db.projectsQueries.delete(it) }
        body.projects.filter { it.id !in pendingProjects }.forEach { db.projectsQueries.upsert(it.toDomain().toRow()) }
    }

    /* ---------- task writes ---------- */

    override suspend fun create(draft: TaskDraft): Task {
        val today = clock.today()
        val done = draft.status == TaskStatus.Done
        val task = Task(
            id = newId(), title = draft.title.trim(), projectId = draft.projectId, goalId = draft.goalId, due = draft.due,
            time = draft.time, estMin = draft.estMin, pri = draft.pri, status = draft.status, done = done,
            completedAt = if (done) today else null, parentId = draft.parentId, notes = draft.notes,
            recurring = draft.recurring, someday = draft.someday, createdAt = clock.now().toString(),
        )
        write { insertTask(task) }
        return task
    }

    override suspend fun update(id: String, changes: List<TaskChange>) {
        if (changes.isEmpty()) return
        val today = clock.today()
        write {
            val old = selectTask(id) ?: return@write
            val (updated, patch) = applyChanges(old, changes, today)
            db.tasksQueries.upsert(updated.toRow())
            patchTask(id, patch)
        }
    }

    override suspend fun setDone(id: String, done: Boolean): String? {
        val today = clock.today()
        var spawned: String? = null
        write {
            val old = selectTask(id) ?: return@write
            if (old.done == done) return@write
            val (updated, patch) = completion(old, done, today)
            db.tasksQueries.upsert(updated.toRow())
            patchTask(id, patch)
            val rule = old.recurring
            if (done && rule != null) {
                val all = db.tasksQueries.selectAll().executeAsList().map { it.toDomain() }
                // Same guard as the server's toggle: never two open occurrences of one series.
                if (all.none { it.id != id && !it.done && it.title == old.title && it.recurring == rule }) {
                    val next = old.copy(
                        id = newId(), due = nextDue(old.due, rule, today), status = TaskStatus.Todo, done = false,
                        completedAt = null, someday = false, source = "recurring", createdAt = clock.now().toString(),
                    )
                    insertTask(next)
                    spawned = next.id
                }
            }
        }
        return spawned
    }

    override suspend fun delete(id: String): List<Task> {
        var removed = emptyList<Task>()
        write {
            val target = selectTask(id) ?: return@write
            removed = listOf(target) + db.tasksQueries.selectAll().executeAsList().map { it.toDomain() }.filter { it.parentId == id }
            removed.forEach { db.tasksQueries.delete(it.id) }
            outbox.enqueue(TASK, "DELETE", "tasks/$id", null, entityId = id) // the server cascades to subtasks
        }
        return removed
    }

    override suspend fun restore(removed: List<Task>) {
        if (removed.isEmpty()) return
        write { removed.sortedBy { it.parentId != null }.forEach { insertTask(it) } }
    }

    /* ---------- project writes ---------- */

    override suspend fun createProject(name: String, emoji: String): Project {
        val p = Project(newId(), name.trim(), emoji, null, clock.now().toString())
        write {
            db.projectsQueries.upsert(p.toRow())
            outbox.enqueue(PROJECT, "POST", "projects", buildJsonObject { put("id", p.id); put("name", p.name); put("emoji", p.emoji) }, entityId = p.id)
        }
        return p
    }

    override suspend fun updateProject(id: String, name: String, emoji: String) {
        write {
            val old = db.projectsQueries.selectById(id).executeAsOneOrNull()?.toDomain() ?: return@write
            db.projectsQueries.upsert(old.copy(name = name.trim(), emoji = emoji).toRow())
            outbox.enqueue(
                PROJECT, "PATCH", "projects/$id", buildJsonObject { put("name", name.trim()); put("emoji", emoji) },
                coalesceKey = "project:$id", entityId = id,
            )
        }
    }

    override suspend fun deleteProject(id: String) {
        write {
            db.projectsQueries.delete(id)
            db.tasksQueries.clearProject(id)
            outbox.enqueue(PROJECT, "DELETE", "projects/$id", null, entityId = id)
        }
    }

    /* ---------- helpers ---------- */

    /** Local change + outbox row in one transaction. The counter is bumped first (see [writes]). */
    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    private fun selectTask(id: String): Task? = db.tasksQueries.selectById(id).executeAsOneOrNull()?.toDomain()

    private fun insertTask(t: Task) {
        db.tasksQueries.upsert(t.toRow())
        outbox.enqueue(TASK, "POST", "tasks", t.createBody(), entityId = t.id)
    }

    private fun patchTask(id: String, patch: JsonObject) =
        outbox.enqueue(TASK, "PATCH", "tasks/$id", patch, coalesceKey = "task:$id", entityId = id)

    private fun pendingIds(entity: String): Set<String> =
        db.outboxQueries.pendingEntityIds(entity).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement?): T? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    /** Server copy of one record after its write was acknowledged — unless a newer local change to it is still queued. */
    private suspend fun applyEcho(entity: String, id: String, apply: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { if (id !in pendingIds(entity)) apply() } }
    }

    companion object {
        const val TASK = OutboxEntities.TASK
        const val PROJECT = OutboxEntities.PROJECT
        private const val REFRESH_ATTEMPTS = 3
    }
}
```

- [ ] **Step 10: Wire it** — in `DataModule.kt` add the import `tech.beyondscale.mitra.data.repository.TasksRepositoryImpl`, `tech.beyondscale.mitra.domain.repository.TasksRepository` and, after the `SettingsRepository` line:

```kotlin
    // Eager: registers the task/project sync handlers, which must exist before the first drain.
    single<TasksRepository>(createdAtStart = true) { TasksRepositoryImpl(get(), get(), get(), get(), get(), get()) }
```

- [ ] **Step 11: Run all shared tests**

Run: `./gradlew testAndroidHostTest`
Expected: PASS (new `TasksRepositoryImplTest` ×11, `TaskPatchesTest` ×3; `AppModulesTest` still resolves the graph with the eager repository).

- [ ] **Step 12: Commit**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai"
git add mitra-mobile/shared/domain mitra-mobile/shared/data mitra-mobile/shared/testing
git commit -m "feat(mobile): offline-first TasksRepository with bootstrap refresh, idempotent completion and server-wins rejection

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

> **Format note for Tasks 5–16:** at the user's request the remaining tasks are *briefs*: files, exact interfaces, requirements, named tests with expected values, and the verify command. Implementers write the code TDD-style (failing test → run → implement → run → commit) following the Phase 1 patterns and the code in Tasks 1–4. Commit messages end with the Co-Authored-By line; `git add` only `mitra-mobile/…` paths.

### Task 5: Sync lifecycle — module refresher, periodic sync, foreground hook, debug-only network security config

**Files:**
- Create: `shared/domain/…/domain/repository/ModuleRefresher.kt`
- Create: `shared/data/…/data/sync/ModuleRefresherImpl.kt`, `shared/data/…/data/sync/PeriodicSync.kt`
- Modify: `shared/data/…/data/di/DataModule.kt`, `shared/app/src/commonTest/…/app/AppModulesTest.kt` (platform module binds `PeriodicSync {}`)
- Modify: `androidApp/build.gradle.kts`, `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/kotlin/…/android/{MitraApplication,MainActivity,SyncWorker}.kt`
- Create: `androidApp/src/debug/AndroidManifest.xml`
- Test: `shared/data/src/commonTest/…/data/sync/ModuleRefresherImplTest.kt`, `…/PeriodicSyncControllerTest.kt`

**Interfaces:**
- Produces (domain): `interface ModuleRefresher { fun onForeground() }`.
- Produces (data): `class ModuleRefresherImpl(session: Flow<SessionState>, drained: Flow<Unit>, clock: AppClock, scope: CoroutineScope, modules: Map<String, suspend () -> Unit>, minIntervalMs: Long = 60_000) : ModuleRefresher`; `fun interface PeriodicSync { fun setEnabled(enabled: Boolean) }` (platform binding, like `SyncScheduler`); `class PeriodicSyncController(pending: Flow<Long>, periodic: PeriodicSync, scope: CoroutineScope)`.
- Produces (androidApp): `WorkManagerPeriodicSync(context) : PeriodicSync` (unique periodic work `"mitra-sync-periodic"`, 15 min, `NetworkType.CONNECTED`, `ExistingPeriodicWorkPolicy.KEEP`, reuses `SyncWorker`; disabled → `cancelUniqueWork`).

**Requirements:**
- Spec §6 triggers: refresh every module once when the session becomes `SignedIn` for a user id different from the last one seen (reset all throttles then; mark `"user"` fresh because `restore()/login()` just fetched it); on `onForeground()` refresh modules whose last refresh is ≥ 60 s old (including `"user"` → `AuthRepository.refresh()`), only while signed in; on `drained` refresh stale modules except `"user"`. Each module runs in its own `scope.launch`, a throwing module never stops the others; throttle map guarded by a `Mutex`. Signed out → nothing runs.
- DI: `single<ModuleRefresher>(createdAtStart = true)` with `modules: Map<String, suspend () -> Unit> = mapOf("user" to suspend { auth.refresh(); Unit }, "tasks" to suspend { tasks.refresh(); Unit })` (resolve `auth`/`tasks` eagerly in the definition); `single(createdAtStart = true) { PeriodicSyncController(get<SyncController>().pendingCount, get(), get<AppScope>().scope) }` — it maps `pending > 0` through `distinctUntilChanged()` to `setEnabled`.
- `MainActivity.onStart`: keep `sync.requestSync()`, add `refresher.onForeground()` (inject `ModuleRefresher`).
- `MitraApplication`: bind `single<PeriodicSync> { WorkManagerPeriodicSync(this@MitraApplication) }` next to the `SyncScheduler` binding.
- Debug network security config (Phase 1 follow-up): remove `manifestPlaceholders["cleartext"]` and set `android:usesCleartextTraffic="false"` in the main manifest. In `androidApp/build.gradle.kts` add an abstract task class `DevNetworkSecurityConfig` (`@Input hosts: SetProperty<String>`, `@OutputDirectory outputDir: DirectoryProperty`) that writes `xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="false"/>` and one `<domain-config cleartextTrafficPermitted="true">` listing `10.0.2.2`, `localhost`, `127.0.0.1` and `java.net.URI(mitra.apiUrl).host` from `local.properties` (same `apiUrl` value already used for `BuildConfig.API_URL`). Register it as `devNetworkSecurityConfig` and attach only to debug: `androidComponents.onVariants(selector().withBuildType("debug")) { it.sources.res?.addGeneratedSourceDirectory(task, DevNetworkSecurityConfig::outputDir) }`. `src/debug/AndroidManifest.xml` adds `android:networkSecurityConfig="@xml/network_security_config"` on `<application>`. Release has no cleartext at all.

**Tests (ModuleRefresherImplTest, FixedClock + `MutableStateFlow<SessionState>` + `MutableSharedFlow<Unit>` drained, modules record calls into a list, `runTest` + `runCurrent`):**
- `signInRefreshesEveryModuleExceptUserOnce` → after `SignedIn(testUser())`: calls == `["tasks"]`; re-emitting the same user → still `["tasks"]`.
- `foregroundRefreshesOnlyStaleModules` → foreground at +30 s: no new calls; at +61 s: `"user"` and `"tasks"` both called once more.
- `signedOutForegroundDoesNothing` → calls empty.
- `drainedRefreshesStaleModulesButNotUser` → after +61 s and a drained emission: `"tasks"` called, `"user"` not.
- `newAccountResetsThrottle` → SignedIn(u1) then SignedIn(testUser(id = "u2")) within 5 s → `"tasks"` called twice.
- `failingModuleDoesNotStopOthers` → `"tasks"` throws, an extra `"habits"` module still runs.
- `PeriodicSyncControllerTest.enablesOnlyWhilePending` → pending 0,2,3,0 ⇒ `setEnabled` calls `[false, true, false]`.

**Verify:** `./gradlew testAndroidHostTest :androidApp:assembleDebug` then
`cat androidApp/build/generated/res/devNetworkSecurity/xml/network_security_config.xml` (lists the LAN host) and
`~/Library/Android/sdk/build-tools/*/aapt2 dump xmltree --file AndroidManifest.xml androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep -i networkSecurityConfig` (present; absent in `assembleRelease -Pmitra.releaseApiUrl=https://example.com/api/`).

---

### Task 6: Design system + feature plumbing — chips, progress, check circle, swipe row, snackbar controller, UI events

**Files:**
- Create: `shared/designsystem/…/designsystem/components/{Chips,Progress,Check,SwipeRow,Snackbar}.kt`
- Modify: `shared/designsystem/…/designsystem/components/TextField.kt` (add `fieldModifier: Modifier = Modifier`, `singleLine: Boolean = true`, `minLines: Int = 1`, `placeholder: String? = null` — defaults keep Phase 1 callers unchanged)
- Create: `shared/feature/…/feature/common/UiEvent.kt`
- Test: `shared/designsystem/src/commonTest/…/designsystem/components/SwipeOutcomeTest.kt`, `shared/feature/src/commonTest/…/feature/common/EventQueueTest.kt`

**Interfaces (produced):**
- `enum class ChipTone { Neutral, Accent, Danger, Warning, Info, Outline }`; `@Composable fun MitraChip(text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.Neutral, icon: ImageVector? = null, selected: Boolean = false, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null)` (min height 32 dp, 48 dp touch target when clickable; `selected` ⇒ accentSoft/accent).
- `@Composable fun MitraProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Mitra.colors.accent, height: Dp = 6.dp)` (track = `Mitra.colors.track`, fraction clamped 0..1).
- `@Composable fun CheckCircle(checked: Boolean, onToggle: () -> Unit, label: String, modifier: Modifier = Modifier)` — 22 dp circle inside a 48 dp target, `Role.Checkbox`, `toggleable` semantics, contentDescription `label`; checked = accent fill + `MitraIcons.Check` in `onAccent`.
- `enum class SwipeOutcome { None, Right, Left }`; `fun swipeOutcome(offsetPx: Float, widthPx: Float, threshold: Float = 0.35f): SwipeOutcome`; `@Composable fun SwipeRow(onSwipeRight: (() -> Unit)?, onSwipeLeft: (() -> Unit)?, rightLabel: String, rightIcon: ImageVector, leftLabel: String, leftIcon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit)` — `Animatable` offset + `pointerInput { detectHorizontalDragGestures }`, direction limited to non-null callbacks; past threshold on release: animate out, invoke callback, snap back to 0; else animate back. Background: right = successSoft + check icon/label at start; left = warningSoft + clock icon/label at end. Haptic `HapticFeedbackType.GestureThresholdActivate` once when the drag crosses the threshold.
- `class SnackbarController(val host: SnackbarHostState, private val scope: CoroutineScope) { fun show(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) }` (dismisses the current snackbar first, `SnackbarDuration.Short`); `val LocalSnackbar = staticCompositionLocalOf<SnackbarController> { error("No SnackbarController") }`.
- `enum class HapticKind { Confirm, Reject, LongPress }`; `sealed interface UiEvent<out A> { data class Snack<out A>(val text: String, val undo: A? = null); data class Haptic(val kind: HapticKind) : UiEvent<Nothing>; data object Close : UiEvent<Nothing> }`; `class EventQueue<A> { val events: Flow<UiEvent<A>>; fun send(e: UiEvent<A>) }` (Channel.BUFFERED + `receiveAsFlow`); `@Composable fun <A> EventEffect(events: Flow<UiEvent<A>>, onAction: (A) -> Unit, onClose: () -> Unit = {})` — Snack → `LocalSnackbar.current.show(text, "Undo" if undo != null) { onAction(undo) }` (uses the **shell's** scope so Undo survives the screen leaving composition), Haptic → `LocalHapticFeedback` with `HapticFeedbackType.Confirm/Reject/LongPress` (verified present in compose-ui 1.12.1), Close → `onClose()`.

**Requirements:** Noir tokens only (`Mitra.colors`, `MitraDimens`); no Android imports; Phase 1 composables keep compiling unchanged.

**Tests:**
- `SwipeOutcomeTest`: `swipeOutcome(0f, 1000f) == None`; `(340f,1000f) == None`; `(351f,1000f) == Right`; `(-351f,1000f) == Left`; `(10f, 0f) == None` (unmeasured row).
- `EventQueueTest`: events sent before anyone collects are delivered in order (`Snack("a")`, `Haptic(Confirm)`, `Close`) via `events.take(3).toList()`.

**Verify:** `./gradlew :shared:designsystem:testAndroidHostTest :shared:feature:testAndroidHostTest`

---

### Task 7: Tasks screen — Inbox / Today / Upcoming / Projects tabs, task row, swipe + Undo + haptics, filters, pull-to-refresh

**Files:**
- Create: `shared/feature/…/feature/tasks/{TaskRow,TasksViewModel,TasksScreen}.kt`
- Modify: `shared/feature/…/feature/di/FeatureModule.kt` (`viewModelOf(::TasksViewModel)`)
- Test: `shared/feature/src/commonTest/…/feature/tasks/TasksViewModelTest.kt`, `…/TaskRowUiTest.kt`

**Interfaces:**
- Consumes: `TasksRepository`, `AppClock` (`dayFlow`, `today`), Task 1 selectors, Task 6 `SwipeRow`, `CheckCircle`, `MitraChip`, `MitraProgressBar`, `EventQueue`, `EventEffect`.
- Produces: `data class TaskRowUi(id, title, done, overdue, due: String?, project: String?, est: String?, sub: String?, recurring: Boolean, ai: Boolean, parent: String?, pri: Priority?)`; `internal fun taskRowUi(t: Task, all: List<Task>, projects: Map<String, Project>, today: String, showProject: Boolean = true): TaskRowUi` (port of `taskVM` in `mitra-frontend/src/screens/vm.ts`: est = `minsToHm` when > 0, sub = `"done/total"` of children, parent title); `@Composable fun TaskRow(row: TaskRowUi, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null, selected: Boolean = false)` (CheckCircle, title struck-through + muted when done, one meta line: clock + due in `danger` when overdue · project · est · sub · ↻ when recurring · "AI" chip · "↳ parent"; 4×28 dp priority bar: high danger / med warning / low info); `@Composable fun SwipeTaskRow(...)` wrapping it (right = "Complete" only when not done, left = "Tomorrow").
- `enum class TasksTab(val label: String) { Inbox, Today, Upcoming, Projects }`; `data class UpcomingGroupUi(key, label, rows)`; `data class ProjectCardUi(id, emoji, name, open: Int, pct: Int)`; `data class TasksUiState(tab = Today, today: String = "", counts: TaskCounts, inbox, triageProjects: List<Project> /* first 3 */, overdue, todayRows, openCount, doneCount, showDone = true, planned: String = "0m", doneTime: String = "0m", plannedFraction: Float, focusLine: String, upcoming: List<UpcomingGroupUi>, projects: List<ProjectCardUi>, filters: TaskFilters, projectOptions: List<Project>, refreshing: Boolean)`.
- `sealed interface TasksAction`: `SelectTab(tab)`, `Toggle(id)`, `Reschedule(id)` (swipe left → tomorrow), `UndoComplete(id, spawnedId: String?)`, `SetDue(id, due: String?)`, `Triage(id, change: TaskChange, label)`, `UndoTriage(id)`, `MoveOverdueToToday`, `RestoreDues(previous: Map<String, String?>)`, `AddToInbox(text)`, `DeleteCreated(id)`, `SetFilters(filters)`, `ToggleShowDone`, `Refresh`.
- `class TasksViewModel(tasks: TasksRepository, clock: AppClock) : ViewModel` with `state: StateFlow<TasksUiState>` (`combine(tasks.tasks, tasks.projects, clock.dayFlow(), local UI flow)`, `stateIn(WhileSubscribed(5_000))`), `events: Flow<UiEvent<TasksAction>>`, `fun onAction(a: TasksAction)`.
- `@Composable fun TasksRoute(onOpenTask: (String) -> Unit, onOpenProject: (String) -> Unit, onAddTask: (TaskDraft) -> Unit, onFocus: () -> Unit, onNewProject: () -> Unit = {}, vm: TasksViewModel = koinViewModel())` + stateless `TasksScreen(state, onAction, …same callbacks)`.

**Requirements (mirror `mitra-frontend/src/screens/tasks.tsx`):**
- Tabs = `MitraSegmented` labels `"Inbox 3"`, `"Today 5"`, `"Upcoming 2"`, `"Projects 4"` from `taskCounts`. Default tab Today; tab selection survives recomposition (VM state).
- Today tab: Overdue card (only if any) with rows + soft button "Move all to today" (`MoveOverdueToToday` → each overdue gets `Due(today)`, Snack `"N overdue task(s) moved to today"` with undo `RestoreDues`); "Today" card with header action `"N open · M done"`, rows from `todaySorted` filtered by `showDone`, empty state "No tasks for today" / "Add one, or pull something forward from Upcoming. Mitra will keep this screen calm." + "Add task" (`onAddTask(TaskDraft("", due = today))`), link "Hide/Show M completed"; card "Planned today" / "Done" KPIs (`minsToHm(plannedMin)`, `minsToHm(doneMin)`) + progress; Focus card (eyebrow "Focus", `focusLine`, button "Focus mode" → `onFocus`). Filters row (Project ▾ / Priority ▾ / Due ▾ dropdown chips + "Clear") applies `applyFilters` to Overdue/Today/Upcoming like `applyFilters` in tasks.tsx.
- Upcoming tab: `groupUpcoming` sections with `upcomingLabel`; empty "Nothing scheduled — that is fine." / "Tasks with a future due date show up here grouped by day." + "Schedule a task" (draft due tomorrow).
- Inbox tab: info banner "N unsorted capture(s). Give each a project or a date." when non-empty; each row has triage chips Today / Tomorrow / first 3 projects / Someday (`Triage` → `update`, Snack `"<title> → <label>"`, undo `UndoTriage` = `ProjectId(null)`, `Due(null)`, `Someday(false)`); empty "Inbox zero — nice." / "New captures from Quick add and the widget land here until you file them."; bottom composer "Add to inbox…" → `AddToInbox` → `create(TaskDraft(text))`, Snack `"Added to inbox · <title>"` undo `DeleteCreated`. Blank text ignored.
- Projects tab: one card per project (emoji box, name, `"N open task(s)"`, progress bar, `"x% complete"` from `projectStats`) → `onOpenProject`; dashed "New project" card → `onNewProject`.
- Gestures: checkbox or swipe-right → `Toggle`: open task → `setDone(id, true)`, events `Haptic(Confirm)` + `Snack("Completed · <title>", UndoComplete(id, spawned))`; done task → `setDone(id, false)` (no snack). `UndoComplete` → `setDone(id,false)` and `delete(spawnedId)` if non-null. Swipe-left → `Reschedule` → `Due(tomorrow)`, Snack `"Moved to tomorrow · <title>"` undo `SetDue(id, previousDue)`.
- Pull-to-refresh (`PullToRefreshBox`) → `Refresh`: `refreshing` true while `tasks.refresh()` runs; `Err(Offline)` → Snack "You're offline — showing tasks saved on this phone"; other `Err` → Snack(error.message).
- All lists are `LazyColumn` items keyed by task id; rows meet 48 dp targets.

**Tests (`TasksViewModelTest : ViewModelTest`, FakeTasksRepository, FixedClock 2026-10-13T04:30Z IST, collect state in `backgroundScope`):**
- `buildsTabsFromSelectors` → counts `TaskCounts(1,2,1,1)` for fixtures inbox/overdue/today/upcoming + 1 project; `todayRows` ids in `todaySorted` order; `openCount`/`doneCount`.
- `completeEmitsHapticAndUndoableSnack` → `Toggle("t1")` ⇒ calls `["done:t1:true"]`, events `[Haptic(Confirm), Snack("Completed · Write report", UndoComplete("t1", null))]`; dispatching that undo ⇒ `"done:t1:false"`.
- `undoCompleteDeletesSpawnedOccurrence` → `spawnOnComplete = "next1"` ⇒ undo calls `"done:t1:false"`, `"delete:next1"`.
- `swipeLeftMovesToTomorrowWithUndo` → `"update:t1:Due(value=2026-10-14)"`, Snack `"Moved to tomorrow · Write report"`, undo → `"update:t1:Due(value=2026-10-13)"`.
- `moveOverdueToTodayRestoresOnUndo` → two overdue ⇒ Snack `"2 overdue tasks moved to today"`; undo restores both original dues.
- `addToInboxIgnoresBlankAndOffersUndo` → `"   "` ⇒ no call; `"Buy milk"` ⇒ `"create:Buy milk"`, undo ⇒ `"delete:new1"`.
- `filtersApplyToTodayAndUpcoming` → `TaskFilters(pri = High)` keeps only high rows.
- `refreshOfflineShowsMessage` → `refreshResult = Err(Offline)` ⇒ Snack "You're offline — showing tasks saved on this phone", `refreshing` back to false.
- `TaskRowUiTest.mapsLikeWebTaskVm` → overdue label "Overdue · Mon 12 Oct", project name, est "1h 05m", sub "1/2", parent title, ai flag for `source = "ai"`.

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 8: Task form sheet (create/edit) + task detail screen

**Files:**
- Create: `shared/feature/…/feature/tasks/{TaskEditorViewModel,TaskFormSheet,TaskDetailViewModel,TaskDetailScreen}.kt`, `shared/feature/…/feature/common/DatePickers.kt`
- Modify: `FeatureModule.kt` (`viewModelOf(::TaskEditorViewModel)`, `viewModel { (id: String) -> TaskDetailViewModel(id, get(), get()) }`)
- Test: `…/feature/tasks/TaskEditorViewModelTest.kt`, `…/TaskDetailViewModelTest.kt`, `…/feature/common/DatePickersTest.kt`

**Interfaces:**
- `TaskEditorViewModel(tasks: TasksRepository, clock: AppClock)` — **shell-scoped** (Task 13 hosts it once), so Undo snackbars outlive the sheet and the detail screen. State `TaskEditorUiState(form: TaskFormState, projects: List<Project>, today: String, heading: String)`; `TaskFormState(open=false, editId: String?=null, title="", projectId: String?=null, due: String?=null, time: String?=null, pri: Priority?=null, est="", recurring: String?=null, parentId: String?=null, notes="", error: String?=null)`. Actions `TaskEditorAction`: `Open(defaults: TaskDraft)`, `Edit(id)`, `Dismiss`, `Title(v)`, `Project(id?)`, `Due(d?)`, `Time(t?)`, `Pri(p?)`, `Est(v)` (digits only, like web `replace(/\D/g,'')`), `Recurring(r?)`, `Notes(v)`, `Save`, `DeleteCreated(id)`, `Deleted(removed: List<Task>)`, `Restore(removed)`. `events: Flow<UiEvent<TaskEditorAction>>`.
- `@Composable fun TaskFormSheet(state: TaskEditorUiState, onAction: (TaskEditorAction) -> Unit)` — `ModalBottomSheet(skipPartiallyExpanded = true)`.
- `fun isoFromUtcMillis(ms: Long): String`, `fun utcMillisFromIso(iso: String): Long`; `@Composable fun MitraDatePickerDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit)` (material3 `DatePickerDialog` + `DatePicker`, `@OptIn(ExperimentalMaterial3Api::class)`); `@Composable fun MitraTimePickerDialog(initial: String?, onPick: (String /* "HH:MM" */) -> Unit, onDismiss: () -> Unit)` (`AlertDialog` + `TimePicker(rememberTimePickerState(h, m, is24Hour = false))`, default 09:00).
- `TaskDetailViewModel(id: String, tasks: TasksRepository, clock: AppClock)`: `TaskDetailUiState(notFound, title, done, status: TaskStatus, priChip: Pair<String, ChipTone>?, project: Project?, ai, dueLine, overdue, estimateLine, repeatLine, parent: ParentUi?(id,title,progress "1/3"), subtasks: List<TaskRowUi>, subProgress: SubProgress, notes /* local draft */, createdLine)`; actions `ToggleDone`, `SetStatus(s)`, `Reschedule(due: String?, label)`, `Notes(text)`, `AddSubtask(title)`, `ToggleChild(id)`, `UndoComplete(id, spawned)`, `SetDue(due)`, `Duplicate`, `Delete`; `events: Flow<UiEvent<TaskDetailAction>>`; `deleted: Flow<List<Task>>`.
- `@Composable fun TaskDetailRoute(id: String, onBack: () -> Unit, onEdit: () -> Unit, onAddTask: (TaskDraft) -> Unit, onOpenTask: (String) -> Unit, onFocus: () -> Unit, onDeleted: (List<Task>) -> Unit, vm: TaskDetailViewModel = koinViewModel { parametersOf(id) })`.

**Requirements (mirror `TaskForm` and `TaskDetailScreen` in tasks.tsx):**
- Form: heading "Edit task" / "New subtask" (parentId set) / "New task"; title field autofocused (`FocusRequester` + keyboard), project chips (Inbox + projects, horizontal scroll), due quick chips Today / Tomorrow / Next week / No date + "Pick date" (date dialog), time chip (time dialog, ✕ clears), priority segmented None/High/Medium/Low, estimate (number keyboard, suffix "minutes"), repeat chips (Does not repeat + `RECURRENCES`), notes (multi-line). Save: blank title → `error = "Give the task a title"`, sheet stays open; create → Snack `"Task added · <title>"` undo `DeleteCreated`; edit → only changed fields as `TaskChange`s (none changed → just close), Snack "Task updated"; then form closes. `Deleted(removed)` → Snack "Task deleted" undo `Restore(removed)` → `tasks.restore`.
- Detail: top bar "Task" with back + overflow (Edit, Duplicate, Delete). Header with CheckCircle, title (struck when done), chips: priority (`"High priority"` danger / `"Medium priority"` warning / `"Low priority"` info), project (tap → not required this phase), "Created by AI". Rows: Due (`"Today · 5:00 PM"`, `"No date"`, `" · overdue"` in danger) → opens reschedule chips Today / Tomorrow / Next Monday (`nextMonday`) / Next week / No date / Pick date (Snack `"Rescheduled · <label>"`); Estimate (`"45m · effort: medium"`: >90 high, >30 medium, else low; "Not set"); Repeat (`recurring ?: "Does not repeat"`); Status segmented To do/Doing/Blocked/Done (`TaskChange.Status`). Parent card "Subtask of <b>title</b> · x/y done" → `onOpenTask(parentId)`. Subtasks "Subtasks · d of n" (+ " · all done") with progress, `TaskRow`s, inline "Add a subtask…" field (`create(TaskDraft(title, parentId = id, projectId = task.projectId, due = task.due))`, Snack `"Subtask added · <title>"`), link "Detailed subtask" → `onAddTask(TaskDraft("", parentId=id, projectId, due))`. Notes field: local draft, saved via `update(Notes)` debounced 600 ms (and flushed in `onCleared`). Footer line `"Created Today by you · completed Today"` (`fmtDay(createdAt.take(10))`, "Mitra (approved by you)" when ai). Bottom bar: Focus (`onFocus`), Complete/Reopen (`ToggleDone`: Snack `"Completed · <title>"` / `"Marked as open"` with undo, Haptic Confirm on complete). Delete → confirm dialog `Delete “<title>”?` / `"N subtask(s) will be deleted too. You can undo for a few seconds."` → `delete(id)` → `deleted` emits → route calls `onDeleted(removed)` then `onBack()`. Duplicate → `create` copy titled `"<title> (copy)"`, Snack "Duplicated". Not found → `EmptyState` "Task not found" / "It may have been deleted." + back.

**Tests:**
- `DatePickersTest`: `isoFromUtcMillis(utcMillisFromIso("2026-10-14")) == "2026-10-14"`; `utcMillisFromIso("1970-01-02") == 86_400_000`.
- `TaskEditorViewModelTest`: `blankTitleShowsErrorAndKeepsSheetOpen`; `createsWithAllFields` → Open(TaskDraft("", due=today)) + Title "Write" + Pri(High) + Est("4x5") ⇒ est "45", Save ⇒ `"create:Write"` and created task has pri High, estMin 45, Snack `"Task added · Write"` with `DeleteCreated("new1")`, form closed; `editSendsOnlyChangedFields` → Edit("t1"), Title("New"), Save ⇒ `"update:t1:Title(value=New)"`; `editWithoutChangesJustCloses` ⇒ no update call; `deletedOffersRestore` → `Deleted(listOf(t))` ⇒ Snack("Task deleted", Restore(listOf(t))), undo ⇒ `"restore:t1"`; `headingFollowsMode` ("New task"/"New subtask"/"Edit task").
- `TaskDetailViewModelTest`: `showsLinesLikeWeb` (dueLine "Today · 5:00 PM", estimateLine "45m · effort: medium", repeatLine "Weekly", createdLine "Created Today by you"); `statusDoneCompletes` ⇒ `"update:t1:Status(value=Done)"`; `rescheduleNextMonday` (today 2026-10-13 Tue ⇒ `Due(value=2026-10-19)`, Snack "Rescheduled · Next Monday"); `notesAreDebounced` (three Notes within 600 ms ⇒ one update with the last text, use `StandardTestDispatcher` on Main + `advanceTimeBy(601)`); `addSubtaskInheritsProjectAndDue`; `deleteEmitsRemoved` (`deleted.first()` == task + child); `missingTaskIsNotFound`.

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 9: Projects — create/edit form, project detail, Projects tab wiring

**Files:**
- Create: `shared/feature/…/feature/tasks/{ProjectFormSheet,ProjectDetailViewModel,ProjectDetailScreen}.kt`
- Modify: `TasksViewModel.kt` (+`CreateProject(name, emoji)`), `TasksScreen.kt` (`TasksRoute` hosts the project sheet: `onNewProject` default now opens it), `FeatureModule.kt` (`viewModel { (id: String) -> ProjectDetailViewModel(id, get(), get()) }`)
- Test: `…/feature/tasks/ProjectDetailViewModelTest.kt`, `…/ProjectFormTest.kt`, add to `TasksViewModelTest`

**Interfaces:**
- `val PROJECT_EMOJIS = listOf("🚀","💼","🙂","🏠","📚","👨‍👩‍👧","🎯","💡","🏋️","✈️","🎨","🧾","🌱","🛠️")` (from `ProjectForm` in tasks.tsx); `internal fun validateProjectName(name: String): String?` → `"Name the project"` when blank.
- `@Composable fun ProjectFormSheet(title: String, confirmLabel: String, initialName: String = "", initialEmoji: String = "🚀", onSave: (name: String, emoji: String) -> Unit, onDismiss: () -> Unit)`.
- `ProjectDetailViewModel(id: String, tasks: TasksRepository, clock: AppClock)`: `ProjectDetailUiState(notFound, name, emoji, open, done, pct, rows: List<TaskRowUi> /* not-done first */)`; actions `Toggle(id)`, `UndoComplete(id, spawned)`, `Edit(name, emoji)`, `Delete`; `events: Flow<UiEvent<ProjectDetailAction>>`.
- `@Composable fun ProjectDetailRoute(id: String, onBack: () -> Unit, onOpenTask: (String) -> Unit, onAddTask: (TaskDraft) -> Unit, vm = koinViewModel { parametersOf(id) })`.

**Requirements (mirror `ProjectsScreen` / `ProjectDetailScreen` / `ProjectForm`):** header with emoji + name, eyebrow "Project", chip `"N open · M done"`, progress bar `pct`; "Add task" button → `onAddTask(TaskDraft("", projectId = id))`; list view only (board not ported — flagged deviation); rows with swipe + complete-with-undo exactly like Task 7; overflow Edit project (sheet "Edit project"/"Save") / Delete project → confirm `Delete “<name>”?` / `"N task(s) will be moved to Inbox, not deleted."` → `deleteProject`, Snack "Project deleted", `Close` event → back. Create from Tasks → Projects tab: sheet "New project"/"Create project" → `CreateProject` → Snack `"Project created · <name>"`. Not found → EmptyState "This project doesn't exist" / "It may have been deleted.".

**Tests:** `validateProjectName("  ") == "Name the project"`, `validateProjectName("Work") == null`; `ProjectDetailViewModelTest`: `statsAndOrder` (open 2, done 1, pct 33, done row last), `editRenames` ⇒ `"updateProject:p1:Home:🏠"`, `deleteMovesTasksToInboxAndCloses` ⇒ `"deleteProject:p1"`, events `[Snack("Project deleted"), Close]`, `toggleCompletesWithUndo`; `TasksViewModelTest.createProjectAnnounces` ⇒ `"createProject:Work"`, Snack `"Project created · Work"`.

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 10: Focus timer

**Files:**
- Create: `shared/feature/…/feature/tasks/{FocusViewModel,FocusScreen}.kt`
- Modify: `FeatureModule.kt` (`viewModel { (taskId: String) -> FocusViewModel(taskId, get(), get(), get()) }` — `""` means no pinned task)
- Test: `…/feature/tasks/FocusViewModelTest.kt`

**Interfaces:**
- `FocusViewModel(taskId: String, tasks: TasksRepository, auth: AuthRepository, clock: AppClock)`; `FocusUiState(empty: Boolean, title: String?, position: String /* "1 of 3 · Today · 1 session" */, clock: String /* "25:00" */, running: Boolean, finished: Boolean, segments: Int /* 0..4 */, next: String? /* "Next: Email Ravi · 30m · " */)`; actions `PauseResume`, `Done`, `Skip`, `Reset`, `AddFive`, `OpenTask`; `events: Flow<UiEvent<FocusAction>>`; `openTask: Flow<String>`.
- `@Composable fun FocusRoute(taskId: String, onClose: () -> Unit, onOpenTask: (String) -> Unit, vm = koinViewModel { parametersOf(taskId) })` — full-screen (no shell bars, Task 13).

**Requirements (mirror `FocusScreen` in tasks.tsx):** queue = `focusQueue(tasks, today, taskId.ifBlank { null })`; current = `queue[idx]`; when the current task id changes, `left = (estMin ?: user.settings.focusMinutes) * 60`, running = true. A 1 s ticker (`delay(1000)` in `viewModelScope`) decrements while running; at 0 → running false, sessions + 1, Snack "Session complete — take a short break" + Haptic Confirm, clock shows in success colour. `Done` → minutes spent `round((total-left)/60)`, `setDone(cur, true)`, Snack `"Completed · <title> · N min logged"`, Haptic Confirm; if it was the last task → `Close`. `Skip` → next, or Snack "That was the last task for today" + `Close`. `Reset` → left = total, running. `AddFive` → left += 300. Segments: count of quarters reached (`pct >= 25*(i+1)`). Empty queue → screen "Nothing left for today" / "Add a task or pull one from Upcoming to start a session." + "Back to tasks". Layout: top row ✕ / position chip / overflow (Reset timer, +5 minutes, Open task); eyebrow "Now", title, big mono timer (`Mitra.type.numLg` scaled to 56 sp), 4 segments, buttons Pause/Play (outline), Done (primary), Skip (outline), footer `next` + "Time is logged when you finish."

**Tests (StandardTestDispatcher on Main, `advanceTimeBy`):** `startsWithEstimateOrFocusMinutes` (estMin 45 ⇒ "45:00"; none ⇒ user `focusMinutes` 25 ⇒ "25:00"); `countsDownOnlyWhileRunning` (61 s ⇒ "24:59"→ after `PauseResume` + 10 s still same); `reachingZeroEndsSession` (estMin 1, 60 s ⇒ running false, events Snack("Session complete — take a short break"), Haptic(Confirm), position contains "1 session"); `doneLogsMinutesAndAdvances` (after 120 s on a 45-min task ⇒ `"done:a:true"`, Snack `"Completed · A · 2 min logged"`, title becomes next task); `doneOnLastClosesScreen`; `pinnedTaskFirst`; `skipPastEndCloses`.

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 11: Quick Add sheet (all type chips; Task fully working)

**Files:**
- Create: `shared/feature/…/feature/quickadd/{QuickAddViewModel,QuickAddSheet}.kt`
- Modify: `FeatureModule.kt` (`viewModelOf(::QuickAddViewModel)`)
- Test: `…/feature/quickadd/QuickAddViewModelTest.kt`

**Interfaces:**
- `enum class QuickType(val label: String, val arrivesWith: String?) { Task("Task", null), Expense("Expense", "Money"), Habit("Habit check", "Habits"), Note("Note", "Notes"), Mood("Mood", "Wellness") }` (icons mapped in the sheet: CheckSquare, Wallet, Repeat, Note, Smile).
- `QuickAddUiState(text = "", type = Task, title = "", dueLabel: String? /* fmtDay */, priLabel: String?, projectLabel: String? /* "💼 Work" */, notice: String? /* "Expense arrives with Money — add it on the web for now." */, canSave: Boolean, error: String?)`; actions `Text(v)`, `Type(t)`, `ClearDue`, `ClearPri`, `ClearProject`, `Save`, `Reset`, `DeleteCreated(id)`; `events: Flow<UiEvent<QuickAddAction>>`.
- `QuickAddViewModel(tasks: TasksRepository, clock: AppClock)` — shell-scoped (Task 13).
- `@Composable fun QuickAddSheet(state: QuickAddUiState, onAction: (QuickAddAction) -> Unit, onDismiss: () -> Unit)`.

**Requirements:** spec §9. Parsed on every keystroke with `QuickAddParser.parse(text, today, projects, ignore)`; dismissing a chip adds that `ParsedField` to `ignore` (the word goes back into the title); `ignore` resets on `Reset`/after save. Chips row shows "Due · Tomorrow ✕", "Priority · High ✕", "💼 Work ✕". Field placeholder "Call John tomorrow p1 #work", focused with keyboard open when the sheet appears. Non-Task types: notice text + Save disabled. Save (Task): empty parsed title → error "Give the task a title"; else `create(TaskDraft(title, due, pri, projectId))`, Snack `"Added · <title>"` (+ `" · <dueLabel>"` when due) with undo `DeleteCreated(id)`, Haptic Confirm, `Close`, state reset. Undo works after the sheet closed (VM is shell-scoped).

**Tests:** `parsesAsYouType` ("Call John tomorrow p1 #work" with project Work, today 2026-10-13 ⇒ title "Call John", dueLabel "Tomorrow", priLabel "High", projectLabel "💼 Work"); `clearingAChipPutsWordBack` (ClearDue ⇒ title "Call John tomorrow", dueLabel null); `saveCreatesAndOffersUndo` (⇒ `"create:Call John"`, created task due "2026-10-14" pri High projectId "p1", events Snack("Added · Call John · Tomorrow", DeleteCreated("new1")), Haptic(Confirm), Close; then text reset); `blankTitleIsRejected` ("today p1" ⇒ error, no create); `otherTypesAreScaffolded` (Type(Expense) ⇒ notice "Expense arrives with Money — add it on the web for now.", canSave false, Save ⇒ no calls).

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 12: Today — real Tasks stat and "Now · priorities"

**Files:**
- Modify: `shared/feature/…/feature/today/{TodayViewModel,TodayScreen}.kt`, `FeatureModule.kt` (constructor change only)
- Test: `shared/feature/src/commonTest/…/feature/today/TodayViewModelTest.kt` (update existing constructor calls + add tests)

**Interfaces:**
- `TodayViewModel(auth: AuthRepository, tasks: TasksRepository, clock: AppClock)`; `TodayUiState` gains `priorities: List<TaskRowUi>`, `showPriorities: Boolean` (`"priorities" in user.settings.todaySections`), `prioritiesFooter: String` (`"All N tasks for today"`), `prioritiesMeta: String` (`"M done · K overdue"`, overdue part omitted when 0); `StatUi` gains `target: StatTarget? = null` with `enum class StatTarget { Tasks }`. Actions `TodayAction.Toggle(id)`, `UndoComplete(id, spawned)`; `events`.
- `@Composable fun TodayRoute(onOpenTasks: () -> Unit = {}, onOpenTask: (String) -> Unit = {}, vm = koinViewModel())`.

**Requirements (mirror `TodayScreen` / `TodayPriority` in today.tsx):** Tasks stat value `"done/(open+done)"` with `open = openToday`, `done = doneToday`; sub `"<K overdue · >Xm planned · Ym done"` via `minsToHm(plannedMin)` / `minsToHm(doneMin)`; tapping it → `onOpenTasks`. Header still ticks per minute; data also follows `clock.dayFlow()`. Section "Now · priorities" (action "Plan" → `onOpenTasks`): up to 3 `priorityTasks` as `TaskRow`s (toggle with Haptic + Undo snack exactly like Task 7), footer link `prioritiesFooter` + muted `prioritiesMeta`; empty → EmptyState "Nothing urgent" / "No overdue or due-today tasks. Pull one from Upcoming or enjoy the calm." + "Plan the day". Keep the Phase 1 placeholder card below for the remaining sections, reworded "Habits, money and your check-in will appear here as each module arrives in the app."; other stats keep "—".

**Tests:** existing two tests pass with `FakeTasksRepository()`; `tasksStatMatchesWeb` (1 done today, 2 open today est 30+45, 1 overdue ⇒ value "1/3", sub "1 overdue · 1h 15m planned · 0m done" — done task has no estimate); `prioritiesAreTopThree` (ids in `priorityTasks` order, footer "All 4 tasks for today" = open today + overdue + done, meta "1 done · 1 overdue"); `prioritiesHiddenWhenSectionRemoved` (todaySections without "priorities" ⇒ `showPriorities` false); `toggleFromTodayOffersUndo`.

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 13: App shell — destinations, Quick Add + task form sheets, shared snackbar, detail chrome, launch targets

**Files:**
- Modify: `shared/app/…/app/{Destinations,MainShell,AppModules,MitraApp}.kt`
- Create: `shared/app/…/app/LaunchTarget.kt`
- Test: `shared/app/src/commonTest/…/app/{LaunchTargetTest,ChromeTest}.kt`, `AppModulesTest.kt` (extend)

**Interfaces:**
- Destinations: `@Serializable data class TaskDetailDest(val id: String)`, `@Serializable data class ProjectDetailDest(val id: String)`, `@Serializable data class FocusDest(val taskId: String = "")` (TasksDest stays a data object).
- `internal enum class Chrome { Tab, Detail, Fullscreen }`; `internal fun chromeFor(route: KClass<*>?): Chrome` (TaskDetailDest/ProjectDetailDest → Detail, FocusDest → Fullscreen, else Tab).
- `sealed interface LaunchTarget { data class OpenTask(val id: String); data object OpenTasks; data object QuickAddTask }`; `fun parseLaunchUri(uri: String?): LaunchTarget?` for `mitra://task/<id>`, `mitra://tasks`, `mitra://quick-add/task`; `class LaunchRequests { val pending: StateFlow<LaunchTarget?>; fun submit(t: LaunchTarget?); fun consume() }` registered `single { LaunchRequests() }` in `appModules`.

**Requirements:**
- `MainShell` creates `SnackbarController(snackbar, rememberCoroutineScope())` and provides `LocalSnackbar` around the Scaffold **and** the sheets; sync `messages` are shown through it (replay comes from Task 3's channel).
- ＋ opens the Quick Add sheet (replace the Phase 1 "Quick add arrives with Tasks" snackbar). `QuickAddViewModel` and `TaskEditorViewModel` are obtained with `koinViewModel()` in `MainShell` (outside any nav entry) and their `EventEffect`s live in `MainShell`; `Close` from Quick Add hides the sheet.
- NavHost: `TasksDest → TasksRoute(onOpenTask = navigate TaskDetailDest, onOpenProject = navigate ProjectDetailDest, onAddTask = editor.Open, onFocus = navigate FocusDest())`; `TaskDetailDest` (`toRoute<TaskDetailDest>().id`) → `TaskDetailRoute(onBack = popBackStack, onEdit = editor.Edit(id), onAddTask = editor.Open, onOpenTask, onFocus = navigate FocusDest(id), onDeleted = { editor.Deleted(it) })`; `ProjectDetailDest` → `ProjectDetailRoute`; `FocusDest` → `FocusRoute(onClose = popBackStack, onOpenTask)`; `TodayDest → TodayRoute(onOpenTasks = go(TasksDest), onOpenTask = navigate TaskDetailDest)`.
- Chrome: shell top bar only on `Tab`; detail screens draw their own `MitraTopBar(onBack)`; bottom bar hidden on `Fullscreen`. The highlighted tab for Detail/Fullscreen routes is Tasks, and the Phase 1 "module switched off → go Today" effect only fires when chrome is `Tab`.
- Launch targets: `MainShell` collects `LaunchRequests.pending` (`koinInject`) — `OpenTask(id)` → `go(TasksDest)` then navigate `TaskDetailDest(id)`; `OpenTasks` → `go(TasksDest)`; `QuickAddTask` → open the Quick Add sheet; then `consume()`. Targets arriving while signed out stay pending until the shell composes.
- `FeatureModule` already has all VMs; `appModules` adds `LaunchRequests`.

**Tests:** `LaunchTargetTest` (`"mitra://task/abc"` ⇒ OpenTask("abc"); `"mitra://tasks"` ⇒ OpenTasks; `"mitra://quick-add/task"` ⇒ QuickAddTask; `"mitra://task/"`, `"https://x/task/1"`, `null` ⇒ null; `LaunchRequests.submit(null)` keeps the previous target, `consume()` clears); `ChromeTest` (three mappings + `TodayDest::class` ⇒ Tab); `AppModulesTest` also resolves `TasksRepository`, `ModuleRefresher`, `LaunchRequests`, `TasksViewModel`, `TaskEditorViewModel`, `QuickAddViewModel`, `TaskDetailViewModel { parametersOf("t1") }`, `ProjectDetailViewModel { parametersOf("p1") }`, `FocusViewModel { parametersOf("") }`, `TodayViewModel`; existing `DestinationsForTest` unchanged.

**Verify:** `./gradlew testAndroidHostTest :androidApp:assembleDebug`

---

### Task 14: Long-press multi-select with bulk complete / move / delete

**Files:**
- Modify: `shared/feature/…/feature/tasks/{TasksViewModel,TasksScreen,TaskRow}.kt`
- Test: add to `TasksViewModelTest`

**Interfaces:**
- `TasksUiState.selection: Set<String>`; actions `LongPress(id)`, `ToggleSelect(id)`, `ClearSelection`, `BulkComplete`, `BulkMove(projectId: String?)`, `BulkDelete`, `UndoBulkComplete(ids: List<String>, spawned: List<String>)`, `RestoreProjects(previous: Map<String, String?>)`, `RestoreDeleted(removed: List<Task>)`.

**Requirements (spec §9 gestures):** long-press a row → selection mode with Haptic(LongPress); while selecting, tap toggles membership, swipe is disabled, the tab row is replaced by a contextual bar (✕, `"N selected"`, Complete, Move ▾ (Inbox + projects), Delete). `BulkComplete` → `setDone(id,true)` for open selected tasks, Snack `"N tasks completed"` undo reopens them and deletes spawned ids; `BulkMove` → `ProjectId(p)` per task, Snack `"Moved N tasks to <name|Inbox>"` undo `RestoreProjects`; `BulkDelete` → confirm dialog `"Delete N tasks?"` (Haptic Reject on confirm), Snack `"N tasks deleted"` undo `RestoreDeleted` → `restore(all removed)`. Each bulk action clears the selection. System back (`androidx.compose.ui.backhandler.BackHandler`, `@Suppress("DEPRECATION")`, `@OptIn(ExperimentalComposeUiApi::class)`) clears the selection; switching tabs clears it.

**Tests:** `longPressStartsSelectionWithHaptic`; `tapTogglesWhileSelecting` (LongPress a, ToggleSelect b, ToggleSelect a ⇒ {b}); `bulkCompleteWithUndo` (⇒ `done:a:true`, `done:b:true`, Snack "2 tasks completed", undo ⇒ `done:a:false`, `done:b:false`, selection empty); `bulkMoveRestoresOriginalProjects`; `bulkDeleteRestoresAll` (⇒ `delete:a`, `delete:b`, undo ⇒ `restore:a,b` order preserved); `switchingTabClearsSelection`.

**Verify:** `./gradlew :shared:feature:testAndroidHostTest`

---

### Task 15: Tasks home-screen widget (Jetpack Glance) + deep links + midnight refresh

**Files:**
- Modify: `gradle/libs.versions.toml` (`glance = "1.2.0"`, `glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }`), `androidApp/build.gradle.kts` (`implementation(libs.glance.appwidget)`)
- Modify: `shared/domain/…/domain/repository/AuthRepository.kt` (+ `val hasLocalAccount: Flow<Boolean> get() = session.map { it is SessionState.SignedIn }`), `shared/data/…/repository/AuthRepositoryImpl.kt` (override: `cache.observe().map { it != null }.distinctUntilChanged()`)
- Create: `androidApp/src/main/kotlin/…/android/widget/{TasksWidget,TasksWidgetReceiver,ToggleTaskAction,WidgetUpdater,MidnightReceiver}.kt`, `androidApp/src/main/res/xml/tasks_widget_info.xml`, `androidApp/src/main/res/values/strings.xml`
- Modify: `androidApp/src/main/AndroidManifest.xml`, `MainActivity.kt`, `MitraApplication.kt`
- Test: `shared/data/src/commonTest/…/repository/AuthRepositoryImplTest.kt` (add `hasLocalAccountFollowsCachedUser`)

**Interfaces:**
- Consumes: `tasksWidgetModel` / `TasksWidgetModel` / `WidgetTaskRow` (Task 1), `TasksRepository.setDone`, `AuthRepository.hasLocalAccount`, `PreferencesRepository.theme`, `AppClock.today/dayFlow/nextMidnightEpochMs`, `NoirDark/NoirLight`, `LaunchRequests` + `parseLaunchUri` (Task 13).
- Produces: `class TasksWidget : GlanceAppWidget(), KoinComponent` (`sizeMode = SizeMode.Responsive(setOf(SMALL 110×110 dp, MEDIUM 250×110 dp, LARGE 250×250 dp))`); `class TasksWidgetReceiver : GlanceAppWidgetReceiver` (`onEnabled` schedules midnight); `class ToggleTaskAction : ActionCallback, KoinComponent` with `val TaskId = ActionParameters.Key<String>("taskId")`; `class WidgetUpdater(context, tasks, auth, prefs, clock, scope) { fun start() }`; `class MidnightReceiver : BroadcastReceiver` with `companion fun schedule(context)` and action `"tech.beyondscale.mitra.WIDGET_MIDNIGHT"`.

**Requirements (spec §10 Tasks widget):**
- `provideGlance`: snapshot `tasksWidgetModel(tasks.tasks.first(), clock.today(), auth.hasLocalAccount.first())`, palette from `prefs.theme.first()` (Auto → system `UI_MODE_NIGHT_YES`), then `provideContent`. Colours via `androidx.glance.unit.ColorProvider(color)`; corner radius 16 dp; padding 12 dp.
- Medium/Large: header `"Today · {done}/{total} done"` + thin `LinearProgressIndicator` + ＋ (opens `mitra://quick-add/task`); rows: `CheckBox(checked = false, onCheckedChange = actionRunCallback<ToggleTaskAction>(actionParametersOf(TaskId to id)))`, danger dot when overdue, title (1 line) + meta; tapping the title opens `mitra://task/<id>`; rows that fit = `((height − 64 dp) / 36 dp)`, at least 1; footer `"+N more"` → `mitra://tasks`. Small: `"{left} left"`, next task title, ＋. Empty: "All clear for today ✓". Signed out (no local account): "Sign in to Mitra" → opens the app.
- Intents: `Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)` with `FLAG_ACTIVITY_NEW_TASK` (distinct data URIs keep PendingIntents distinct). `MainActivity`: `android:launchMode="singleTask"`; `onCreate` (only when `savedInstanceState == null`) and `onNewIntent` call `launches.submit(parseLaunchUri(intent.dataString))`.
- `ToggleTaskAction`: `get<TasksRepository>().setDone(id, true)` (local DB + outbox; the periodic job from Task 5 sends it if the process dies) then `TasksWidget().update(context, glanceId)`.
- `WidgetUpdater.start()` (called from `MitraApplication.onCreate` after Koin): schedules midnight and collects `combine(tasks.tasks, auth.hasLocalAccount, prefs.theme, clock.dayFlow())` debounced 500 ms → `TasksWidget().updateAll(context)` (covers data change, sync echoes, logout and midnight while alive).
- `MidnightReceiver`: `goAsync()` → `updateAll` → reschedule via `AlarmManager.set(RTC, clock.nextMidnightEpochMs() + 60_000, PendingIntent.getBroadcast(…, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT))` (inexact; no exact-alarm permission). Manifest: receiver `exported=false` with actions `tech.beyondscale.mitra.WIDGET_MIDNIGHT`, `android.intent.action.TIME_SET`, `android.intent.action.TIMEZONE_CHANGED`.
- `tasks_widget_info.xml`: minWidth/minHeight 110 dp, `targetCellWidth="4"`, `targetCellHeight="2"`, `resizeMode="horizontal|vertical"`, `updatePeriodMillis="1800000"`, `initialLayout="@layout/glance_default_loading_layout"`, `description="@string/tasks_widget_description"` ("Today's tasks — tick them off from the home screen"), label `@string/tasks_widget_label` ("Mitra · Today"). Receiver `exported=true` with `APPWIDGET_UPDATE` + provider meta-data.

**Tests:** `AuthRepositoryImplTest.hasLocalAccountFollowsCachedUser` (false on empty DB; true after login; false after logout). Widget composition itself is verified by build + the deferred device checklist.

**Verify:** `./gradlew testAndroidHostTest :androidApp:assembleDebug` and `~/Library/Android/sdk/build-tools/*/aapt2 dump xmltree --file AndroidManifest.xml androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep -E "TasksWidgetReceiver|MidnightReceiver|singleTask"`.

---

### Task 16: Full build verification + README

**Files:**
- Modify: `mitra-mobile/README.md`

**Requirements:**
- Run `cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile" && ./gradlew testAndroidHostTest :androidApp:assembleDebug` — both must pass with zero test failures; also run `./gradlew :androidApp:assembleRelease -Pmitra.releaseApiUrl=https://example.com/api/` to prove the release variant still builds without the dev network config. If anything fails, use superpowers:systematic-debugging before changing code.
- README: under Layout mention `shared/feature/tasks`, `quickadd`, and `androidApp/widget`; add a "Widgets" section (add "Mitra · Today" from the launcher's widget picker; completes tasks offline; refreshes at midnight); add a "Debug networking" note (cleartext only to 10.0.2.2, localhost, 127.0.0.1 and the host in `mitra.apiUrl`; rebuild after changing it); update the test count line with the real number printed by the run.
- Commit: `git add mitra-mobile/README.md && git commit -m "docs(mobile): Phase 2 tasks, widget and debug networking notes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

---

## Deferred device checklist (run with all modules at the end)

No phone is connected for this phase; run these on a device against the local backend (`curl -s http://localhost:4000/api/health`, `./gradlew :androidApp:installDebug`) together with later phases' checks:

1. Install over the Phase 1 build: app opens straight into Today (migration kept the session).
2. Tasks tab: Inbox / Today / Upcoming / Projects show the same data as the web account; pull-to-refresh works.
3. Swipe right completes with a haptic and "Completed · …" Undo; swipe left moves to tomorrow with Undo; the web reflects both after sync.
4. Long-press two tasks → Move to a project → both move; Undo restores.
5. ＋ → Quick Add "Call John tomorrow p1 #work" → task appears in Upcoming with High priority in Work.
6. Airplane mode: create a project, move a task into it, complete a recurring task → pill shows pending; airplane off → everything lands on the web in order, next occurrence exists once.
7. Task detail: edit, status, reschedule chips, subtasks, notes autosave, delete + Undo; Focus timer counts down and logs minutes.
8. Today: Tasks stat "done/total" and "Now · priorities" match the web.
9. Add the "Mitra · Today" widget: tick a task from the widget (app closed) → it disappears and syncs; ＋ opens Quick Add; tapping a title opens task detail; after sign-out the widget says "Sign in to Mitra"; next morning it shows the new day without opening the app.
10. Edit a task on the web, background/foreground the app after > 60 s → change appears without restart.
11. Debug build cannot reach a non-dev cleartext host (e.g. `http://example.com`), but reaches the LAN backend.
