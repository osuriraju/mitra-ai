# Mitra AI — Android App (KMP + Compose Multiplatform) — Design Spec

*13 Oct 2026 · status: draft for review*

## 1. Goal

Ship a native Android app for Mitra AI that reaches feature parity with `mitra-frontend` (Today · Tasks · Goals · Habits · Money · Wellness · Notes · AI), talks to the existing `mitra-backend` with the same accounts, and feels smoother than the PWA on a phone: instant screens, offline use, one-handed capture, gestures, haptics and home-screen widgets for Tasks and Money.

The code must follow Clean Architecture + MVVM and be structured so an iOS app can be added later with minimal rework.

**Success criteria**
- A user can sign in with their existing web account and see the same data.
- Every screen opens from local data without a spinner after the first sync; the app is fully usable offline and syncs when back online.
- Numbers (safe-to-spend, goal progress, streaks, budgets) match the web app for the same data.
- Tasks and Money widgets work on the home screen, including completing a task from the widget.
- Each delivery phase produces an installable debug APK.

**Decisions taken (from brainstorming)**
| Topic | Decision |
|---|---|
| Stack | Kotlin Multiplatform + Compose Multiplatform; Android target now, iOS later |
| Architecture | Clean Architecture (feature → domain ← data) + MVVM (ViewModel + `StateFlow<UiState>`) |
| Scope | Full parity, delivered in 6 phases |
| Offline | Offline-first: local DB is the source of truth, writes go through an outbox, last-write-wins |
| Widgets | Tasks and Money (Jetpack Glance, Android-only) |
| Backend | No changes required |

## 2. Non-goals

- No iOS target in this project (only keep the code iOS-ready: no Android imports in `shared/`).
- No backend changes, no server push (FCM) in v1 — reminders are local notifications.
- Not porting the `/design-system` and `/states` dev pages, nor the desktop-only ⌘K palette (replaced by a search button).
- No real-time multi-device conflict resolution beyond last-write-wins + server refresh.
- No Play Store listing in v1 (release signing is set up; publishing is a later decision).

## 3. Repository layout

New Gradle project at `mitra-mobile/` in the monorepo (independent of pnpm).

```
mitra-mobile/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  androidApp/                       Android application module
    MainActivity, MitraApplication (Koin start), AndroidManifest
    widget/   TasksWidget, MoneyWidget, receivers, actions (Glance)
    notify/   local notification scheduling (AlarmManager/WorkManager)
    sync/     SyncWorker (WorkManager) wrapping shared SyncEngine
    platform/ encrypted cookie storage, biometric lock, app shortcuts, share target
  shared/
    core/          AppError, Result helpers, dispatchers, clock, INR + date formatting, logger
    domain/        entities, repository interfaces, use cases (incl. ported web selectors)
    data/          Ktor API + DTOs + mappers, SQLDelight schema + queries, repositories, SyncEngine, outbox
    designsystem/  MitraTheme (Noir tokens), typography, shapes, spacing, shared components
    feature/
      auth/ today/ tasks/ habits/ goals/ money/ wellness/ notes/ ai/ settings/ quickadd/
      each: screens (Composables), ViewModels, UiState/UiEvent, navigation entry
    app/           root composable: navigation graph, bottom bar, More sheet, session gate, Koin modules
```

`shared/*` modules use only `commonMain` code except where a platform `expect/actual` is needed (DB driver, HTTP engine, secure storage, haptics). Gradle modules can start coarse (`shared/core`, `shared/domain`, `shared/data`, `shared/designsystem`, `shared/feature`, `shared/app`) and split features into separate modules later if build times demand it.

**Dependency rules**
- `domain` depends only on `core`. No Ktor, SQLDelight, Compose or Android imports.
- `data` implements `domain` repository interfaces.
- `feature` depends on `domain` + `designsystem`; never on `data` directly (wired via Koin).
- `androidApp` depends on `shared/app` and uses `domain` use cases for widgets/notifications.

## 4. Technology

