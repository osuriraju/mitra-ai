# Mitra Android — Phase 4 (Habits + Goals) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **LIGHT PLAN (user decision for speed):** tasks are briefs, not code. Each brief fixes the files, the exact interfaces neighbouring tasks rely on, the behaviour to port (web file + function + copy), named tests with concrete expected values, and the verify command. Implement each task TDD-style (failing test → code → green → commit) following the Phase 2/3 code that already exists — when this plan says "as `MoneyRepositoryImpl`", open that file and match it.

**Goal:** Ship Habits and Goals on Android — habit list (Today / Week, grouped by time of day, one-tap check-off, swipe right = complete), habit detail (30-day ring, streaks, 12-week heatmap, trend, recent days), editable history calendar, habit create/edit; goals list (Active / Review due / Completed), goal detail (progress, milestones, progress chart, linked tasks/habits/projects, blockers), log progress, milestone add/edit/tick, goal create/edit, and the goal review as a swipeable step pager; the Today **Habits** stat + Habits and Goals cards; Quick Add **Habit check**; and goal links on tasks and projects — all offline-first through the Phase 1–3 outbox.

**Architecture:** Same layering as Phases 1–3 (feature → domain ← data, MVVM, Route + stateless Screen). Five new SQLDelight tables (`habits`, `habit_logs`, `goals`, `milestones`, `goal_progress`; schema **v4** via `migrations/3.sqm`) are the source of truth. `HabitsRepositoryImpl` and `GoalsRepositoryImpl` copy the `MoneyRepositoryImpl` skeleton (write counter, `applyEcho`, refresh-race guard, pending-id protection, owner check, `shareForRepository` hot flows, sync handlers registered at construction). Web selectors (`mitra-frontend/src/store/selectors.ts` habits + goals blocks) are ported 1:1 into `domain/habits` and `domain/goals`. **Server-side derived goal data is computed locally by one shared domain function set (`GoalMath`)** so the UI shows the same numbers before the echo lands. Before any Habits/Goals screen exists, Task 1 extracts the duplicated task complete/undo logic into one helper that the goal screens reuse. No widget changes (the user asked for widgets only for Tasks and Money; spec §10 has no habits on the Tasks widget).

**Tech Stack:** Unchanged from Phase 3 (Kotlin 2.4.21, AGP 9.4.1, CMP 1.12.1, Koin 4.2.2, Ktor 3.6.0, SQLDelight 2.4.1, coroutines 1.11.0, datetime 0.8.0, Glance 1.2.0, Turbine 1.2.1). **No new dependencies** (the review pager uses `androidx.compose.foundation.pager.HorizontalPager`, already in Compose foundation).

**Spec:** `docs/superpowers/specs/2026-10-13-android-app-design.md` — §13 item 4 (Habits + Goals) with §6 (sync), §7 (ported selectors: `habitsDueToday`, `habitsDoneToday`, `habitsWeekRate`, streaks, `goalProgress`, `goalStatus`), §9 (Quick Add "Habit check" chip, swipe right = complete habit, haptics on complete and streak milestone, goal review as swipeable step pager, bottom-sheet forms), §14 ("Server-side derived data … the client computes the same values locally"). Habit nudges (local notifications) and global search are **Phase 6** (spec §9/§13) — not in this plan. Conventions: Phase 3 plan `docs/superpowers/plans/2026-10-14-android-phase3-money.md`; rulings: `.superpowers/sdd/2026-10-14-android-phase3-money/progress.md`.

## Global Constraints

