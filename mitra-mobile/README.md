# Mitra Mobile (Android, KMP + Compose Multiplatform)

Native Android app for Mitra AI. Shared Kotlin code in `shared/*` is structured for a future iOS app.

## Layout
- `shared/core` – Outcome/AppError, clock, date + INR formatting (matches the web)
- `shared/domain` – models, repository interfaces, use cases (no frameworks)
  - `shared/domain/money` – money selectors (safe to spend, budgets, analytics), Today money card, widget model, expense parser
  - `shared/domain/habits`, `shared/domain/goals` – habit selectors (streaks, due today, week stats, check-off rules), GoalMath (progress, status, milestone auto-ticks — the server's rules)
- `shared/data` – Ktor API client, SQLDelight DB, offline outbox + SyncEngine, repositories
- `shared/designsystem` – Noir theme, fonts, icons (generated from the web), components
- `shared/feature` – screens + ViewModels; `shared/app` – navigation, shell, DI
  - `shared/feature/tasks` – Tasks screen (Inbox / Today / Upcoming / Projects), task form + detail, projects, focus timer, multi-select
  - `shared/feature/money` – Money dashboard, transactions + transaction sheet, budgets, categories, recurring bills, subscriptions
  - `shared/feature/habits` – Habits list (Today / Week), habit detail, history calendar, habit form
  - `shared/feature/goals` – Goals list (Active / Review due / Completed), goal detail, milestones, log progress, goal form, goal review pager
  - `quickadd` – Quick Add sheet and natural-language parser
- `androidApp` – Android entry point, Keystore storage, WorkManager sync
  - `androidApp/widget` – "Mitra · Today" and "Mitra · Money" home-screen widgets (Glance) + Money widget configuration activity

## Phase 2 – Tasks
Offline-first tasks and projects (outbox sync with hardening), module refresher + periodic sync + refresh on foreground, Tasks screen with swipe (complete / move to tomorrow) and undo, filters and pull-to-refresh, task form/detail with Subtask-of picker and notes autosave, projects, focus timer, Quick Add, real data on Today, long-press multi-select, and the Tasks home-screen widget.

### Quick Add syntax
Type a title plus any of: `today`, `tomorrow`, a weekday name (`friday`), `p1`–`p3` (priority), `#project`. Example: `Call John tomorrow p1 #work`.

## Phase 3 – Money
Offline-first money (same outbox sync as tasks): Money dashboard (spent / income / saved, budget progress, projected month end, analytics with press-and-drag daily bars, month switcher, account filter), transactions with swipe-delete + Undo, a transaction sheet with an amount keypad and live ₹ formatting (expense / income / transfer, Save & add another), budgets (Near limit at ≥ 90 %, Over above 100 %), categories, recurring bills with Mark paid (works offline; one transaction per tap), subscriptions, a Spent today stat and Money card on Today, and an Expense mode in Quick Add.

Notes:
- Amounts are stored in paise (integers) everywhere; ₹ formatting matches the web.
- **Accounts are read-only on Android** — create, edit and archive them on the web. An archived account still referenced by an edited transaction shows "Choose account" in the picker (known cosmetic).
- Not ported from the web this phase: receipts and the detected-recurring banner.
- **Hide amounts** is a per-widget option of the Money widget (below); screens inside the app always show amounts.

### Quick Add – Expense
Pick the **Expense** chip and type an amount plus words, e.g. `₹240 food lunch` or `1,250.50 groceries`. The amount may lead or trail; the first word matching an expense category name is used as the category, and the default account is used. Undo is offered after saving.

### Widgets
Long-press the home screen, open Widgets, and add **Mitra · Today**. Tasks can be completed from the widget (works offline and syncs later); it refreshes at midnight. Tapping a title opens the task, ＋ opens Quick Add.

**Mitra · Money** (2×2 or 4×2) shows safe to spend per day, the month budget bar, the next bill and an **＋ Expense** button (opens Quick Add on Expense). With no budgets it shows "Spent this month" and Set budget. When placing it, a configuration screen asks whether to **Hide amounts**; long-press the widget → reconfigure to change it. With Hide amounts on, that widget never shows amounts (₹••••) nor the budget bar or its tone — it is a permanent per-widget setting, not lifted by opening the app. After sign-out or an expired session the widget shows "Sign in to Mitra" and no amounts. It redraws whenever local money data changes (including after sync) and at midnight, so safe to spend rolls over to the new day without opening the app.

### Launch links
Widgets open the app with these URIs (also usable for testing, e.g. `adb shell am start -n tech.beyondscale.mitra.debug/tech.beyondscale.mitra.android.MainActivity -a android.intent.action.VIEW -d mitra://money`):
`mitra://task/<id>`, `mitra://tasks`, `mitra://quick-add/task`, `mitra://money`, `mitra://quick-add/expense`, `mitra://money/budgets`, `mitra://checkin`, `mitra://ai`.

## Phase 4 – Habits + Goals
Offline-first habits and goals (same outbox sync). **Habits:** Today / Week lists grouped by time of day, one-tap check-off (binary, count and duration habits; partial counts like `5/8`), swipe right to complete and **swipe left to archive**, both with Undo; habit detail (30-day ring, streaks, 12-week heatmap, trend, recent days with Mark done), an editable month history calendar, and a habit form (schedule presets or custom days, reminder time, goal link, gentle mode). **Delete is in the habit detail menu** (with a confirm). **Goals:** Active / Review due / Completed, goal detail (progress, milestones add / edit / tick with a milestone sheet, progress chart, next action, linked tasks / habits / projects, blockers), Log progress, a goal form, and the goal review as a swipeable step pager (Keep / Adjust target / Pause). Tasks and projects can link a goal (goal chips open it; "Add task" from a goal pre-links it). Today gains a Habits stat plus Habits and Goals cards in the Settings → Today sections order; Quick Add gains **Habit check**.

Notes:
- Goal numbers (progress, status, milestone auto-ticks, completion) are recomputed locally with the server's rules, so the screen doesn't jump when the echo arrives.
- Logging goal progress is idempotent: each log carries a client id that stays the same across retries, and the server ignores a repeat.
- Habit logs older than 400 days are only on the device that wrote them (`GET habits` returns the last 400 days).
- Habit reminders are stored and synced but don't fire notifications until Phase 6.
- Habits and Goals live in the More sheet (Habits takes the third tab when Money is off).

## Phase 5 – Wellness + Notes + AI
Offline-first through the same outbox. **Wellness:** daily check-in (mood, sleep, steps, water, weight/BMI, note; only tracked fields), trends, sleep detail and wellness settings (targets, tracked metrics, reminders, Share with AI, Show on Today). **Notes:** list with folder / tag filters, search across notes, tasks, goals and habits, a full-screen editor with debounced autosave and a formatting toolbar, and the journal by date (AI draft, or an offline draft from local data). **AI:** Ask Mitra chat with progressive reveal, proposal cards (preview → confirm) that apply through the normal repositories, suggestions, and the activity log with Revert. Today gains a **Check-in** card (mood picker that saves today's mood) when Wellness is on and *Show on Today* is set; Quick Add gains **Note** (creates the note, then opens the editor writing) and **Mood** (logs today's mood, or "Full check-in"). Wellness and AI live in the More sheet and fall back to Today when switched off; their data isn't refreshed while off.

System integration (from the spec, pulled forward): **app shortcuts** (long-press the icon) New task · Add expense · Check-in · Ask Mitra, and a **share target** — sharing text or a link to Mitra opens Quick Add on Note, prefilled (subject on the first line).

Rulings:
- AI actions are logged with a client id; a retry returns the original row and a reused id is a 409 (backend fix `7a10fd6`), so a retried log or revert never duplicates. Reverts run oldest-first (FIFO) and apply the server's undo ops exactly once.
- Chat history and suggestion state (dismissed / done) are device-local, as on the web.
- **Share with AI** mirrors the web toggle: it sets wellness `shareAI` and also writes the account setting `shareWellness`. The backend includes wellness in AI context when either is on; turning the toggle off clears both, so wellness leaves AI context (the web has the same two-flag design).

Known gaps:
- Notes are last-write-wins (the server compares date-only `updatedAt`), so concurrent edits on two devices keep the later save.
- There's no `POST activity` endpoint: the activity feed is read-only, and the journal's offline draft uses local data.
- Some server-built labels use the server's time zone; AI "plan" proposals don't carry an amount; reverting a recurring item only restores its reminder.
- Not ported: checklist → task suggestion and "Copy link" in the note editor.

## Run locally
1. Start the backend (`cd ../mitra-backend && pnpm dev`; needs Postgres + Redis).
2. `local.properties`: `sdk.dir=…` and `mitra.apiUrl=http://10.0.2.2:4000/api/` (emulator) or `http://<Mac LAN IP>:4000/api/` (phone).
3. `./gradlew :androidApp:installDebug`

### Debug networking
Debug builds allow cleartext HTTP only to `10.0.2.2`, `localhost`, `127.0.0.1` and the host in `mitra.apiUrl`. Rebuild after changing `mitra.apiUrl`. Release builds use HTTPS only (`-Pmitra.releaseApiUrl=https://…/api/`).

### Physical phone
1. Enable USB debugging on the phone and connect it to the Mac.
2. Put the phone and the Mac on the same Wi‑Fi network.
3. In `local.properties`, set `mitra.apiUrl=http://<Mac LAN IP>:4000/api/`.
4. `./gradlew :androidApp:installDebug`

## Test
`./gradlew testAndroidHostTest` runs all shared unit tests on the JVM (762 tests at time of writing).

## Device checklist
Not yet run on a device (planned once, after Phase 6). The deferred checklists live in the plans: the "Deferred device checklist" sections of `docs/superpowers/plans/2026-10-14-android-phase2-tasks.md` (Tasks), `…-phase3-money.md` (Money) and `…-phase4-habits-goals.md` (Habits + Goals, items 27–43). Phase 5 (Wellness + Notes + AI):

44. Check-in: save mood + sleep + steps offline → Trends and Sleep detail update; it syncs when online.
45. Today Check-in card: tap a mood → snackbar, Wellness shows it; *Show on Today* off (or Wellness off) hides the card.
46. Wellness settings → Share with AI off → Ask Mitra's context excludes wellness (and the web's Settings toggle shows off too).
47. Notes: create from the list → editor opens writing; type, leave, reopen → text kept (autosave); search finds it.
48. Journal: open today offline → offline draft; online → AI draft.
49. Quick Add → Note "Idea" → editor opens on the new note; Quick Add → Mood → logged.
50. Ask Mitra: a proposal card → Preview → Confirm applies it; Log → Revert undoes it; kill the app mid-confirm → no duplicate on retry.
51. Settings → AI off / Wellness off → their screens fall back to Today; More no longer lists them; Mood chip disappears.
52. Long-press the launcher icon → New task / Add expense / Check-in / Ask Mitra each open the right screen.
53. Share a link from Chrome to Mitra → Quick Add opens on Note with the link; Create note → editor.

## Icons
After changing `mitra-frontend/src/lib/icons.ts`:
`python3 -I tools/gen_icons.py ../mitra-frontend/src/lib/icons.ts shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons/MitraIcons.kt tech.beyondscale.mitra.designsystem.icons`