| Concern | Choice |
|---|---|
| Language / build | Kotlin (latest stable 2.x), Gradle version catalog, AGP; compileSdk/targetSdk 36, minSdk 26 |
| UI | Compose Multiplatform (Material 3 primitives, skinned with Noir tokens) |
| Navigation | JetBrains Navigation Compose (multiplatform), type-safe `@Serializable` routes |
| ViewModel | `androidx.lifecycle:lifecycle-viewmodel` multiplatform |
| DI | Koin |
| Networking | Ktor client (OkHttp engine on Android, Darwin later), ContentNegotiation + kotlinx.serialization, HttpCookies |
| Local DB | SQLDelight (Android driver; native driver later) with coroutine `Flow` extensions |
| Dates | kotlinx-datetime |
| Preferences | DataStore (multiplatform) for theme, pinned tabs, widget privacy, last-sync time |
| Widgets | Jetpack Glance (`androidApp` only) |
| Background | WorkManager (sync, reminders refresh) |
| Secure storage | Android Keystore–backed encryption for the session cookie |
| Tests | kotlin.test in `commonTest`, Turbine for Flows, Ktor `MockEngine`, SQLDelight in-memory driver, Compose UI tests (Android) |

Exact versions are pinned in `libs.versions.toml` at scaffold time (latest stable).

## 5. Backend integration

**Base URL** per build type: `debug` → `http://10.0.2.2:4000/api` (emulator → host machine; configurable via `local.properties` for a physical device), `release` → production API URL from Gradle property. Cleartext HTTP is allowed only in debug via a network security config.

**Auth.** The backend uses a signed httpOnly cookie (`mitra_sid`, `sameSite=lax`, `secure` in prod). A native client is not bound by browser cookie rules, so Ktor's `HttpCookies` plugin with a custom persistent `CookiesStorage` (encrypted at rest) carries the session. `remember=true` is always sent on mobile login. Endpoints used: `POST /auth/login|signup|logout|forgot-password|reset-password`, `GET /auth/me`, `PATCH /users/me`, `PATCH /users/me/settings`, `POST /users/me/onboarded`, `PATCH /users/me/password`, `GET /users/me/export`.

**Data endpoints** (existing): `tasks/bootstrap`, `tasks`, `projects`; `habits` (+ `PUT :id/logs`); `goals` (+ progress, milestones); `money/bootstrap` + accounts/categories/transactions/recurring (+ `pay`)/subscriptions; `wellness/bootstrap`, `entries/:date`, `settings`; `notes`; `activity`; `ai/status|ask|suggestions|journal-draft|actions|actions/:id/revert`.

DTOs mirror the server JSON exactly (taken from the backend mappers and `mitra-frontend/src/store/types.ts`) and are mapped to domain entities in `data`. Money stays integer paise (`Long`) end to end; formatting to lakh/crore happens in `core`.

**Error envelope.** The server returns `{ error: { code, message, issues? } }`. Mapped to:
```kotlin
sealed interface AppError {
  data object Offline; data object Unauthenticated
  data class Validation(val issues: List<FieldIssue>, val message: String)
  data class Server(val status: Int, val code: String, val message: String)
  data class Unknown(val cause: Throwable)
}
```

## 6. Data layer and offline-first sync

**Source of truth:** SQLDelight tables per entity (tasks, projects, habits, habit_logs, goals, milestones, accounts, categories, transactions, recurring, subscriptions, wellness_entries, wellness_settings, notes, activity, ai_messages, ai_actions, suggestions, settings, user). UI observes `Flow`s from repositories.

**Reads / refresh.** Each module has a `refresh()` calling its bootstrap/list endpoint and upserting rows in one DB transaction (and deleting rows absent from the server unless they have a pending outbox entry). Triggered on: app start after auth, app foreground (throttled to once per 60 s per module), pull-to-refresh, and after the outbox drains.

**Writes.** Use case → repository → (1) write to local DB immediately, (2) append an `outbox` row `{id, entity, entityId, op (create|update|delete|action), payloadJson, createdAt, attempts, lastError}` in the same transaction. IDs are client-generated UUIDs (as the web store does), so creates need no ID remapping. Updates to the same entity coalesce in the outbox (later patch merged into the pending one) to keep the queue short — mirrors the web's debounced note autosave.

**SyncEngine** (`shared/data`): drains the outbox FIFO, one request at a time.
- 2xx → delete outbox row, upsert the server response if returned.
- Network error / 5xx → stop, retry later with exponential backoff (WorkManager constraint: network connected).
- 401 → stop, emit `SessionExpired`; outbox is kept and resumed after re-login (same user only — a different user signing in clears the DB and outbox).
- Other 4xx → drop the row, refresh that module from the server (server wins), emit a user-visible message ("Couldn't save 'X': <server message>").

**Triggers:** after every local write (debounced 500 ms), on connectivity regained, on app start, and a periodic WorkManager job (15 min) while items are pending.