- All code lives under `mitra-mobile/`; repo root is `/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai` — **the path contains a space; always quote it**. Never stage the repo-root `README.md` or `mitra-mobile/local.properties`; `git add` only the files a task names (retry if `.git/index.lock` exists — parallel implementers commit concurrently).
- Branch: keep working on `feat/android-phase1`.
- Kotlin package root `tech.beyondscale.mitra`. compileSdk 37, targetSdk 36, minSdk 26, JDK 17, Gradle wrapper only. Versions exactly as in `gradle/libs.versions.toml`; add nothing.
- `shared/*/commonMain` must not import Android APIs. `domain` depends only on `core`. `feature` never depends on `data`. Only `app` and `androidApp` wire `data` in. ViewModels depend on domain interfaces (`HabitsRepository`, `GoalsRepository`, `TasksRepository`, `AppClock`), never on `data` classes.
- Screens are `XxxRoute` (collects state, owns `koinViewModel`) + stateless `XxxScreen(state, onAction, …)`; one-off effects through `EventQueue` / `EventEffect` (`feature/common/UiEvent.kt`); snackbars with Undo via the shell's `SnackbarController`. Forms with more than one entry point are **shell-hosted sheets** like `TaskFormSheet` / `TxFormSheet` (one VM keyed `shellVmKey(name, user.id)`).
- **Server contract** (from `mitra-backend/src/habits/*`, `mitra-backend/src/goals/*`; paths relative, no leading slash; POST/PATCH without payload send `{}`; DELETE sends no body; Zod strips unknown keys):
  - `GET habits → Habit[]` (all habits **including archived**, `createdAt` asc; each with `logs: { "YYYY-MM-DD": value }` for the **last 400 days only**; `reminder`, `goalId` absent when null, `archived` absent when false). `POST habits { id, name, type, target, unit, icon, timeOfDay, days[7] (Mon..Sun), reminder|null, goalId|null, gentle }`, `PATCH habits/:id` (any subset + `archived`), `DELETE habits/:id → {ok:true}`, `PUT habits/:id/logs { date, value|null } → Habit` (idempotent per day; `null` or `0` deletes the log). Wire enums: `type` = `binary|count|duration|quantity`, `timeOfDay` = `morning|anytime|evening`. `target` int 1..100000.
  - `GET goals → Goal[]` (`createdAt` asc; `milestones` ordered; `progressLog: [{date, value, note?}]` ascending). `POST goals { id, name, type, term, due, startedOn, current, target, unit, formula|null, nextAction, reviewEvery, reviewOn, status, notes, milestones: [{ id, title, target|null, targetDate|null, reachedOn|null, notes|null }] }`, `PATCH goals/:id` (subset; `formula: null` clears), `DELETE goals/:id → {ok:true}`, `POST goals/:id/progress { value, note|null, date } → Goal` (**action; not idempotent**), `POST goals/:id/milestones {…milestone} → Goal`, `PATCH goals/:id/milestones/:mid → Goal`, `DELETE goals/:id/milestones/:mid → Goal`. Wire enums: `type` = `numeric|milestone|habit|date`, `term` = `Short-term|Medium-term|Long-term`, `reviewEvery` = `Weekly|Monthly|Quarterly`, `status` = `active|paused|completed`, `unit` = `₹|""`. `current`/`target`/milestone `target` are JSON numbers (doubles, **not paise** — a ₹ goal's numbers are whole rupees as the user typed them).
- **Server-side derived goal data — computed locally (spec §14).** The server owns these numbers and returns the whole goal after every write; the device must compute exactly the same thing at write time (domain `GoalMath`, Task 2) so screens never jump when the echo lands: (1) milestone-type goals: `current = reached milestones`, `target = milestone count` (`goalOut`); (2) `logProgress`: numeric → `current += value`, every other type → `current = value`; every milestone with `target != null && reachedOn == null && current >= target` gets `reachedOn = date`; a progress entry `{date, value: current, note}` is appended; status becomes `completed` only if type ∉ {milestone, habit} **and** `current >= target` **and** status was `active` (server rule — the web's optimistic copy ignores `active`; follow the server); (3) marking a milestone reached (null → date) on a **milestone-type** goal appends a progress entry `{reachedOn, reachedCount, note = title}`; un-reaching removes nothing; (4) habit-type goal progress is never stored — it is the average 30-day rate of habits whose `goalId` is the goal (`goalProgress`, client-only on web and device). **Goal progress is not derived from linked tasks** anywhere (web or server); tasks/projects linked to a goal are evidence only.
- Every outbox write carries `entityId`. Outbox entity names (added to `OutboxEntities` in Task 4): `HABIT = "habit"`, `HABIT_LOG = "habit-log"`, `GOAL = "goal"`, `GOAL_PROGRESS = "goal-progress"`, `MILESTONE = "milestone"`. **Habit rows (`HABIT`, `HABIT_LOG`) use `entityId = habitId`; every goal-scoped row (`GOAL`, `GOAL_PROGRESS`, `MILESTONE`) uses `entityId = goalId`** — so "pending" is decided per habit / per goal. Coalesce keys: `"habit:<id>"` (PATCH), `"habit-log:<habitId>:<date>"` (PUT), `"goal:<id>"` (PATCH), `"milestone:<mid>"` (PATCH). Creates, deletes and progress POSTs never coalesce (newest-row-only rule from Phase 2).
- **Echo / refresh rule (Phase 2 deferred item, Money `deletedRefs` generalised):** a server echo or refresh never overwrites a habit or goal that still has **any** queued row (across the entities above), never resurrects a locally deleted habit/goal, and therefore never re-adds a milestone whose DELETE is queued. `goalId` references on tasks, projects and habits are **never scrubbed** (the server keeps them after a goal is deleted — no FK); screens resolve an unknown `goalId` as "no goal".
- "Today" always comes from `clock.today()` (device local date) and rolls over with `clock.dayFlow()`; never UTC. Habit logs are always written with an explicit local `date`; goal progress always sends `date = clock.today()` (the server's default `todayISO()` is UTC).
- Number formatting: rounding through core `jsRound` (`Math.round`), percentages as the web computes them; `₹` goal values through the new core `localeIN(Double)` (port of `toLocaleString('en-IN')`, max 3 fraction digits) and plain values through `jsNum(Double)` (JS `String(n)`: `58000.0 → "58000"`, `12.5 → "12.5"`).
- UI copy, ordering and thresholds follow `mitra-frontend/src/screens/habits.tsx`, `screens/goals.tsx` and `screens/today.tsx` (`TodayHabits`, `TodayGoals`, stat strip) unless this plan says otherwise. Icons from `MitraIcons` (habit icons via `MitraIcons.byName(web id)`, fallback `Repeat`); colours from `Mitra.colors`.
- Tests: `./gradlew testAndroidHostTest` from `mitra-mobile/` (all shared `commonTest` + `androidHostTest` on JVM); phase gate `./gradlew testAndroidHostTest :androidApp:assembleDebug`. Harnesses: `MockServer` + `testDriver()` + `FixedClock` + `ViewModelTest` + Turbine. Fakes live in `shared/testing`. **No on-device testing per phase** (user decision) — device checks go to the Deferred device checklist at the end.
- **No backend changes** without asking the user — gaps are listed under "Backend gaps" and handled client-side as described.
- Commit after every task; message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- *(Deviations flagged for the user)*: (a) swipe left on a habit **archives** it with Undo (history kept) instead of deleting — a deleted habit's logs can't be restored; Delete stays in the detail menu behind a confirm (web parity); (b) habit history "Export CSV" is not ported (data export is Phase 6); (c) goal detail "Linked" omits notes (Notes arrive in Phase 5) and the client-only activity rows ("Goal review completed", "Milestone reached") are not written (Activity arrives in Phase 5; the server still writes its own activity rows); (d) the habit Reminder time is stored and synced but no notification fires until Phase 6 (habit nudges); (e) milestone detail is a bottom sheet over goal detail (web: side panel); (f) goal review is a swipeable pager (spec §9) — same decisions, copy and effects as the web's single page; (g) Quick Add "Habit check" lists today's due habits to check off (web's Quick Add only links to New habit), with a "New habit" fallback; (h) a stronger haptic fires when a check-off reaches a streak of 7 / 30 / 100 / 365 days (Android-only feedback).

## Review Focus

1. **Day boundaries and IST midnight for habits** — at 00:30 IST the UTC date is still yesterday: a check-off must log the local date, "done today", streaks (start from yesterday when today is still open; rest days skipped), Monday-based week dots, 30-day rate and the Today stat must all use the local day and recompute at local midnight without reopening the screen. Pinned in Task 2 (`HabitSelectorsTest.streakCountsFromYesterdayWhenTodayOpen`, `HabitSelectorsTest.streakSkipsRestDays`, `HabitSelectorsTest.weekDotsAreMondayBased`) and Task 13 (`TodayViewModelTest.habitCheckUsesLocalDateAfterMidnight`, `TodayViewModelTest.habitsStatRollsOverAtMidnight`).
2. **Goal numbers drifting from the web/server** — `goalProgress` per type (milestone ratio, date elapsed, habit 30-day average, numeric capped at 100 with `max(1, target)`), `goalStatus` thresholds (`expected − 5 / − 15`, slipping = stale > 21 days and `< expected − 10`) and the locally applied server maths (numeric adds / others set, auto-tick `>=`, completion only while active, milestone-type derived current/target, reach appends a point) must equal what the server returns. Pinned in Task 2 (`GoalSelectorsTest.goalStatusThresholds`, `GoalSelectorsTest.habitGoalAveragesLinked30DayRates`, `GoalMathTest.numericProgressAddsAndAutoTicks`, `GoalMathTest.reachingTargetCompletesOnlyActive`, `GoalMathTest.milestoneTypeDerivesCurrentAndTarget`) and Task 6 (`GoalsRepositoryImplTest.echoEqualsLocalApplication`).
3. **Offline habit check / uncheck / edit / delete** — PUTs for the same habit+day coalesce only into the newest queued row, different days queue separately, an echo of an earlier PUT never wipes a later local log, a refresh keeps pending habits and never deletes logs older than the server's 400-day window, and a delete never runs before the create. Pinned in Task 5 (`HabitsRepositoryImplTest.checkThenUncheckCoalescesIntoOnePut`, `HabitsRepositoryImplTest.echoDoesNotOverwritePendingLogs`, `HabitsRepositoryImplTest.refreshKeepsLogsOutsideWindow`, `HabitsRepositoryImplTest.offlineCreateCheckDeleteReachesServerInOrder`).
4. **Goal echo vs queued milestone/progress writes** — create goal → add milestone → tick it → log progress → delete another milestone, all offline: rows replay in order, no echo or refresh in between reverts a later local change, a deleted milestone is never re-added, a deleted goal is never resurrected. Pinned in Task 6 (`GoalsRepositoryImplTest.goalEchoSkippedWhileGoalHasQueuedRows`, `GoalsRepositoryImplTest.deletedMilestoneNotReintroduced`, `GoalsRepositoryImplTest.offlineGoalSequenceReachesServerInOrder`).
5. **Complete/undo regression in Tasks** — after the Task 1 extraction every existing caller (Today, Tasks, Task detail, Project detail, Focus, widget action) still completes, reopens, offers `Completed · <title>` with Undo that also deletes the spawned recurring occurrence. Pinned in Task 1 (`TaskCompletionTest.*`, `CompletionEventsTest.completedEmitsHapticThenSnackWithUndo`) and all existing Phase 2 VM tests staying green.

---

## File Map (all paths relative to `mitra-mobile/`)

`…` = `src/commonMain/kotlin/tech/beyondscale/mitra` (tests: `src/commonTest/kotlin/tech/beyondscale/mitra`).

```
shared/core/          …/core/Money.kt                           + localeIN, jsNum                                                                   (T2)
shared/domain/        …/domain/tasks/TaskCompletion.kt           toggle/complete/undo helper                                                        (T1)
                      …/domain/model/{Habits,Goals}.kt           models, enums, drafts, changes                                                     (T2)
                      …/domain/repository/{HabitsRepository,GoalsRepository}.kt                                                                     (T2)
                      …/domain/habits/{HabitSelectors,HabitCheck}.kt · …/domain/goals/{GoalSelectors,GoalMath}.kt                                   (T2)
                      …/domain/model/Tasks.kt (+ TaskChange.GoalId) · …/domain/repository/TasksRepository.kt (project goalId)                       (T7)
shared/testing/       …/testing/{FakeHabitsRepository,FakeGoalsRepository}.kt                                                                       (T2)
                      …/testing/FakeTasksRepository.kt (GoalId, project goalId)                                                                     (T7)
shared/designsystem/  …/designsystem/components/{Ring,Heatmap,LineChart,WeekDots,CalendarMonth,IconBox,ConfirmDialog}.kt · Chips.kt (+Success)  (T3)
shared/data/          sqldelight/…/data/db/{Habits,HabitLogs,Goals,Milestones,GoalProgress}.sq · sqldelight/migrations/3.sqm                     (T4)
                      …/data/local/{LocalData.kt (+tables), HabitRows.kt, GoalRows.kt} · …/data/sync/PendingSettings.kt (OutboxEntities +5)        (T4)
                      androidHostTest …/data/db/MigrationTest.kt (+v3→v4)                                                                          (T4)
                      …/data/remote/HabitDtos.kt · …/data/repository/{HabitsRepositoryImpl,HabitPatches}.kt                                        (T5)
                      …/data/remote/GoalDtos.kt · …/data/repository/{GoalsRepositoryImpl,GoalPatches}.kt                                           (T6)
                      …/data/repository/{TasksRepositoryImpl,TaskPatches}.kt · …/data/remote/TaskDtos.kt                                           (T7)
                      …/data/di/DataModule.kt (+2 repos, refresher entries)                                                                       (T14)
shared/feature/       …/feature/common/CompletionEvents.kt · UiEvent.kt (HapticKind.Milestone)                                                     (T1)
                      …/feature/{today/TodayViewModel, tasks/TasksViewModel, tasks/TaskDetailViewModel, tasks/ProjectDetailViewModel, tasks/FocusViewModel}.kt (T1)
                      …/feature/tasks/{TaskEditorViewModel,TaskFormSheet,TaskDetailViewModel,TaskDetailScreen,ProjectFormSheet,ProjectDetailViewModel,ProjectDetailScreen,TasksViewModel}.kt (T7)
                      …/feature/habits/{HabitsViewModel,HabitsScreen,HabitRow,HabitEditorViewModel,HabitFormSheet}.kt                             (T8)
                      …/feature/habits/{HabitDetailViewModel,HabitDetailScreen,HabitHistoryViewModel,HabitHistoryScreen}.kt                       (T9)
                      …/feature/goals/{GoalsViewModel,GoalsScreen,GoalCard,GoalEditorViewModel,GoalFormSheet}.kt                                  (T10)
                      …/feature/goals/{GoalDetailViewModel,GoalDetailScreen,LogProgressSheet,MilestoneSheets}.kt                                  (T11)
                      …/feature/goals/{GoalReviewViewModel,GoalReviewScreen}.kt                                                                   (T12)
                      …/feature/today/{TodayViewModel,TodayScreen}.kt · …/feature/quickadd/{QuickAddViewModel,QuickAddSheet}.kt                     (T13)
                      …/feature/di/FeatureModule.kt                                                                                               (T14; see owners)
shared/app/           …/app/{Destinations,MainShell}.kt                                                                                            (T14)
androidApp/           kotlin/…/android/widget/ToggleTaskAction.kt                                                                                 (T1)
README.md (mitra-mobile/)                                                                                                                         (T15)
```

## Parallelism

Dependency waves (tasks in the same wave touch disjoint files and may run in parallel):

- **Wave 1:** T1 (task completion helper — domain/tasks + feature tasks/today VMs + widget action), T2 (core + domain habits/goals + testing), T3 (designsystem).
- **Wave 2 (after T2; screens also need T3):** T4 (schema + rows + OutboxEntities), T8, T9, T10, T12 (feature VMs/screens against the fakes). T7 (after T1 + T2 — edits Task VMs that T1 also edits). T11 (after T1 — reuses the completion helper for linked task rows).
- **Wave 3 (after T4):** T5 ∥ T6 (disjoint files; neither touches `DataModule.kt`). In parallel: T13 (after T1, T2, T8 — reuses `HabitRow`; after T7 is **not** required).
- **Wave 4:** T14 (after T5–T13 — it alone edits `Destinations.kt`, `MainShell.kt`, `DataModule.kt` and adds new VMs to `FeatureModule.kt`).
- **Wave 5:** T15.

Shared-file owners (never edit outside the owning task):
- `TodayViewModel.kt`: T1 (completion only) → T13. `TaskDetailViewModel.kt` / `ProjectDetailViewModel.kt` / `TasksViewModel.kt`: T1 → T7. `UiEvent.kt`: T1.
- `OutboxEntities` (`PendingSettings.kt`), `LocalData.kt`: T4. `DataModule.kt`: T14. `Destinations.kt` / `MainShell.kt`: T14.
- `FeatureModule.kt`: a task that **changes the constructor of an existing ViewModel** edits only that VM's single `viewModel { … }` line (T7: `TaskEditorViewModel`, `TaskDetailViewModel`, `ProjectDetailViewModel`, `TasksViewModel`; T13: `TodayViewModel`, `QuickAddViewModel`), so `:shared:feature` keeps compiling; **all new** ViewModels are registered by T14.
- `FakeTasksRepository.kt`, `TasksRepository.kt`, `Tasks.kt` (model): T7. `QuickAdd*`: T13. `Chips.kt`: T3.

---
### Task 1: Shared task complete/undo helper — retrofit the five ViewModels and the widget action

Phase 2 deferred item: the "toggle → haptic → `Completed · <title>` snackbar with Undo that reopens and deletes the spawned recurring occurrence" logic is copied in `TodayViewModel`, `TasksViewModel`, `TaskDetailViewModel`, `ProjectDetailViewModel` (and a complete-only variant in `FocusViewModel` / `ToggleTaskAction`). Extract it **before** Goals reuses it (goal detail and milestone sheet show linked tasks with a check).

**Files:**
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/tasks/TaskCompletion.kt`
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/common/CompletionEvents.kt`
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/common/UiEvent.kt` (`HapticKind.Milestone`)
- Modify: `…/feature/today/TodayViewModel.kt`, `…/feature/tasks/TasksViewModel.kt`, `…/feature/tasks/TaskDetailViewModel.kt`, `…/feature/tasks/ProjectDetailViewModel.kt`, `…/feature/tasks/FocusViewModel.kt` (completion code only)
- Modify: `androidApp/src/main/kotlin/tech/beyondscale/mitra/android/widget/ToggleTaskAction.kt`
- Test: `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/tasks/TaskCompletionTest.kt`, `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/common/CompletionEventsTest.kt`

**Interfaces (Produces):**
- domain: `sealed interface Completion { data object NotFound; data class Reopened(val task: Task); data class Completed(val task: Task, val spawnedId: String?) }`; `suspend fun TasksRepository.toggleCompletion(id: String): Completion` (done → `setDone(false)` → `Reopened`; open → `setDone(true)` → `Completed`); `suspend fun TasksRepository.complete(id: String): Completion` (never reopens; an already-done task returns `Completed(task, null)` without calling `setDone`); `suspend fun TasksRepository.undoCompletion(id: String, spawnedId: String?)` (`setDone(id, false)` then `delete(spawnedId)` when non-null).
- feature: `data class CompletionUndo(val id: String, val spawnedId: String?)`; `fun <A> EventQueue<A>.announce(c: Completion, undo: (CompletionUndo) -> A)` — `Completed` → `Haptic(Confirm)` **then** `Snack("Completed · <title>", undo(CompletionUndo(id, spawned)))`; `Reopened`/`NotFound` → nothing (callers that show "Marked as open" keep doing it themselves, as `TaskDetailViewModel` does today).
- `HapticKind.Milestone` (mapped in `EventEffect` to `HapticFeedbackType.LongPress`, the strongest available) — used by Habits (Task 8/13) for streak milestones.

**Requirements:**
- Each VM keeps its own `UndoComplete` action type (screens/shell already route them) but its body becomes `tasks.undoCompletion(a.id, a.spawned)`; each `toggle` becomes `queue.announce(tasks.toggleCompletion(id)) { TodayAction.UndoComplete(it.id, it.spawnedId) }` (per VM). `TasksViewModel` bulk complete keeps its own `"<n> completed"` snackbar but uses `complete()` per id. `FocusViewModel` and `ToggleTaskAction` call `complete()`.
- Pure refactor: no copy, order or behaviour change. Every existing Phase 2/3 test must stay green **unchanged**.

**Tests:**
- `TaskCompletionTest.toggleCompletesAndReturnsSpawned` — fake task `t1` recurring Daily due today → `Completed(t1, spawnedId != null)`; fake `calls` contains `setDone:t1:true`.
- `TaskCompletionTest.toggleReopensDoneTask` — done task → `Reopened`, `setDone:t1:false`.
- `TaskCompletionTest.unknownIdIsNotFound` — `"nope"` → `NotFound`, no repository call.
- `TaskCompletionTest.undoReopensAndDeletesSpawned` — `undoCompletion("t1", "t2")` → calls `setDone:t1:false` then `delete:t2`; `undoCompletion("t1", null)` → no delete.
- `TaskCompletionTest.completeNeverReopens` — done task → no `setDone` call.
- `CompletionEventsTest.completedEmitsHapticThenSnackWithUndo` — `announce(Completed(task "Call John", "t2"))` → events `[Haptic(Confirm), Snack("Completed · Call John", undo(CompletionUndo("t1","t2")))]`; `Reopened` → no events.

**Verify:** `cd mitra-mobile && ./gradlew :shared:domain:testAndroidHostTest :shared:feature:testAndroidHostTest :androidApp:assembleDebug`

---

### Task 2: Domain — habit/goal models, repository interfaces, ported selectors, GoalMath, HabitCheck, fakes, number formatting

**Files:**
- Modify: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/Money.kt` (`localeIN`, `jsNum`)
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/model/Habits.kt`, `…/domain/model/Goals.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/repository/HabitsRepository.kt`, `…/domain/repository/GoalsRepository.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/habits/HabitSelectors.kt`, `…/domain/habits/HabitCheck.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/goals/GoalSelectors.kt`, `…/domain/goals/GoalMath.kt`
- Create: `shared/testing/src/commonMain/kotlin/tech/beyondscale/mitra/testing/FakeHabitsRepository.kt`, `…/testing/FakeGoalsRepository.kt`
- Test: `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/MoneyFormatTest.kt` (extend), `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/habits/{HabitSelectorsTest,HabitCheckTest,HabitFixtures}.kt`, `…/domain/goals/{GoalSelectorsTest,GoalMathTest,GoalFixtures}.kt`

**Interfaces (Produces — every later task uses these names):**
- core: `fun localeIN(x: Double): String` (Indian grouping, ≤ 3 fraction digits, trailing zeros dropped, sign kept: `100000.0 → "1,00,000"`, `1234.5678 → "1,234.568"`, `0.5 → "0.5"`); `fun jsNum(x: Double): String` (`58000.0 → "58000"`, `12.5 → "12.5"`).
- `domain/model/Habits.kt`:
  - `enum class HabitType(val wire: String, val label: String) { Binary("binary","Yes / No"), Count("count","Count"), Duration("duration","Duration"), Quantity("quantity","Quantity") }` (+ `fromWire` → `Binary` on unknown)
  - `enum class TimeOfDay(val wire: String, val label: String) { Morning("morning","Morning"), Anytime("anytime","Anytime"), Evening("evening","Evening") }`
  - `val ALL_DAYS = List(7) { true }`, `val WEEKDAYS = listOf(true,true,true,true,true,false,false)`, `val THREE_A_WEEK = listOf(true,false,true,false,true,false,false)`
  - `data class Habit(val id: String, val name: String, val type: HabitType, val target: Int, val unit: String, val icon: String, val timeOfDay: TimeOfDay, val days: List<Boolean> /* Mon..Sun */, val reminder: String?, val goalId: String?, val gentle: Boolean, val archived: Boolean, val createdAt: String, val logs: Map<String, Int>)`
  - `data class HabitDraft(val name: String, val type: HabitType = Binary, val target: Int = 1, val unit: String = "", val icon: String = "repeat", val timeOfDay: TimeOfDay = Anytime, val days: List<Boolean> = ALL_DAYS, val reminder: String? = null, val goalId: String? = null, val gentle: Boolean = true)`
  - `sealed interface HabitChange { Name(v: String); Type(v: HabitType); Target(v: Int); Unit(v: String); Icon(v: String); TimeOfDay(v: TimeOfDay); Days(v: List<Boolean>); Reminder(v: String?); GoalId(v: String?); Gentle(v: Boolean); Archived(v: Boolean) }`
- `domain/model/Goals.kt`:
  - `enum class GoalType(val wire, val label) { Numeric("numeric","Numeric"), Milestone("milestone","Milestone"), Habit("habit","Habit-supported"), Date("date","Date-based") }`; `enum class GoalTerm(val wire) { Short("Short-term"), Medium("Medium-term"), Long("Long-term") }`; `enum class ReviewEvery(val wire: String, val days: Int) { Weekly("Weekly",7), Monthly("Monthly",30), Quarterly("Quarterly",90) }`; `enum class GoalState(val wire) { Active("active"), Paused("paused"), Completed("completed") }` (unknown wire → first constant)
  - `data class Milestone(val id: String, val title: String, val target: Double? = null, val targetDate: String? = null, val reachedOn: String? = null, val notes: String? = null)`; `data class ProgressEntry(val date: String, val value: Double, val note: String? = null)`
  - `data class Goal(val id: String, val name: String, val type: GoalType, val term: GoalTerm, val due: String, val startedOn: String, val current: Double, val target: Double, val unit: String /* "₹" or "" */, val formula: String?, val nextAction: String, val reviewEvery: ReviewEvery, val reviewOn: String, val state: GoalState, val notes: String, val createdAt: String, val milestones: List<Milestone>, val progressLog: List<ProgressEntry>)`
  - `data class GoalDraft(val name: String, val type: GoalType = Numeric, val term: GoalTerm = Medium, val due: String, val current: Double = 0.0, val target: Double = 100.0, val unit: String = "", val nextAction: String = "", val reviewEvery: ReviewEvery = Monthly, val milestoneTitles: List<String> = emptyList())`
  - `sealed interface GoalChange { Name; Type; Term; Due; Current(Double); Target(Double); Unit(String); Formula(String?); NextAction; ReviewEvery; ReviewOn(String); State(GoalState); Notes }`; `data class MilestoneDraft(val title: String, val target: Double? = null, val targetDate: String? = null, val notes: String? = null)`; `sealed interface MilestoneChange { Title(String); Target(Double?); TargetDate(String?); ReachedOn(String?); Notes(String?) }`
- `domain/repository/HabitsRepository.kt`:
  ```
  interface HabitsRepository {
    val habits: Flow<List<Habit>>                    // all, incl. archived, server order (createdAt asc)
    suspend fun refresh(): Outcome<Unit>
    suspend fun create(draft: HabitDraft): Habit
    suspend fun update(id: String, changes: List<HabitChange>)
    suspend fun delete(id: String)
    suspend fun setLog(id: String, date: String, value: Int?)   // null or 0 removes that day's log; unknown id = no-op
    companion object { const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED }
  }
  ```
- `domain/repository/GoalsRepository.kt`:
  ```
  interface GoalsRepository {
    val goals: Flow<List<Goal>>                      // all states, server order
    suspend fun refresh(): Outcome<Unit>
    suspend fun create(draft: GoalDraft): Goal
    suspend fun update(id: String, changes: List<GoalChange>)
    suspend fun delete(id: String)
    suspend fun logProgress(id: String, value: Double, note: String?)
    suspend fun addMilestone(goalId: String, draft: MilestoneDraft): Milestone?
    suspend fun updateMilestone(goalId: String, id: String, changes: List<MilestoneChange>)
    suspend fun deleteMilestone(goalId: String, id: String)
    companion object { const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED }
  }
  ```
- `domain/habits/HabitSelectors.kt` (ports of `selectors.ts` habits block; every function takes `today: String`): `habitDue(h, date)`, `habitDone(h, date)`, `habitValue(h, date)`, `streak(h, today)`, `bestStreak(h, today)` (90-day window), `weekRate(h, today)`, `data class Rate(val due: Int, val done: Int, val pct: Int)`, `rateDays(h, days, today)`, `weekDots(h, today): List<Boolean>`, `habitsDueToday(habits, today)`, `habitsDoneToday(habits, today)`, `habitsWeekRate(habits, today)`, `longestStreak(habits, today)` (`max(0, …)` over non-archived), plus shared label helpers `scheduleLabel(h)` (`"daily"` / `"Mon–Fri"` / `"3× a week"`), `habitSubline(h, today): HabitSubline` (`data class HabitSubline(val text: String, val recovering: Boolean)` — `HRow` lines 22–24: measurable prefix `"5/8 glasses · "`, binary `daily|Mon–Fri|N× a week`, measurable `"8 glasses · daily"`, then `" · 🔥 12-day streak"` or recovering `"Recovering — start again today"`), `stepFor(h)` (`count → 1`, else `max(1, jsRound(target / 2.0))`).
- `domain/habits/HabitCheck.kt`: `val STREAK_MILESTONES = setOf(7, 30, 100, 365)`; `data class HabitTap(val habitId: String, val date: String, val value: Int?, val snack: String?, val undoValue: Int?, val canUndo: Boolean, val reachedTarget: Boolean, val streakMilestone: Boolean)`; `fun habitTap(h, today): HabitTap` (port `HRow.tap`: done → `null`, `"Unchecked · Read"`, no undo; binary → `1`, `"Read ✓"` + `" · streak N+1"` when `streak > 0`, undo to `null`; measurable → `min(target, v + step)`, snack `"Stretch ✓ · target reached"` only when it reaches target, no undo); `fun habitComplete(h, today): HabitTap` (swipe right / detail "Done today": value `target`, `"Read ✓"`, undo to the previous value); `streakMilestone = reachedTarget && streak-after ∈ STREAK_MILESTONES`.
- `domain/goals/GoalSelectors.kt` (ports of the goals block): `goalProgress(g, habits, today): Int`, `enum class GoalStatus(val label: String) { OnTrack("On track"), Attention("Needs attention"), Behind("Behind"), Slipping("Slipping"), Completed("Completed"), Paused("Paused") }`, `goalStatus(g, habits, today)`, `enum class StatusTone { Success, Warning, Danger, Accent, Neutral }`, `statusTone(s)` (`STATUS_TONE`), `ringTone(s)` (Today/review ring: OnTrack → Accent, Slipping → Danger, else Warning), `cardRingTone(s)` (`GoalCard`: Completed → Success, OnTrack → Accent, Slipping → Danger, else Warning), `barTone(s)` (detail bar: Slipping → Danger, OnTrack/Completed → Accent, else Warning), `goalValueLabel(g)` / `goalTargetLabel(g)` (`"₹" + localeIN` or `jsNum`), `reviewDue(g, today)`, `monthsLeft(due, today)` (`"past due"` / `"30 days left"` / `"2 months left"` — `jsRound(d / 30.0)`), `dueMonthLabel(g)` (`"Mar 2027"`), `linksLine(goalId, tasks, habits, projects)` (`"1 project · 3 tasks · 2 habits"` / `"Nothing linked yet"`), `formulaText(g)` (web default per type), `blockers(g, status)` (web copy, line 115).
- `domain/goals/GoalMath.kt` (the "server-side derived data" rules in Global Constraints — used by `GoalsRepositoryImpl` **and** `FakeGoalsRepository`):
  `fun newGoal(id: String, draft: GoalDraft, today: String, createdAt: String, newId: () -> String): Goal` (port `GoalForm.save` create path: milestones from titles — numeric `target = jsRound(target * (i+1) / n)`, others `null`; `targetDate = addDays(today, jsRound(diffDays(due, today) * (i+1) / n.toDouble()))`; `startedOn = today`; `reviewOn = addDays(today, reviewEvery.days)`; milestone type `target = n`, `current = 0`), `fun Goal.derived(): Goal`, `fun Goal.applyChanges(changes: List<GoalChange>): Goal`, `fun Goal.applyProgress(value: Double, note: String?, date: String): Goal`, `fun Goal.withMilestone(m: Milestone): Goal`, `fun Goal.applyMilestone(id: String, changes: List<MilestoneChange>): Goal`, `fun Goal.withoutMilestone(id: String): Goal` — each returns `.derived()`.
- Fakes: `FakeHabitsRepository(initial: List<Habit> = emptyList())` / `FakeGoalsRepository(initial: List<Goal> = emptyList(), clock: AppClock)` with `MutableStateFlow`s, `calls: MutableList<String>` (`"create:<name>"`, `"update:<id>"`, `"log:<id>:<date>:<value>"`, `"delete:<id>"`, `"progress:<id>:<value>"`, `"addMilestone:<goalId>"`, `"updateMilestone:<mid>"`, `"deleteMilestone:<mid>"`), `refreshResult` overridable; the goals fake applies `GoalMath` so VM tests see the server numbers. Mirror `FakeMoneyRepository`.

**Requirements:**
- `streak`: start at today; if not done today start yesterday; walk back ≤ 400 days skipping non-due days; stop at the first due-and-not-done day (`selectors.ts` line 26). `habitDone = (logs[date] ?: 0) >= target`.
- `goalProgress`: milestone → `jsRound(reached / count * 100)` (0 when none); date → `min(100, jsRound(diffDays(today, startedOn) / max(1, diffDays(due, startedOn)) * 100))`; habit → average of `rateDays(h, 30).pct` over habits with `goalId == g.id` (falls through to numeric when none); else `min(100, jsRound(current / max(1.0, target) * 100))`.
- `goalStatus`: completed/paused first; `expected = min(100, max(0, elapsed / total * 100))` as `Double` (not rounded); `stale = lastLog == null || diffDays(today, lastLog) > 21`; slipping if `stale && p < expected − 10`, on-track if `p >= expected − 5`, attention if `p >= expected − 15`, else behind.
- Fixtures: copy the web seed habits/goals (`mitra-frontend/src/store/seed.ts`) so tests read like the web.

**Tests (today `2026-10-14`, a Wednesday):**
- `MoneyFormatTest.localeInMatchesToLocaleString` — `localeIN(100000.0) == "1,00,000"`, `localeIN(1234.5678) == "1,234.568"`, `localeIN(12.5) == "12.5"`, `jsNum(58000.0) == "58000"`, `jsNum(0.1) == "0.1"`.
- `HabitSelectorsTest.streakCountsFromYesterdayWhenTodayOpen` — daily binary, logs 11, 12, 13 Oct → `streak == 3`; add 14 Oct → `4`.
- `HabitSelectorsTest.streakSkipsRestDays` — weekdays habit, logs Thu 8, Fri 9, Mon 12, Tue 13 (Sat/Sun not due, Wed 7 missing) → `streak == 4`.
- `HabitSelectorsTest.weekDotsAreMondayBased` — logs Mon 12 and Wed 14 → `[true,false,true,false,false,false,false]`.
- `HabitSelectorsTest.weekRateRoundsLikeJs` — daily, 5 of the last 7 days done → `71`; 1 of 2 due → `50`.
- `HabitSelectorsTest.dueTodayExcludesArchivedAndRestDays` — on Sat `2026-10-17` a weekdays habit is not due; an archived daily habit is excluded from `habitsDueToday` and `habitsWeekRate`.
- `HabitSelectorsTest.measurablePartialIsNotDone` — count target 8, value 5 → not done, subline starts `"5/8 glasses · 8 glasses · daily"`.
- `HabitSelectorsTest.sublineShowsRecovering` — streak 0 → `recovering == true`, text ends `"Recovering — start again today"`.
- `HabitCheckTest.binaryTapShowsNextStreak` — streak 3, today open → value `1`, snack `"Read ✓ · streak 4"`, `undoValue == null`, `canUndo`.
- `HabitCheckTest.doneTapUnchecks` — value `null`, snack `"Unchecked · Read"`, `!canUndo`.
- `HabitCheckTest.durationStepsByHalfTarget` — target 20 → `10` (snack `null`), then `20` (`"Stretch ✓ · target reached"`).
- `HabitCheckTest.streakMilestoneAtSeven` — streak 6 → tap → `streakMilestone == true`; streak 7 → tap → `false`.
- `GoalSelectorsTest.milestoneProgressIsReachedOverTotal` — 1 of 4 → `25`; none → `0`.
- `GoalSelectorsTest.dateGoalProgressIsElapsedTime` — started `2026-10-01`, due `2026-12-30` → `14`.
- `GoalSelectorsTest.habitGoalAveragesLinked30DayRates` — linked habits at 80 % and 61 % → `71` (JS half-up); no linked habit → numeric fallback.
- `GoalSelectorsTest.numericCapsAndGuardsZeroTarget` — 150/100 → `100`; target `0`, current `0` → `0`.
- `GoalSelectorsTest.goalStatusThresholds` — started `2026-07-16`, due `2027-01-12` (expected 50): p 45 → OnTrack; 44 → Attention; 34 → Behind; 39 with last log 22 days ago → Slipping; 39 with last log 21 days ago → Attention; empty log + 39 → Slipping; paused → Paused regardless.
- `GoalSelectorsTest.labelsAndMonthsLeft` — ₹ 100000 → `"₹1,00,000"`; plain 12.5 → `"12.5"`; due +45 d → `"2 months left"`, +30 → `"30 days left"`, −1 → `"past due"`; `reviewDue` true on `reviewOn == today` only while active.
- `GoalMathTest.newGoalSpreadsMilestones` — numeric target 100000, due today+180, titles `25%,50%,75%,Done` → targets `25000,50000,75000,100000`, target dates +45/+90/+135/+180, `reviewOn == "2026-11-13"`; same draft as Milestone type → `target == 4.0`, `current == 0.0`, milestone targets null.
- `GoalMathTest.numericProgressAddsAndAutoTicks` — current 50000 (milestones 25000 reached, 75000, 100000) + `applyProgress(30000.0, "bonus", "2026-10-14")` → `current == 80000.0`, 75000 milestone `reachedOn == "2026-10-14"`, last entry `(2026-10-14, 80000.0, "bonus")`, state Active.
- `GoalMathTest.reachingTargetCompletesOnlyActive` — +20000 more → Completed; the same on a Paused goal stays Paused.
- `GoalMathTest.dateGoalProgressSetsValue` — date goal current 10 + 4 → `current == 4.0` (server parity).
- `GoalMathTest.milestoneTypeDerivesCurrentAndTarget` — 3 milestones, `applyMilestone(m1, [ReachedOn("2026-10-14")])` → `current 1.0`, `target 3.0`, entry `(2026-10-14, 1.0, m1.title)`; un-reach → `current 0.0`, log unchanged; numeric goal reaching a milestone → no entry.

**Verify:** `cd mitra-mobile && ./gradlew :shared:core:testAndroidHostTest :shared:domain:testAndroidHostTest :shared:feature:testAndroidHostTest` (feature tests compile `shared/testing`, so the fakes are type-checked).

---

### Task 3: Design system — ring, heatmap, line chart, week dots, month calendar, icon box, confirm dialog, success chip

**Files:**
- Create: `shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/components/Ring.kt`, `Heatmap.kt`, `LineChart.kt`, `WeekDots.kt`, `CalendarMonth.kt`, `IconBox.kt`, `ConfirmDialog.kt`
- Modify: `…/designsystem/components/Chips.kt` (`ChipTone.Success`)
- Test: `shared/designsystem/src/commonTest/kotlin/tech/beyondscale/mitra/designsystem/components/{LineChartScaleTest,CalendarCellsTest,RingSweepTest}.kt`

**Interfaces (Produces):**
- `enum class Tone { Accent, Success, Warning, Danger, Neutral }` + `@Composable fun Tone.color(): Color` (from `Mitra.colors`).
- `@Composable fun Ring(pct: Int, size: Dp = 44.dp, stroke: Dp = 5.dp, label: String? = null, tone: Tone = Tone.Accent, modifier: Modifier = Modifier)` — web `Ring` (`components/ui/index.tsx` line 51); semantics `"<label> progress"`; `fun ringSweep(pct: Int): Float` (clamped 0..360).
- `@Composable fun Heatmap(cells: List<Int> /* 0..4 */, columns: Int = 7, modifier)` — web `Heat` (line 276); levels map to accent alpha steps.
- `@Composable fun LineChart(values: List<Double>, labels: List<String>, goal: Double? = null, format: (Double) -> String, modifier: Modifier = Modifier.height(130.dp))` — web `LineChart` (line 251) with area fill, dots, dashed goal line; press-and-drag tooltip reusing `scrubIndex` from `ScrubBars.kt`; `fun chartMax(values: List<Double>, goal: Double?): Double` (max of values and goal, ≥ 1).
- `@Composable fun WeekDots(days: List<Boolean>, todayIndex: Int, modifier)` — web `WeekDots` (line 209); today outlined; content description `"Mon done, Tue missed, …"`.
- `data class CalendarCell(val date: String?, val day: Int, val inMonth: Boolean)`; `fun calendarCells(monthFirst: String): List<CalendarCell>` (Monday-first, leading days of the previous month as `inMonth = false` — web history grid lines 116–117); `@Composable fun CalendarMonth(cells: List<CalendarCell>, decorate: @Composable (CalendarCell) -> CalendarDayStyle, onTap: (String) -> Unit, modifier)` with `data class CalendarDayStyle(val hit: Boolean, val miss: Boolean, val today: Boolean, val future: Boolean, val partial: Boolean)`; cells ≥ 40 dp, `Role.Button`, description `"14 October, done"`.
- `@Composable fun IconBox(icon: ImageVector, tone: Tone = Tone.Neutral, size: Dp = 36.dp)` — web `IconBox`.
- `@Composable fun MitraConfirmDialog(title: String, body: String, confirmLabel: String, danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit)` — same look as the existing `AlertDialog`s in `TaskDetailScreen`/`RecurringScreen` (do **not** migrate those call sites in this task).
- `ChipTone.Success` (Phase 3 deferred minor) styled from `Mitra.colors.success` like `Warning`.

**Requirements:** Stateless, previewable, no domain imports; follow `Progress.kt`/`ScrubBars.kt`/`Donut.kt` styling; touch targets ≥ 48 dp where tappable.

**Tests:**
- `RingSweepTest.clamps` — `ringSweep(-5) == 0f`, `ringSweep(25) == 90f`, `ringSweep(140) == 360f`.
- `LineChartScaleTest.goalRaisesMax` — `chartMax([1.0, 3.0], 5.0) == 5.0`; `chartMax([], null) == 1.0`.
- `CalendarCellsTest.octoberStartsThursday` — `calendarCells("2026-10-01")` → first 3 cells are 28, 29, 30 Sep (`inMonth false`), cell 3 is `2026-10-01`, total in-month cells 31; `calendarCells("2026-06-01")` (Monday) → no leading cells.

**Verify:** `cd mitra-mobile && ./gradlew :shared:designsystem:testAndroidHostTest`

---

### Task 4: Data — schema v4 (habits + goals tables) with an in-place v3→v4 migration, row mappers, outbox entities

**Files:**
- Create: `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Habits.sq`, `HabitLogs.sq`, `Goals.sq`, `Milestones.sq`, `GoalProgress.sq`
- Create: `shared/data/src/commonMain/sqldelight/migrations/3.sqm`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/HabitRows.kt`, `GoalRows.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/LocalData.kt`, `…/data/sync/PendingSettings.kt` (`OutboxEntities` + `HABIT`, `HABIT_LOG`, `GOAL`, `GOAL_PROGRESS`, `MILESTONE`)
- Test: `shared/data/src/androidHostTest/kotlin/tech/beyondscale/mitra/data/db/MigrationTest.kt` (extend), `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/local/{HabitRowsTest,GoalRowsTest}.kt`, `…/data/local/LocalStoreTest.kt` (extend)

**Interfaces:**
- Tables (booleans `INTEGER 0/1`; `days` as a 7-char text `"1111100"`; doubles as `REAL`):
  - `habits(id PK, name, type, target INTEGER NOT NULL, unit, icon, timeOfDay, days TEXT NOT NULL, reminder, goalId, gentle INTEGER NOT NULL, archived INTEGER NOT NULL, createdAt TEXT NOT NULL, position INTEGER NOT NULL)`
  - `habit_logs(habitId TEXT NOT NULL, date TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (habitId, date))`
  - `goals(id PK, name, type, term, due, startedOn, current REAL NOT NULL, target REAL NOT NULL, unit TEXT NOT NULL, formula, nextAction, reviewEvery, reviewOn, status, notes, createdAt, position INTEGER NOT NULL)`
  - `milestones(id PK, goalId TEXT NOT NULL, title, target REAL, targetDate, reachedOn, notes, position INTEGER NOT NULL)` + `CREATE INDEX milestones_goal ON milestones(goalId)`
  - `goal_progress(goalId TEXT NOT NULL, position INTEGER NOT NULL, date TEXT NOT NULL, value REAL NOT NULL, note TEXT, PRIMARY KEY (goalId, position))`
- Queries (names used by Tasks 5/6): habits `selectAll` (`ORDER BY position, id`), `selectById`, `selectIds`, `upsert`, `delete`, `deleteAll`, `nextPosition`; habit_logs `selectAll`, `selectForHabit`, `upsert`, `deleteOne(habitId, date)`, `deleteForHabit`, `deleteForHabitOnOrAfter(habitId, date)`, `deleteAll`; goals `selectAll`, `selectById`, `selectIds`, `upsert`, `delete`, `deleteAll`, `nextPosition`; milestones `selectAll` (`ORDER BY goalId, position`), `selectForGoal`, `upsert`, `delete`, `deleteForGoal`, `deleteAll`; goal_progress `selectAll` (`ORDER BY goalId, position`), `insert`, `deleteForGoal`, `deleteAll`.
- `HabitRows.kt`: `fun Habits.toDomain(logs: Map<String, Int>): Habit`, `fun Habit.toRow(position: Long): Habits`, `fun daysToText(List<Boolean>): String` / `textToDays(String): List<Boolean>` (malformed → `ALL_DAYS`); `GoalRows.kt`: `fun assembleGoals(goals: List<Goals>, milestones: List<Milestones>, progress: List<Goal_progress>): List<Goal>` (grouping in one pass) and `fun Goal.writeTo(db: MitraDb, position: Long)` (replaces the goal row, its milestones and its progress rows). Unknown wire enum → first constant, never throw.
- `LocalData.clearUserData()` also `deleteAll()`s the five tables.

**Requirements:**
- `3.sqm` header `-- v3 (Phase 3 Money) → v4 (Phase 4 Habits + Goals)`; creates the tables + index exactly as the `.sq` files; no data change to existing tables. `MitraDb.Schema.version == 4L`.

**Tests:**
- `MigrationTest.phase3DatabaseUpgradesInPlace` — build a genuine v3 DB (existing fixture + `migrate(driver, 1, 3)`), insert a transaction and an outbox row → `migrate(driver, 3, 4)` → both survive, `SELECT count(*) FROM habits == 0`, `MitraDb.Schema.version == 4L`. Update the earlier tests' expected version to 4.
- `HabitRowsTest.roundTripsEveryField` — habit with `days = WEEKDAYS`, `reminder = null`, `archived = true`, logs `{2026-10-13: 1}` → equal after `toRow`/`toDomain`; `textToDays("bad") == ALL_DAYS`.
- `GoalRowsTest.assemblesMilestonesAndProgressInOrder` — 2 goals, milestones/progress inserted out of order → each goal's lists ordered by `position`; `formula = null`, milestone `target = null` survive.
- `LocalStoreTest.clearUserDataRemovesHabitsAndGoals` — one row per new table → `clearUserData()` → all counts `0`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest`

---

### Task 5: Data — HabitsRepositoryImpl (DTOs, refresh with the 400-day window, writes, log PUTs, echo rule)

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/remote/HabitDtos.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/HabitPatches.kt`, `HabitsRepositoryImpl.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/remote/HabitDtosTest.kt`, `…/data/repository/{HabitPatchesTest,HabitsRepositoryImplTest}.kt`

**Interfaces:**
- Consumes: `HabitsRepository`, models (Task 2); tables, `HabitRows`, `OutboxEntities.HABIT/HABIT_LOG` (Task 4); `shareForRepository`, `OutboxWriter`, `SyncEngine`, `PrefStore` (Phases 2–3).
- Produces: `class HabitsRepositoryImpl(api: ApiClient, db: MitraDb, prefs: PrefStore, outbox: OutboxWriter, engine: SyncEngine, clock: AppClock, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) : HabitsRepository` (DI added by Task 14); `@Serializable HabitDto` (`logs: Map<String, Int> = emptyMap()`, optional `reminder`, `goalId`, `archived = false`) + `toDomain()`; `fun Habit.createBody(): JsonObject`; `fun habitPatch(changes: List<HabitChange>): JsonObject` (changed keys only; `Reminder(null)`/`GoalId(null)` → JSON `null`; `Days` → JSON array of 7 booleans).

**Requirements (copy the `MoneyRepositoryImpl` skeleton: `writes`, `write { }`, `applyEcho`, `pendingIds`, `decode`, refresh with 3 attempts, owner check, race check, `REFRESH_SUPERSEDED`):**
- Flow: `combine(habits.selectAll, habit_logs.selectAll)` → `List<Habit>` with logs grouped per habit → `shareForRepository(scope)`; archived habits stay in the flow (selectors filter).
- `create(draft)`: `id = newId()`, `createdAt = clock.now()` ISO, `position = nextPosition`, POST `habits` (body as web `addHabit`: `{ id, name, type, target, unit, icon, timeOfDay, days, reminder|null, goalId|null, gentle }`), `entityId = id`.
- `update(id, changes)`: apply locally, PATCH `habits/$id` with `habitPatch`, `coalesceKey = "habit:$id"`. `delete(id)`: delete habit + its logs locally, DELETE `habits/$id`.
- `setLog(id, date, value)`: unknown habit → no-op; `value == null || value == 0` → `deleteOne`, else `upsert`; PUT `habits/$id/logs` `{ date, value|null }` (`0` is sent as `null`), `coalesceKey = "habit-log:$id:$date"`, `entityId = id`.
- Handlers `HABIT` and `HABIT_LOG` → `onSuccess`: decode `HabitDto` (`{ok:true}` from DELETE → ignore) → echo applies **only if** the habit still exists locally **and** `id ∉ pendingIds(HABIT) ∪ pendingIds(HABIT_LOG)`; it replaces the habit row (keeping `position`) and the habit's logs dated on/after the window cut-off. `onRejected` → `refresh()`.
- Refresh (`GET habits`): pending = `pendingIds(HABIT) ∪ pendingIds(HABIT_LOG)`; delete local habits absent from the server unless pending; for each non-pending server habit: upsert with `position = index`, then **logs window**: `cutoff = addDays(clock.today(), -398)` (server keeps 400 days, UTC; 2-day margin) — delete local logs `>= cutoff` that the server didn't return, upsert the server's logs, keep older local logs untouched.

**Tests** (`MockServer` + `testDriver()` + `FixedClock("2026-10-14T10:00:00+05:30")`, as `MoneyRepositoryImplTest`):
- `HabitDtosTest.decodesOptionalFields` — JSON without `reminder`, `goalId`, `archived` → `null`, `null`, `false`; `logs {"2026-10-13":1}` mapped; unknown `type` → `Binary`.
- `HabitPatchesTest.clearsSendNull` — `[Reminder(null), GoalId(null)]` → `{"reminder":null,"goalId":null}`; `[Days(WEEKDAYS)]` → `{"days":[true,true,true,true,true,false,false]}`; `[]` → `{}`.
- `HabitsRepositoryImplTest.refreshReplacesHabitsAndLogs` — 2 habits (one archived) → flow has both with logs; second refresh without one → removed.
- `HabitsRepositoryImplTest.checkThenUncheckCoalescesIntoOnePut` — offline `setLog(h, "2026-10-14", 1)` then `setLog(h, "2026-10-14", null)` → one outbox row `PUT habits/h/logs` body `{"date":"2026-10-14","value":null}`; local log absent.
- `HabitsRepositoryImplTest.logsForDifferentDaysQueueSeparately` — dates 13 and 14 → two rows, in order.
- `HabitsRepositoryImplTest.echoDoesNotOverwritePendingLogs` — PUTs for 13 and 14 queued; the first is acknowledged with a server habit containing only `13` → local still has `14`.
- `HabitsRepositoryImplTest.refreshKeepsLogsOutsideWindow` — local log `2025-08-01` not on the server → kept; local log `2026-10-01` not on the server (habit not pending) → deleted.
- `HabitsRepositoryImplTest.refreshKeepsPendingHabit` — habit created offline, absent from `GET habits` → survives; habit with a queued PATCH keeps its local name.
- `HabitsRepositoryImplTest.offlineCreateCheckDeleteReachesServerInOrder` — create, `setLog`, `update(Name)`, delete → MockServer sees `POST habits`, `PUT habits/<id>/logs`, `PATCH habits/<id>`, `DELETE habits/<id>` in that order; local list empty; no echo resurrects it.
- `HabitsRepositoryImplTest.refreshRacingAWriteIsDiscarded` and `refreshAfterSignOutWritesNothing` — as Money.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest`

---

### Task 6: Data — GoalsRepositoryImpl (DTOs, refresh, writes with local GoalMath, progress action, milestones, goal-scoped echo)

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/remote/GoalDtos.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/GoalPatches.kt`, `GoalsRepositoryImpl.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/remote/GoalDtosTest.kt`, `…/data/repository/{GoalPatchesTest,GoalsRepositoryImplTest}.kt`

**Interfaces:**
- Consumes: `GoalsRepository`, models, `GoalMath` (Task 2); tables, `GoalRows`, `OutboxEntities.GOAL/GOAL_PROGRESS/MILESTONE` (Task 4).
- Produces: `class GoalsRepositoryImpl(api, db, prefs, outbox, engine, clock, scope, io = Dispatchers.IO) : GoalsRepository`; `@Serializable GoalDto`, `MilestoneDto`, `ProgressDto` + `toDomain()`; `fun Goal.createBody(): JsonObject`; `fun goalPatch(changes: List<GoalChange>): JsonObject` (changed keys only; `Formula(null)` → `"formula": null`); `fun milestoneBody(m: Milestone): JsonObject`; `fun milestonePatch(changes: List<MilestoneChange>): JsonObject` (nullable fields → JSON `null`).

**Requirements:**
- Flow: `combine(goals.selectAll, milestones.selectAll, goal_progress.selectAll)` → `assembleGoals` → `shareForRepository(scope)`.
- Every write = read the local goal → apply the matching `GoalMath` function → `Goal.writeTo(db, position)` → enqueue, inside one `write { }`:
  - `create(draft)`: `newGoal(newId(), draft, clock.today(), now, ::newId)`; POST `goals` with the full body (as web `addGoal`, milestones with their ids), `entityId = id`.
  - `update(id, changes)`: `applyChanges`; PATCH `goals/$id` `goalPatch`, `coalesceKey = "goal:$id"`. `delete(id)`: remove goal + milestones + progress; DELETE `goals/$id`.
  - `logProgress(id, value, note)`: `applyProgress(value, note, clock.today())`; enqueue `GOAL_PROGRESS` POST `goals/$id/progress` `{ id: newId(), value, note|null, date: clock.today() }` — no coalesce key. (The `id` is stripped by today's server and becomes the idempotency key if the user approves Backend gap 1.)
  - `addMilestone`: `Milestone(newId(), …)` → `withMilestone`; POST `goals/$goalId/milestones` `milestoneBody`, entity `MILESTONE`, `entityId = goalId`. `updateMilestone`: `applyMilestone`; PATCH `goals/$goalId/milestones/$mid`, `coalesceKey = "milestone:$mid"`. `deleteMilestone`: `withoutMilestone`; DELETE `goals/$goalId/milestones/$mid`.
- **Goal-scoped pending:** `pendingGoals = pendingIds(GOAL) ∪ pendingIds(GOAL_PROGRESS) ∪ pendingIds(MILESTONE)` (all keyed by goal id).
- Handlers `GOAL`, `GOAL_PROGRESS`, `MILESTONE` → `onSuccess`: decode `GoalDto` (`{ok:true}` → ignore) → echo only if the goal still exists locally **and** `id ∉ pendingGoals` → `writeTo` (keeps `position`). This alone keeps a queued milestone DELETE from being undone by an earlier echo. `onRejected` → `refresh()`.
- Refresh (`GET goals`): delete local goals absent from the server unless pending; replace every non-pending goal wholesale (`writeTo` with `position = index`); pending goals keep their local copy (milestones and progress included).

**Tests:**
- `GoalDtosTest.decodesNumbersAndOptionals` — `current: 58000`, `target: 100000.5`, milestone without `target`/`notes`, progress without `note`, missing `formula` → decodes; unknown `status` → `Active`.
- `GoalPatchesTest.onlyChangedKeys` — `[Formula(null)]` → `{"formula":null}`; `[State(Paused)]` → `{"status":"paused"}`; `[ReviewOn("2026-11-13"), Target(120000.0)]` → both keys.
- `GoalsRepositoryImplTest.refreshReplacesGoalsMilestonesAndProgress` — fixture with 2 goals → flow equals the DTOs; second refresh without one → removed.
- `GoalsRepositoryImplTest.logProgressAppliesLocallyAndSendsLocalDate` — clock `2026-11-01T00:30:00+05:30` → local `current` moved per `GoalMath`, body `"date":"2026-11-01"`, `"value":8000.0`, `"note":null`, `"id"` present.
- `GoalsRepositoryImplTest.echoEqualsLocalApplication` — log progress on a numeric goal with milestones; MockServer answers with the server's computed goal (fixture built by hand from `goals.service.ts` rules) → after the echo, the flow value equals the pre-echo local value (no jump).
- `GoalsRepositoryImplTest.progressRowsNeverCoalesce` — two `logProgress` calls → two outbox rows.
- `GoalsRepositoryImplTest.goalEchoSkippedWhileGoalHasQueuedRows` — progress POST acknowledged while a milestone POST for the same goal is still queued → local milestone still present.
- `GoalsRepositoryImplTest.deletedMilestoneNotReintroduced` — milestone DELETE queued; an earlier PATCH echo and a refresh both return the milestone → it stays deleted locally.
- `GoalsRepositoryImplTest.deletedGoalNotResurrected` — delete while its create POST is in flight → echo ignored; outbox `POST`, `DELETE`.
- `GoalsRepositoryImplTest.offlineGoalSequenceReachesServerInOrder` — create (with 2 milestone titles), add milestone, reach it, log progress, delete one milestone → MockServer order `POST goals`, `POST goals/<id>/milestones`, `PATCH goals/<id>/milestones/<mid>`, `POST goals/<id>/progress`, `DELETE goals/<id>/milestones/<mid2>`.
- `GoalsRepositoryImplTest.rejectedProgressRefreshesServerCopy` — 400 on progress → refresh → local equals server.
- `GoalsRepositoryImplTest.refreshAfterSignOutWritesNothing`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest`

---

### Task 7: Tasks ↔ Goals links — task goal picker, project goal, goal chips, and the Phase 2 project-echo fix

**Files:**
- Modify: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/model/Tasks.kt` (`TaskChange.GoalId`), `…/domain/repository/TasksRepository.kt` (`createProject(name, emoji, goalId: String? = null)`, `updateProject(id, name, emoji, goalId: String?)`)
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImpl.kt`, `TaskPatches.kt`, `…/data/remote/TaskDtos.kt` (project body)
- Modify: `shared/testing/src/commonMain/kotlin/tech/beyondscale/mitra/testing/FakeTasksRepository.kt`
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/tasks/TaskEditorViewModel.kt`, `TaskFormSheet.kt`, `TaskDetailViewModel.kt`, `TaskDetailScreen.kt`, `ProjectFormSheet.kt`, `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `TasksViewModel.kt`; `…/feature/di/FeatureModule.kt` (only the four existing lines)
- Test: `…/data/repository/{TaskPatchesTest,TasksRepositoryImplTest}.kt`, `…/feature/tasks/{TaskEditorViewModelTest,TaskDetailViewModelTest,ProjectDetailViewModelTest,ProjectFormTest}.kt` (extend)

**Interfaces:**
- Consumes: `GoalsRepository`, `Goal`, `GoalState`, `FakeGoalsRepository` (Task 2).
- Produces: `TaskChange.GoalId(val value: String?)`; `TaskEditorAction.Goal(val id: String?)`; `TaskEditorUiState.goalOptions: List<Pair<String, String>>` (active goals id → name); `TaskDetailUiState.goal: GoalChip?` and `ProjectDetailUiState.goal: GoalChip?` (`data class GoalChip(val id: String, val name: String, val due: String)`); routes gain `onOpenGoal: (String) -> Unit` (wired by Task 14). Constructors: `TaskEditorViewModel(tasks, goals: GoalsRepository, clock)`, `TaskDetailViewModel(…, goals: GoalsRepository, …)`, `ProjectDetailViewModel(…, goals, …)`, `TasksViewModel(…, goals, …)` — each updates its own `FeatureModule` line.

**Requirements (port `TaskForm` line 64, `ProjectForm` line 89, `ProjectsScreen` line 178, `ProjectDetail` line 219, `TaskDetail` line 262 in `screens/tasks.tsx`):**
- Task form: `Links to goal` picker (`None` + **active** goals); edit sends `TaskChange.GoalId` only when changed (`{"goalId": null}` clears). Create keeps `TaskDraft.goalId` (defaults from goal detail "Add task").
- Task detail "Linked": `Goal: <name ≤ 30 chars>` chip → `onOpenGoal`; unknown/deleted goal id → no chip; empty copy `"Nothing linked yet — set a goal in Edit."` when no goal (and no amount).
- Project form: `Contributes to goal` picker (`None` + **all** goals) with hint `"Completed tasks in this project show as evidence on the goal."`; create/update send `goalId` (`null` clears). Project detail: goal chip + `"Due 12 Apr"` chip (`fmtDay(due, today, relative = false)`); projects list card: goal chip with name stripped of a leading `"Build "`/`"Ship "`, ≤ 22 chars.
- **Phase 2 deferred fix:** a task echo must not re-attach a task to a project whose DELETE is queued — scrub `projectId` when it is in `pendingIds(PROJECT) − local project ids` (Money `deletedRefs` rule). `goalId` is never scrubbed (Global Constraints).

**Tests:**
- `TaskPatchesTest.goalIdNullClears` — `[GoalId(null)]` → `{"goalId":null}`; `[GoalId("g1")]` → `{"goalId":"g1"}`.
- `TasksRepositoryImplTest.echoDoesNotReattachDeletedProject` — task PATCH in flight, project DELETE queued, echo carries `projectId = p1` → local `projectId == null`.
- `TasksRepositoryImplTest.projectGoalIdRoundTrips` — `createProject("Launch", "🚀", "g1")` body has `"goalId":"g1"`; `updateProject(…, null)` sends `"goalId":null`.
- `TaskEditorViewModelTest.goalPickerListsActiveGoalsOnly` — goals active/paused/completed → options contain only the active one; choose it on edit → `update(id, [GoalId("g1")])`.
- `TaskEditorViewModelTest.createFromGoalKeepsGoalId` — `Open(TaskDraft("x", goalId = "g1"))` → created task `goalId == "g1"`.
- `TaskDetailViewModelTest.showsLinkedGoalChip` — goal `g1` "Build ₹1,00,000 emergency fund" → `goal.name == "Build ₹1,00,000 emergency fund"`; task with `goalId = "gone"` → `goal == null`.
- `ProjectDetailViewModelTest.showsGoalAndDueChips`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest :shared:feature:testAndroidHostTest`

---

### Task 8: Habits list (Today / Week, time-of-day groups, check-off, swipe) + habit form sheet

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/habits/HabitRow.kt`, `HabitsViewModel.kt`, `HabitsScreen.kt`, `HabitEditorViewModel.kt`, `HabitFormSheet.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/habits/{HabitRowUiTest,HabitsViewModelTest,HabitEditorViewModelTest}.kt`

**Interfaces:**
- Consumes: `HabitsRepository`, `GoalsRepository`, selectors + `habitTap`/`habitComplete` (Task 2), `IconBox`, `WeekDots`, `Tone`, `ChipTone.Success` (Task 3), `SwipeRow`, `MitraSegmented`, `EmptyState`, `SectionHeader` (Phases 1–3), `HapticKind.Milestone` (Task 1).
- Produces (reused by Tasks 9, 13): `data class HabitRowUi(val id: String, val name: String, val icon: String, val done: Boolean, val partial: String? /* "5/8" */, val sub: String, val recovering: Boolean, val checkLabel: String /* "Undo" | "Log +1 glasses" | "Mark done" */)`; `fun habitRowUi(h: Habit, today: String): HabitRowUi`; `@Composable fun HabitRow(row: HabitRowUi, onCheck: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier)`; `@Composable fun SwipeHabitRow(row, onCheck, onComplete, onArchive, onOpen)`.
- Produces: `class HabitsViewModel(habits: HabitsRepository, clock: AppClock)`; `sealed interface HabitsAction { data class Tab(val index: Int) /* 0 Today, 1 Week */; data class Check(val id: String); data class Complete(val id: String); data class Archive(val id: String); data class SetLog(val id: String, val date: String, val value: Int?) /* Undo */; data class Unarchive(val id: String); data object Refresh }`; `@Composable fun HabitsRoute(onOpenHabit: (String) -> Unit, onNewHabit: () -> Unit)`.
- Produces (shell-hosted, like `TaskEditorViewModel`): `class HabitEditorViewModel(habits: HabitsRepository, goals: GoalsRepository, clock: AppClock)`; `sealed interface HabitEditorAction { data class Open(val name: String = ""); data class Edit(val id: String); data object Dismiss; data class Name(val v: String); data class Icon(val id: String); data class Type(val t: HabitType); data class Target(val v: String); data class Unit(val v: String); data class TimeOfDay(val t: TimeOfDay); data class Schedule(val preset: String); data class ToggleDay(val i: Int); data class ReminderOn(val on: Boolean); data class ReminderTime(val hhmm: String); data class Goal(val id: String?); data class Gentle(val on: Boolean); data object Save }`; `@Composable fun HabitFormSheet(vm: HabitEditorViewModel, onDismiss: () -> Unit)`; event `HabitEditorEvent.Created(id)` for the shell (navigate to Habits).

**Requirements (port `HabitsScreen`, `HRow`, `HabitForm` in `screens/habits.tsx` lines 20–100):**
- Top bar (own, like `TasksScreen`): `Habits` + ＋. Stats: `"1 / 3"` done today, `"57%"` this week, `"12"` longest active streak. Segmented `Today` / `Week`; groups `Morning` / `Anytime` / `Evening` (Today = due today only; empty groups hidden); `"Nothing scheduled today"` / `"Switch to Week to see all habits."` when Today has none; empty list `"No habits yet"` / `"Start with one small daily habit — Mitra keeps streaks gentle."` + `New habit`. "This week" card: name, `WeekDots` (today outlined), `"71%"`, footer `"Mon → Sun · today outlined"`.
- Check button: `habitTap` → `setLog(id, today, value)`; snackbar from `tap.snack` (Undo → `SetLog(id, today, undoValue)` when `canUndo`); `Haptic(Confirm)` when `reachedTarget`, `Haptic(Milestone)` when `streakMilestone`. Swipe right → `habitComplete`; swipe left → `update(id, [Archived(true)])` + `"Habit archived · history kept"` with Undo (`Archived(false)`) — deviation (a). The date is always `clock.today()` at tap time.
- Form (`Edit habit` / `New habit`): Name (`"e.g. Evening stretch"`), Icon chips (web `ICONS_PICK`), Type segmented (unit defaults: duration `minutes`, count `times`, quantity `glasses`, binary `""`), Target (digits, hidden for binary) + unit field, Time of day, Schedule chips `Every day` / `Weekdays` / `3× a week` / `Custom` (derived), day toggles `M T W T F S S`, Reminder switch + time (default `21:30`; stored only), `Supports goal` (`None — optional` + active goals), `Gentle mode` (`"Missed days show as recovering, never as failure"`). Validation `"Name the habit"`, `"Pick at least one day"`. Create → `create(draft)` → `"Habit created · <name>"` + `Created(id)`; edit → `update(id, changed fields only)` → `"Habit updated"`. Binary saves `target 1`, `unit ""`; empty target → 1; target clamped to 100000.
- Recomputes on `clock.dayFlow()`.

**Tests:**
- `HabitRowUiTest.measurableShowsPartialAndLogLabel` — count target 8, unit `glasses`, value 5 → `partial == "5/8"`, `checkLabel == "Log +1 glasses"`; done binary → `checkLabel == "Undo"`.
- `HabitsViewModelTest.headerStats` — 3 due, 1 done, rates → `"1 / 3"`, `"57%"`, longest `"12"`.
- `HabitsViewModelTest.todayGroupsOnlyDueHabits` — weekend habit hidden on Wed in Today, shown in Week.
- `HabitsViewModelTest.checkLogsTodayWithUndo` — Check(read) → fake `log:read:2026-10-14:1`, snack `"Read ✓ · streak 4"`; Undo → `log:read:2026-10-14:null`.
- `HabitsViewModelTest.streakMilestoneHaptic` — streak 6 → events include `Haptic(Milestone)`.
- `HabitsViewModelTest.swipeLeftArchivesWithUndo` — `update:<id>` with Archived(true), snack `"Habit archived · history kept"`.
- `HabitEditorViewModelTest.validationOrder` — blank → `"Name the habit"`; all days off → `"Pick at least one day"`.
- `HabitEditorViewModelTest.typeSwitchSetsUnitAndBinarySavesTargetOne` — Duration → unit `"minutes"`; back to Binary → saved `target 1`, `unit ""`.
- `HabitEditorViewModelTest.scheduleLabelFollowsDays` — toggling Sat off from Every day → `"Custom"`; Mon–Fri → `"Weekdays"`.
- `HabitEditorViewModelTest.editSendsOnlyChangedFields` — rename only → `update(id, [Name("Walk")])`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 9: Habit detail + editable history calendar

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/habits/HabitDetailViewModel.kt`, `HabitDetailScreen.kt`, `HabitHistoryViewModel.kt`, `HabitHistoryScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/habits/{HabitDetailViewModelTest,HabitHistoryViewModelTest}.kt`

**Interfaces:**
- Consumes: selectors, `habitComplete` (Task 2), `Ring`, `Heatmap`, `LineChart`, `CalendarMonth`/`calendarCells`, `MitraConfirmDialog`, `Tone` (Task 3), `MonthSwitcher` (Phase 3), `HabitsRepository`, `GoalsRepository`.
- Produces: `class HabitDetailViewModel(id: String, habits, goals, clock)` (Koin `parametersOf(id)`, as `TaskDetailViewModel`); `@Composable fun HabitDetailRoute(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onHistory: (String) -> Unit, onOpenGoal: (String) -> Unit)`; `class HabitHistoryViewModel(id, habits, clock)`; `@Composable fun HabitHistoryRoute(id: String, onBack: () -> Unit)`.

**Requirements (port `HabitDetailScreen` lines 103–135 and `HabitHistoryScreen` lines 138–171):**
- Detail top bar: name, eyebrow `"Habit · yes / no · daily"` (`"3× a week"` form), actions Edit + overflow `View history`, `Archive`/`Unarchive` (`"Habit archived · history kept"` / `"Habit restored"`), `Delete` (confirm `Delete “Read”?` / `"All logs will be removed. Archiving keeps history."` → `"Habit deleted"` + back). Not found → `"This habit doesn't exist"` + `All habits`.
- Ring of `rateDays(h, 30).pct` (tone ≥ 70 Success, ≥ 40 Warning, else Danger); KPIs `Current streak` `"4 days"`, `Best` `"12 days"`, `This month` `"9 / 14"` (done = logs in month ≥ target; due = due days 1..today), measurable `Avg <unit>` (rounded mean of non-zero logs) else `Last 30 days` `"21/30"`. `Done today` / `Done today ✓` button (target or null; `"Read ✓"` / `"Unchecked"`); measurable: digits field + `Log` → `"Logged 5 minutes"`.
- `Consistency · last 12 weeks` heatmap (84 cells: `!due && v == 0 → 0`, `v ≥ target → 4`, `v > 0 → max(1, jsRound(v / target * 3))`), captions `12 weeks ago` / `Filled = done` (`Darker = more minutes`) / `This week`. Measurable: `Trend` `LineChart` of 12 weekly averages (`toFixed(1)`), month labels every 4th, goal line = target, legend `"minutes per session (weekly avg)"`, `"Target 20 minutes"`.
- `Supports goal`: Ring + name + `"Evidence: 21 sessions in 30 days"` → `onOpenGoal`, or `"Not linked to a goal."` + `Link one` (→ edit). `Recent`: 5 days (`fmtDay` relative) with `"Done ✓"` / `"20 minutes ✓"` / `"5 minutes · partial"` / `"Missed"` / `"Rest day"`, and a `Mark done`/`Undo` chip on past days (`setLog(d, target|null)`); `Full history` link.
- History: `History · <name>`; `MonthSwitcher` (`monthTitle`, prev/next unbounded as the web); `CalendarMonth` with hit / miss (past, due, 0) / partial dot / today outline / future disabled; tap a past-or-today day toggles `null` ↔ target; legend; `"Tap any past day to correct it."`. KPIs `Completion` `"80%"` + `"Oct"`, `Best month` `"Sep · 92%"` or `"—"`, `Total` `"41 days"` (`sessions` when measurable), duration → `Total time` `"5h 20m"` else `Best streak`. `Log`: up to 10 days of the month ≤ today, newest first, `"21 Sep"` · value or `"—"` · `Done`/`Partial`/`Missed`/`Rest day`. No CSV export (deviation b).

**Tests:**
- `HabitDetailViewModelTest.kpisAndRingTone` — daily, 21 of 30 → ring `70`, tone Success, `"21/30"`; `This month` on `2026-10-14` with 9 hits → `"9 / 14"`.
- `HabitDetailViewModelTest.heatCellLevels` — target 20: values 0 (due), 5, 20, 0 on a rest day → levels `0, 1, 4, 0`; value 15 → `jsRound(2.25) = 2`.
- `HabitDetailViewModelTest.recentRowsAndPastToggle` — yesterday missed → `"Missed"` + `Mark done` → `log:<id>:2026-10-13:<target>`; today has no chip.
- `HabitDetailViewModelTest.deleteConfirmsThenNavigatesBack`.
- `HabitHistoryViewModelTest.monthStatsAndFutureDaysDisabled` — Oct 2026: tap `2026-10-20` → no call; tap `2026-10-13` → toggles; completion `"%"` counts only due days ≤ today.
- `HabitHistoryViewModelTest.bestMonth` — Sep 92 %, Oct 80 % → `"Sep · 92%"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 10: Goals list (Active / Review due / Completed) + goal form sheet

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/goals/GoalCard.kt`, `GoalsViewModel.kt`, `GoalsScreen.kt`, `GoalEditorViewModel.kt`, `GoalFormSheet.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/goals/{GoalsViewModelTest,GoalEditorViewModelTest}.kt`

**Interfaces:**
- Consumes: `GoalsRepository`, `HabitsRepository`, `TasksRepository` (link counts), selectors/labels (Task 2), `Ring`, `Tone`, `ChipTone.Success` (Task 3), `DatePickers.kt` (Phase 2).
- Produces: `data class GoalCardUi(val id: String, val name: String, val pct: Int, val ringTone: StatusTone, val status: String, val statusTone: StatusTone, val meta: String /* "Numeric · Medium-term · due Mar 2027" */, val next: String?, val links: String, val dimmed: Boolean)`; `fun goalCardUi(g, habits, tasks, projects, today): GoalCardUi`; `@Composable fun GoalCard(ui, onClick)`; `class GoalsViewModel(goals, habits, tasks, clock)`; `@Composable fun GoalsRoute(onOpenGoal: (String) -> Unit, onNewGoal: () -> Unit, onStartReview: () -> Unit)`.
- Produces (shell-hosted): `class GoalEditorViewModel(goals: GoalsRepository, clock: AppClock)`; `sealed interface GoalEditorAction { data object Open; data class Edit(val id: String); data object Dismiss; data class Name(val v: String); data class Type(val t: GoalType); data class Current(val v: String); data class Target(val v: String); data class Unit(val rupees: Boolean); data class Due(val iso: String); data class Term(val t: GoalTerm); data class NextAction(val v: String); data class Review(val r: ReviewEvery); data class Milestones(val text: String); data object Save }`; `@Composable fun GoalFormSheet(vm, onDismiss)`; event `GoalEditorEvent.Created(id)` (shell → goal detail).

**Requirements (port `GoalsScreen`, `GoalCard`, `GoalForm`, `screens/goals.tsx` lines 20–104):**
- Own top bar `Goals` + `＋ New goal`. Segmented `Active` / `Review due` / `Completed` (Completed = paused + completed); summary `"3 active · 1 review due"` + `Start review` link when > 0. Cards: Ring (`cardRingTone`), name, status chip, meta, `→ Next: <nextAction>` when set, links line + `" · review due"`; paused cards dimmed (alpha 0.7). Empty copy per tab (web line 99) with `New goal` on Active only.
- Form (`Edit goal` / `New goal`): `Goal` (`"e.g. Build ₹1,00,000 emergency fund"`), Type segmented + hint per type (web line 35), Current/Target (decimal, numeric & date only), Unit `Number` / `₹ Rupees` (numeric only), Due (default today + 180, date picker), Horizon, Next action (`"The one thing to do next"`), Review, Milestones textarea (create only; default `"25%\n50%\n75%\nDone"`; hint `"Targets and dates are spread evenly; edit them on the goal page."`). Validation `"Give the goal a name"`. Create → `create(GoalDraft…)` (empty/invalid target → 100, current → 0) → `"Goal set · <name>"` + `Created(id)`; edit → changed fields only (milestone type never sends current/target) → `"Goal updated"`.

**Tests:**
- `GoalsViewModelTest.tabsAndSummary` — 2 active (1 review due), 1 paused, 1 completed → Active 2 cards, Review due 1, Completed 2; summary `"2 active · 1 review due"`, `showStartReview`.
- `GoalsViewModelTest.cardMetaAndLinks` — numeric Medium-term due `2027-03-31`, 1 project + 3 tasks + 2 habits linked → meta `"Numeric · Medium-term · due Mar 2027"`, links `"1 project · 3 tasks · 2 habits"`; none → `"Nothing linked yet"`.
- `GoalsViewModelTest.habitGoalUsesLinkedHabits` — habit-type goal with two linked habits → `pct` = average 30-day rate.
- `GoalEditorViewModelTest.createSpreadsMilestones` — default milestones → fake goal has 4 milestones with targets `25,50,75,100`; snack `"Goal set · Run a half marathon"`; `Created` emitted.
- `GoalEditorViewModelTest.milestoneTypeHidesNumbers` — Type(Milestone) → current/target fields hidden; saved goal target = milestone count.
- `GoalEditorViewModelTest.editSendsOnlyChanges` — change Review to Weekly → `update(id, [ReviewEvery(Weekly)])`; snack `"Goal updated"`.
- `GoalEditorViewModelTest.blankNameError` — `"Give the goal a name"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 11: Goal detail — progress, milestones (add / edit / tick / detail sheet), progress chart, linked items, log progress

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/goals/GoalDetailViewModel.kt`, `GoalDetailScreen.kt`, `LogProgressSheet.kt`, `MilestoneSheets.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/goals/{GoalDetailViewModelTest,MilestoneSheetTest}.kt`

**Interfaces:**
- Consumes: `GoalsRepository`, `HabitsRepository`, `TasksRepository` + `toggleCompletion`/`undoCompletion`/`announce` (Task 1), `TaskRow`/`taskRowUi` (Phase 2), selectors/labels (Task 2), `Ring`, `LineChart`, `MitraConfirmDialog`, `Tone` (Task 3).
- Produces: `class GoalDetailViewModel(id: String, goals, habits, tasks, clock)`; `sealed interface GoalDetailAction { data object OpenLog; data class LogProgress(val value: String, val note: String); data class TickMilestone(val id: String); data class OpenMilestone(val id: String?); data class SaveMilestone(val id: String?, val title: String, val target: String, val date: String?, val notes: String); data class MilestoneNotes(val id: String, val notes: String); data class DeleteMilestone(val id: String); data class Formula(val text: String); data object TogglePause; data object Complete; data object Reopen; data object Delete; data class ToggleTask(val id: String); data class UndoTask(val undo: CompletionUndo) }`; `@Composable fun GoalDetailRoute(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onOpenTask: (String) -> Unit, onOpenProject: (String) -> Unit, onOpenHabit: (String) -> Unit, onAddTask: (goalId: String) -> Unit)`.

**Requirements (port `GoalDetailScreen`, `LogProgress`, `MilestoneForm`, `MilestoneDetailScreen`, `screens/goals.tsx` lines 45–177):**
- Top bar: name, eyebrow `"Numeric goal · Medium-term · Review monthly"`, Edit + overflow `Log progress`, `Add milestone`, `Link a task` (→ `onAddTask`), `Pause goal`/`Resume goal` (`"Goal paused · history kept"` / `"Goal resumed"`), `Mark completed` (`"🎉 Goal completed"`), `Delete goal` (confirm `Delete “<name>”?` / `"Linked tasks and habits are kept; only the goal is removed."` → `"Goal deleted"` + back). Banner for non-active (`"Completed — well done."` + `Reopen`; `"Paused — hidden from Today, history kept."` + `Resume`).
- Progress card: KPI per type (`"2 of 4 milestones"`, `"62% habit consistency · 30d"`, `"₹58,000 of ₹1,00,000"`), status chip, thick bar (`barTone`), `"58% · started 14 Oct 2026"` / `"Due 12 Apr 2027 · 5 months left"`, `How this is calculated:` + `formulaText` + `Edit formula` (inline field; `update(Formula(text.ifBlank{null}))` debounced 600 ms like task notes).
- Milestones list: `done` / `now` (first unreached after all reached) / open; sub `"Reached Mon 12 Oct"` or `"Target 14 Jan 2027"` / `"No date"` + `" · ₹17,000 to go"` (numeric with target); tick button (`Mark reached` / `Un-reach`) → `updateMilestone(ReachedOn(today|null))` + `"Milestone reached · <title>"`; empty `"No milestones yet."` + `Add one`; tap → milestone sheet.
- `Progress over time`: `LineChart` (log values when > 1 entry else `[0, current]`; labels every `ceil(n/5)`-th as month else `Start`/`Now`; goal line = target or milestone count) + `"Last logged Today · “bonus”"`.
- `Next action`: the open linked task with the earliest due (no due last) as a `TaskRow` with check (Task 1 helper; snackbar Undo) or `nextAction` / `"Define the next step"` + `"Set in Edit · or link a task"`; `Add task`. `Linked · N`: projects (`"Project · 3 open"`), up to 4 tasks (`"Task · done"` / `"Task · Tomorrow"` / `"Task · no date"`), habits (`"Habit · 70% last 30 days"`); empty copy (web line 143, notes omitted — deviation c). `Blockers` card when `blockers()` non-empty. Primary `Log progress`.
- Log progress sheet: numeric/date → `Add to ₹58,000 (target ₹1,00,000)` decimal field (`₹` suffix when unit ₹), placeholder `8000` / `1`; else info copy (web line 53). Note `"What moved the needle?"`. Save: numeric/date with `n <= 0` → ignored; value = n, or `g.current` for milestone/habit types → `logProgress` → `"Progress logged · +₹8,000"` / `"Progress logged"`.
- Milestone form sheet (`Add milestone` / `Edit milestone`): title (`"e.g. ₹75,000"`), `Reached at value` (numeric goals; hint `"Auto-ticks when progress passes this"`), Target date, Notes → `"Milestone added"` / `"Milestone updated"`. Milestone detail sheet (deviation e): KPI `Remaining` (`"₹17,000"`) or `Status` (`Reached` / `Open`), `Target date`; bar (`pct` rule, Success tone when reached); line `"₹58,000 of ₹75,000 · on pace at ₹8,000/month (≈ 3 months)"` / `"Reached Mon 12 Oct"` / `"Tick it when the milestone is done."`; `Tasks to reach it` (open goal tasks with check) + `Add task`; Notes field (debounced `updateMilestone(Notes)`); footer `Edit` and `Mark reached`/`Mark not reached` (`"Milestone reopened"`); overflow `Delete milestone` (confirm).

**Tests:**
- `GoalDetailViewModelTest.progressKpiPerType` — numeric ₹ `"₹58,000"` of `"₹1,00,000"`; milestone `"2 of 4 milestones"`; habit `"62%"`.
- `GoalDetailViewModelTest.logProgressNumericAddsAndSnacks` — `LogProgress("8000", "")` → fake `progress:<id>:8000.0`, snack `"Progress logged · +₹8,000"`; `"0"` → no call.
- `GoalDetailViewModelTest.logProgressMilestoneTypeSendsCurrent` — milestone goal current 2 → `progress:<id>:2.0`, snack `"Progress logged"`.
- `GoalDetailViewModelTest.tickMilestoneUsesLocalDate` — clock `2026-11-01T00:30+05:30` → `updateMilestone` with `ReachedOn("2026-11-01")`, snack `"Milestone reached · ₹75,000"`.
- `GoalDetailViewModelTest.nextTaskIsEarliestOpenLinked` — linked tasks due 20 Oct, 15 Oct, none → `nextTask.id` = 15 Oct; toggling it → snack `"Completed · …"` with Undo.
- `GoalDetailViewModelTest.blockersCopy` — slipping → `"No progress logged in 3+ weeks — the goal is drifting from its target line."`.
- `GoalDetailViewModelTest.deleteGoalConfirmsAndLeaves`.
- `MilestoneSheetTest.paceLine` — log `(2026-08-15, 42000)` → `(2026-10-14, 58000)`, milestone 75000 → `"₹58,000 of ₹75,000 · on pace at ₹8,000/month (≈ 3 months)"` (pace = 16000 / 60 × 30 = 8000; `ceil(17000 / 8000) = 3`).

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 12: Goal review — swipeable step pager

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/goals/GoalReviewViewModel.kt`, `GoalReviewScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/goals/GoalReviewViewModelTest.kt`

**Interfaces:**
- Consumes: `GoalsRepository`, `HabitsRepository`, selectors (Task 2), `Ring`, `Tone` (Task 3), `HorizontalPager` + `rememberPagerState` (Compose foundation).
- Produces: `class GoalReviewViewModel(goals, habits, clock)`; `sealed interface GoalReviewAction { data class Decide(val goalId: String, val decision: Decision); data class StartAdjust(val goalId: String); data class AdjustTarget(val v: String); data class AdjustDue(val goalId: String, val iso: String); data class SaveAdjust(val goalId: String); data object Finish }`; `enum class Decision(val label: String) { Keep("Keep"), Adjusted("Adjusted"), Pause("Pause") }`; `@Composable fun GoalReviewRoute(onDone: () -> Unit, onBack: () -> Unit)`.

**Requirements (port `GoalReviewScreen`, `screens/goals.tsx` lines 180–198; spec §9 "goal review → swipeable step pager"):**
- The goal list is the **active goals captured when the screen opens** (pausing during the review must not remove a page). Pages: (0) `Trajectory` — `on track` / `behind` (attention + behind) / `slipping` counts in success/warning/danger + `"Compared with each goal's own target line since it started."`; (1..n) one goal per page — Ring (`ringTone`), name, decision chip, `"<delta> · <status lowercase>"`, buttons `Keep` / `Adjust target` / `Pause`; Adjust shows target field (placeholder current target, unit suffix or `target`) + due date picker + `Save`; (n+1) `Finish review` page listing decisions. Page indicator dots; top bar `Goal review`, sub `"Monthly · Wed 14 Oct · 3 goals"` (web uses the first goal's `reviewEvery`). A decision advances to the next page.
- Effects as the web: `Pause` → `update(State(Paused))` immediately; Adjust → `AdjustDue` sends `Due` immediately, `SaveAdjust` sends `Target` when entered, decision `Adjusted`; `Finish` → for each captured goal **still active** `update(ReviewOn(addDays(today, reviewEvery.days)))` → snackbar `"Review done · 2 decisions recorded"` (`"1 decision"`) → `onDone` (Goals). No activity row (deviation c). Empty → `"No active goals to review"` + Finish still available.
- `delta(g)` exact port (line 184): numeric with ≥ 1 log in the last 31 days → `"+12 pts this month"` (`before` = last log older than 31 days, else first recent log); otherwise `"62% · last logged Today"` / `"62% · nothing logged yet"`.

**Tests:**
- `GoalReviewViewModelTest.trajectoryCounts` — on-track, attention, behind, slipping → `1 / 2 / 1`.
- `GoalReviewViewModelTest.deltaNumericAndFallback` — target 100000, logs 40 days ago (40000) and 5 days ago (52000), current 52000 → `"+12 pts this month"`; milestone goal never logged → `"25% · nothing logged yet"`.
- `GoalReviewViewModelTest.pauseKeepsPageAndSkipsReviewDate` — Pause goal B → `update:B` State(Paused); B still a page; Finish → `ReviewOn` sent for A and C only (`"2026-11-13"` for Monthly, `"2026-10-21"` for Weekly).
- `GoalReviewViewModelTest.adjustSendsTargetAndDue` — StartAdjust, AdjustTarget("120000"), AdjustDue(…), SaveAdjust → changes `Due`, then `Target(120000.0)`; decision `Adjusted`.
- `GoalReviewViewModelTest.finishSnackCountsDecisions` — two decisions → `"Review done · 2 decisions recorded"`; one → `"Review done · 1 decision recorded"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 13: Today — Habits stat, Habits and Goals cards, section order; Quick Add "Habit check"

**Files:**
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/today/TodayViewModel.kt`, `TodayScreen.kt`
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/quickadd/QuickAddViewModel.kt`, `QuickAddSheet.kt`
- Modify: `…/feature/di/FeatureModule.kt` (only the `TodayViewModel` and `QuickAddViewModel` lines)
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/today/TodayViewModelTest.kt`, `…/feature/quickadd/QuickAddViewModelTest.kt` (extend; constructors gain the fakes)

**Interfaces:**
- Consumes: `HabitsRepository`, `GoalsRepository`, selectors, `habitTap` (Task 2), `HabitRow`/`habitRowUi` (Task 8), `Ring`, `Tone` (Task 3), `HapticKind.Milestone` (Task 1).
- Produces: `TodayViewModel(auth, tasks, money, habits: HabitsRepository, goals: GoalsRepository, clock)`; `StatTarget.Habits`; `TodayUiState` + `habits: List<HabitRowUi>?` (null = section hidden), `goals: List<TodayGoalUi>?`, `sections: List<String>` (render order); `data class TodayGoalUi(val id: String, val name: String, val pct: Int, val tone: StatusTone, val next: String /* "Next: Transfer ₹8,000" | "Next: —" */, val status: String, val statusTone: StatusTone)`; `TodayAction` + `CheckHabit(id)`, `SetHabitLog(id, date, value)` (Undo), `OpenHabit(id)`, `OpenGoal(id)`, `AddHabit`, `OpenHabits`, `OpenGoals` (navigation events for the shell — Task 14).
- Produces: `QuickAddViewModel(tasks, money, habits: HabitsRepository, clock)`; `QuickType.Habit.arrivesWith = null`; `QuickAddUiState.habitRows: List<HabitRowUi>` (due today, filtered by the typed text, case-insensitive); `QuickAddAction` + `CheckHabit(id)`, `UndoHabit(id, date, value)`, `NewHabit` (event → shell opens the habit form with `Open(name = text)`).

**Requirements:**
- Stat `Habits` (`today.tsx` line 73): value `"1/3"` (done/due today), sub `"57% this week"`, target `StatTarget.Habits` — replaces the Phase 1 `"—"` / `"Due today"` placeholder.
- Habits section (`TodayHabits`, line 24): title `Habits`, action `All` (→ `OpenHabits`); due-today rows with check (same `habitTap` effects/snackbars/haptics as Task 8, date = `clock.today()` at tap time); empty `"No habits today"` + `Add a habit` (→ `AddHabit`).
- Goals section (`TodayGoals`, line 54): title `Goals`, action `All`; active goals sorted not-on-track first (stable), top 2; row Ring (`ringTone`), name, `"Next: <nextAction or —>"`, status chip; tap → `OpenGoal`; empty `"No active goals"` + `Set a goal` (→ `OpenGoals`).
- Sections render in `settings.todaySections` order (`today.tsx` line 66), skipping ids not built yet (`activity`, `checkin`) and ones turned off; `money` keeps its module rule.
- Quick Add with `Habit check`: field placeholder `"Search today's habits"`; rows = due-today habits matching the text, each with the check button (`habitTap` → `setLog`; snackbar + Undo through the shell like the expense Undo); `New habit` row (`"New habit “<text>”"` when text is non-blank) → `NewHabit`; the primary Save button is hidden for this type. `"No habits due today"` when none match and text is blank. Note / Mood keep the `"… arrives with …"` notice.
- Everything recomputes on `clock.dayFlow()`.

**Tests:**
- `TodayViewModelTest.habitsStatCountsDueAndDone` — 3 due, 1 done → stat `"1/3"`, `"57% this week"`, target `Habits`.
- `TodayViewModelTest.habitCheckUsesLocalDateAfterMidnight` — clock `2026-11-01T00:30+05:30` → `CheckHabit(read)` → `log:read:2026-11-01:1`.
- `TodayViewModelTest.habitsStatRollsOverAtMidnight` — done yesterday at `23:59` (`"1/1"`) → after `00:00:30` → `"0/1"`.
- `TodayViewModelTest.goalsCardShowsNotOnTrackFirst` — three active goals (on-track, behind, slipping) → first two rows are behind and slipping (original order kept), next `"Next: —"` when blank.
- `TodayViewModelTest.sectionsFollowTodaySectionsOrder` — `["goals","priorities","habits"]` → `sections == ["goals","priorities","habits"]`; `"habits"` absent → `habits == null`.
- `QuickAddViewModelTest.habitCheckListsDueHabitsFilteredByText` — Type(Habit), Text("rea") → rows `["Read"]`; notice `null`.
- `QuickAddViewModelTest.checkFromQuickAddLogsTodayAndSnacks` — `CheckHabit(read)` → `log:read:2026-10-14:1`, snack `"Read ✓ · streak 4"` with `UndoHabit`.
- `QuickAddViewModelTest.newHabitCarriesTypedName` — Text("Stretch") + `NewHabit` → event with name `"Stretch"`.
- `QuickAddViewModelTest.otherTypesStillArriveLater` — update: Type(Note) → `"Note arrives with Notes — add it on the web for now."`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 14: App shell — Habits/Goals routes, habit & goal form hosts, navigation wiring, Koin (data + feature)

**Files:**
- Modify: `shared/app/src/commonMain/kotlin/tech/beyondscale/mitra/app/Destinations.kt`, `MainShell.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/di/DataModule.kt`
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/di/FeatureModule.kt` (new VMs)
- Test: `shared/app/src/commonTest/kotlin/tech/beyondscale/mitra/app/ChromeTest.kt`, `AppModulesTest.kt` (extend)

**Interfaces:**
- Consumes: every Route/VM/sheet from Tasks 7–13; `HabitsRepositoryImpl`, `GoalsRepositoryImpl` (Tasks 5–6).
- Produces: `@Serializable data class HabitDetailDest(val id: String)`, `data class HabitHistoryDest(val id: String)`, `data class GoalDetailDest(val id: String)`, `data object GoalReviewDest`.

**Requirements:**
- `DataModule.kt`: `single<HabitsRepository>(createdAtStart = true) { HabitsRepositoryImpl(get(), get(), get(), get(), get(), get(), get<AppScope>().scope) }`, same for `GoalsRepository` (eager: they register sync handlers before the first drain); `ModuleRefresherImpl` modules map gains `"habits" to { habits.refresh() }` and `"goals" to { goals.refresh() }`.
- `NON_TAB_ROUTES` + `chromeFor`: `HabitsDest`, `GoalsDest` → `Chrome.TabOwnBar` (screens draw their own bar with ＋); `HabitDetailDest`, `HabitHistoryDest`, `GoalDetailDest`, `GoalReviewDest` → `Chrome.Detail`. Replace both `ComingSoonScreen`s.
- Shell-hosted sheets (like `TaskFormSheet` / `TxFormSheet`, events hoisted to the shell `SnackbarController`): `HabitEditorViewModel` keyed `shellVmKey("habit-editor", user.id)` → `HabitFormSheet`; `GoalEditorViewModel` keyed `shellVmKey("goal-editor", user.id)` → `GoalFormSheet`. Openers: Habits ＋, Today `AddHabit`, Quick Add `NewHabit(name)` → `Open(name)`; habit detail Edit / `Link one` → `Edit(id)`; Goals ＋ → `Open`; goal detail Edit → `Edit(id)`. `HabitEditorEvent.Created` → select/navigate to `HabitsDest`; `GoalEditorEvent.Created(id)` → `GoalDetailDest(id)`.
- Navigation: Habits rows → `HabitDetailDest`; detail `onHistory` → `HabitHistoryDest`; `onOpenGoal` (habit detail, task detail, project detail, Today) → `GoalDetailDest`; goal detail linked task → `TaskDetailDest`, project → `ProjectDetailDest`, habit → `HabitDetailDest`; `onAddTask(goalId)` → shell task form `Open(TaskDraft("", goalId = goalId))`; Goals `Start review` → `GoalReviewDest`, review done → back to `GoalsDest`; Today `StatTarget.Habits`/`OpenHabits` → `HabitsDest` (tab if pinned, else push), `OpenGoals` → `GoalsDest`.
- Deleting the goal/habit shown in a detail route pops back (no crash on a missing id — screens already show "not found").
- `FeatureModule.kt`: `HabitsViewModel`, `HabitEditorViewModel`, `HabitDetailViewModel` (`parametersOf(id)`), `HabitHistoryViewModel`, `GoalsViewModel`, `GoalEditorViewModel`, `GoalDetailViewModel`, `GoalReviewViewModel`.
- No new launch URIs and no widget changes in this phase (shortcuts/notifications are Phase 6).

**Tests:**
- `ChromeTest.habitsGoalsRoutes` — `chromeFor(HabitsDest::class) == TabOwnBar`, `GoalsDest` → `TabOwnBar`; `HabitDetailDest`, `HabitHistoryDest`, `GoalDetailDest`, `GoalReviewDest` → `Detail`.
- `AppModulesTest` — the Koin graph (real `dataModule` on the test driver) resolves `HabitsRepository`, `GoalsRepository`, every new ViewModel (with `parametersOf("x")` where needed) and the changed Phase 2/3 VMs.

**Verify:** `cd mitra-mobile && ./gradlew :shared:app:testAndroidHostTest :androidApp:assembleDebug`

---

### Task 15: Full build verification + README

**Files:**
- Modify: `mitra-mobile/README.md`

**Requirements:**
- Run `cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile" && ./gradlew testAndroidHostTest :androidApp:assembleDebug` — both must pass with zero test failures. If anything fails, use superpowers:systematic-debugging before changing code.
- README: Layout gains `shared/domain/{habits,goals}`, `shared/feature/{habits,goals}`; Habits/Goals notes (goal numbers are recomputed locally with the server's rules; habit logs older than 400 days are only on the device that wrote them; reminders are stored but fire from Phase 6); update the test count line with the real number from the run.
- Commit: `git add mitra-mobile/README.md && git commit -m "docs(mobile): Phase 4 habits and goals notes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

---

## Backend gaps (not changed — need the user's approval)

1. **`POST goals/:id/progress` is not idempotent.** It has no client id and numeric goals *add* the value, so a resend after a lost response (or the third retried `500 INTERNAL`) double-counts progress and appends a second log entry. Android already sends a client `id` (stripped by Zod today). Suggested fix, same shape as the approved `recurring/:id/pay` fix: accept optional `id` in `LogProgressSchema`, create `GoalProgress` with it inside the transaction, and return the goal unchanged when that id already exists.
2. **Server UTC "today"** in `habits.service.ts` `log` (activity row `"<habit> ✓"` only when `date === todayISO()`) and `goals.service.ts` defaults (`logProgress` date, `create` `startedOn`). Android always sends dates, so stored data is correct; only the habit activity row is skipped/misdated for check-offs between 00:00 and 05:30 IST (visible in Activity, Phase 5).
3. **`GET habits` returns only the last 400 days of logs** — a fresh install cannot show older history (web has the same limit). Android keeps older logs it already has.
4. **Deleting a goal leaves `goalId` on tasks, habits and projects** (no FK / cleanup) — dangling links. Android mirrors the server (no local scrub; unknown goal = no goal).
5. **Duplicate-id creates return `500 INTERNAL`** (`POST habits`, `POST goals`, `POST goals/:id/milestones` resent after a lost response) instead of an idempotent success; the outbox self-heals after three 500s + refresh, but the retries are slow. Same class of issue as gap 1, lower impact.

## Deferred device checklist (Phase 4 — run on a device with the Phase 2 and 3 lists)

Run against the local backend (`curl -s http://localhost:4000/api/health`, `./gradlew :androidApp:installDebug`), installing **over the Phase 3 build**:

27. Upgrade in place: opens into Today signed in; tasks and money still there; Habits and Goals fill in after the first refresh (schema v4 migration).
28. Habits list: Today/Week groups, done counts, % this week, longest streak and week dots equal the web for the same data; tap check on binary / count / duration habits behaves like the web (partial `5/8`, target reached).
29. Swipe right completes a habit with Undo; swipe left archives with Undo; a 7-day streak check gives the stronger haptic.
30. Habit form: create each type, Weekdays / 3× a week / custom days, reminder time, link a goal, gentle mode; edit and archive/unarchive; delete from the detail menu.
31. Habit detail ring/KPIs/heatmap/trend and Recent "Mark done" on a past day match the web; history calendar taps correct past days, future days are inert, month switcher works, KPIs match the web.
32. Airplane mode: check, uncheck, re-check today, log yesterday, rename a habit, create another and check it, delete one → airplane off → the web shows exactly the final state; no log flickers back during the drain.
33. At 00:00 IST the Today Habits stat, Today Habits card and the Habits list roll to the new day without restarting; a check-off at 00:30 IST appears on the web under the new date.
34. Goals list tabs, cards (ring, status chip, meta, links line, review due) and "Start review" equal the web.
35. Goal create (numeric ₹ with default milestones, milestone-type, habit-supported, date-based) → numbers and milestone targets/dates match the web after sync.
36. Goal detail: log progress (₹ and number), auto-ticked milestones and auto-completion at target match the web without a jump when the echo arrives; tick/un-tick milestones on a milestone-type goal moves the chart; Edit formula; pause/resume/complete/reopen; delete.
37. Milestone sheet: remaining, pace line, tasks to reach it (complete one → Undo), notes autosave; add/edit/delete milestone.
38. Linked items: task form "Links to goal" and project form "Contributes to goal" show on goal detail; goal chips on task/project detail open the goal; "Add task" from a goal pre-links it.
39. Airplane mode on a goal: add milestone, tick it, log progress twice, delete a milestone → online → web identical; progress counted exactly twice (watch for the Backend gap 1 double count only if a request timed out).
40. Goal review pager: swipe between goals, Keep / Adjust target (target + due) / Pause, Finish sets the next review dates and the "Review due" tab empties; TalkBack reads each page.
41. Today Goals card (behind/slipping first) and Habits card; section order follows Settings → Today sections.
42. Quick Add → Habit check: due habits listed and filterable, check with Undo, "New habit “…”" opens the form prefilled.
43. Large font scale: habit rows, calendar cells, review pager buttons and goal cards don't clip; touch targets stay ≥ 48 dp.