**Sync status** is exposed as `StateFlow<SyncStatus>` (`Idle | Syncing | Offline(pending:n) | Error`) and shown as a small pill in the top bar when not idle.

**Logout** clears the DB, outbox, cookie and widget state.

## 7. Domain layer

Entities and repository interfaces per module, plus use cases. The derived logic in `mitra-frontend/src/store/selectors.ts` and `src/lib/dates.ts` is ported 1:1 into domain functions with unit tests using the same fixtures, including: `openToday`, `doneToday`, `isOverdue`, `priorityTasks`, `plannedMin`/`doneMin`, `habitsDueToday`, `habitsDoneToday`, `habitsWeekRate`, streaks, `goalProgress`, `goalStatus`, `spent`, `spentByCategory`, `spentToday`, `thisMonth`, `totalBudget`, safe-to-spend, next bill/renewal, `sleepMinutes`, BMI, and INR `inr`/`inrShort` formatting.

## 8. Presentation (MVVM)

Each screen = `@Composable Screen(state, onAction)` (stateless, previewable) + `ViewModel` exposing `StateFlow<UiState>` and a `Flow<UiEvent>` for one-off effects (snackbar, navigate, haptic). User intents are a sealed `Action` handled by `onAction`. ViewModels only call use cases.

**Design system** (`shared/designsystem`): Noir tokens from `mitra-frontend/src/app/globals.css` → `MitraColors` (dark default + light), spacing (`gap 14`, `pad 16`), radii (`10/7/14`), typography (Manrope for text, JetBrains Mono–style tabular numbers for amounts), accent override. Components: Card, ListRow, Chip, Kpi, Ring, ProgressBar, StatStrip, MoodRow, EmptyState, BottomSheet, SwipeRow, AmountKeypad, DateChips, Snackbar host, SyncPill. Respects system font scale and supports edge-to-edge.

## 9. Mobile UX

**Navigation shell.** Bottom bar: 4 tabs + center ＋ — default `Today · Tasks · ＋ · Money · More`. “More” opens a sheet with Habits, Goals, Wellness, Notes, AI, Settings, Profile. Users can choose which modules occupy the 4 tab slots (respecting `settings.modules`). Each tab keeps its own back stack and scroll position. Predictive back; shared-element transition list row → detail. Top bar: title, search button, sync pill, avatar/notifications.

**Quick Add sheet** (center ＋, widget buttons, app shortcuts): one sheet with type chips Task · Expense · Habit check · Note · Mood, opened with the keyboard focused. Lightweight parsing:
- Task: `tomorrow`, `today`, weekday names, `p1`–`p3`, `#project` → due / priority / project.
- Expense: leading/trailing amount (`₹240`, `240`) + a word matching a category name → amount / category; defaults to the default account.
Parsed fields show as editable chips before saving.

**Gestures & feedback.** Swipe right = complete (task/habit); swipe left = reschedule (task) / delete (others) with Undo snackbar; long-press = multi-select (bulk complete/move/delete); pull-to-refresh on every list; haptics on complete, streak milestone, destructive confirm.

**Screen adaptations.**
- Today: single column; stat strip → horizontal scroll cards; sections collapsible and drag-reorderable (persisted to `settings.todaySections`).
- Forms → bottom sheets; native date/time pickers with quick chips (Today / Tomorrow / Next week / Someday); amount entry with numeric keypad and live ₹ formatting.
- Daily review and goal review → swipeable step pager.
- Money: month switcher; charts support press-and-scrub to read values.
- Notes: full-screen editor, debounced autosave via outbox coalescing, formatting toolbar above the keyboard; journal by date.
- AI: chat with progressive text reveal, inline action cards that require preview → confirm, link to the audit log with revert.
- Search (replaces ⌘K): global search across tasks, notes, goals, habits, transactions over local DB.

**System integration.** Local notifications (task reminders with due time, habit nudges, bills/subscriptions due in 1 day) scheduled from local data and rescheduled after each sync; notification actions “Complete” / “Snooze”. App shortcuts: New task, Add expense, Check-in, Ask Mitra. Share target: shared text/URL → Quick Add (Note or Task). Optional biometric app lock (on resume after 5 min in background). Light/dark/auto theme as on the web.

## 10. Widgets (Jetpack Glance)

Both widgets read through domain use cases on the local DB (offline-capable). They update when the relevant repository data changes (a small `WidgetUpdater` observes the flows and calls `updateAll`, debounced), after each sync, and at local midnight (date rollover). No independent polling.

### Tasks widget — “Today”
- **Medium/Large (4×2, 4×4, resizable):** header “Today · 3/7 done” + thin progress bar + ＋ button (opens Quick Add → Task). List: overdue first (danger dot), then due today ordered by priority then time. Each row has a checkbox that completes the task in place (Glance `ActionCallback` → `ToggleTask` use case → local DB + outbox → widget refresh); tapping the title deep-links to task detail. Shows as many rows as fit; a “+N more” footer opens Tasks.
- **Small (2×2):** “4 left”, next task title, ＋ button.
- **Empty:** “All clear for today ✓”. **Signed out:** “Sign in to Mitra” → opens app.

### Money widget — “Safe to spend”
- **Medium (4×2):** hero “₹1,240 / day” (same formula as web Today: `(totalBudget − spentThisMonth) / daysLeftIncludingToday`, floored at 0); line “₹18,400 of ₹30,000 · 61%” with budget bar (accent < 90 %, warning ≥ 90 %, danger > 100 %); next bill/renewal “🏠 Rent · in 3 days · ₹15,000”; buttons ＋ Expense (Quick Add → Expense, keypad focused) and body tap → Money.
- **Small (2×2):** safe-to-spend + ＋ Expense.
- **No budget set:** shows “Spent this month ₹18,400” and a “Set budget” tap target.
- **Privacy:** per-widget “Hide amounts” option (configuration activity) renders ₹•••• until the app is opened.

Both follow the app theme (dark/light) and use the Noir accent.

## 11. Error handling (summary)

- API errors → `AppError` (section 5). ViewModels map them to snackbar text or inline field errors (`Validation.issues` keyed by path).
- Writes never fail in the UI while offline; they are pending in the outbox (sync pill shows count).
- Rejected outbox writes → server state restored + snackbar (section 6).
- 401 anywhere → session cleared, navigate to Login, outbox preserved for same-user re-login.
- Unexpected exceptions in ViewModels are caught at the use-case boundary and logged; the screen shows an error state with Retry rather than crashing.

## 12. Testing

- **commonTest:** all ported selectors/use cases (fixtures copied from web seed data where applicable), INR/date formatting, Quick Add parser, DTO ↔ entity mappers, SyncEngine (MockEngine + in-memory SQLDelight: success, offline retry, 401, 4xx drop, coalescing), repositories.
- **ViewModels:** state transitions with Turbine and fake repositories.
- **Android instrumented / Compose UI tests:** login flow, Today renders from cache offline, task swipe-complete + undo, add expense sheet, widget action callback completes a task.
- **Manual per phase:** install debug APK on emulator against local backend (`pnpm --filter mitra-backend dev`).

## 13. Delivery phases

Each phase ends with a working debug APK and passing tests.

1. **Foundation** — Gradle/KMP scaffold, design system, Ktor + cookie storage, SQLDelight + outbox + SyncEngine, auth screens (login, signup, forgot/reset), session gate, app shell (bottom bar, More sheet, top bar, sync pill), Today (stat strip + sections wired as their modules land), settings: theme + logout.
2. **Tasks** — inbox / today / upcoming, projects + project detail, task detail, focus timer, Quick Add sheet (all types scaffolded, Task fully working), swipe gestures, **Tasks widget**.
3. **Money** — dashboard, transactions (filter/search), add/edit expense, budgets, categories, recurring (+ mark paid), subscriptions, Quick Add Expense, **Money widget**.
4. **Habits + Goals** — habit list/detail/history/edit, check-offs; goals list/detail, milestones, progress, goal review.
5. **Wellness + Notes + AI** — check-in, trends, sleep, wellness settings; notes list/folders/search/editor, journal; AI chat, suggestions, action log + revert.
6. **Polish & release** — local notifications, app shortcuts, share target, biometric lock, profile/settings/export/delete account, onboarding, notifications screen, global search, release signing config, performance pass (baseline profile, startup).

## 14. Open risks

- **Cookie `secure` flag in prod** requires HTTPS for the production API (expected). Debug uses HTTP against `10.0.2.2`.
- **Server-side derived data** (e.g., activity rows, goal progress after task completion) only appears after sync; the client computes the same values locally where the web does, so the UI is consistent in the meantime.
- **Last-write-wins** can overwrite a concurrent web edit to the same entity; acceptable for a single-user personal app.
- **Compose Multiplatform iOS** maturity is good but iOS-specific polish (navigation gestures, keyboard) will need work when the iOS target is added.
