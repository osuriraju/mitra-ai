# Mitra Mobile (Android, KMP + Compose Multiplatform)

Native Android app for Mitra AI. Shared Kotlin code in `shared/*` is structured for a future iOS app.

## Layout
- `shared/core` – Outcome/AppError, clock, date + INR formatting (matches the web)
- `shared/domain` – models, repository interfaces, use cases (no frameworks)
  - `shared/domain/money` – money selectors (safe to spend, budgets, analytics), Today money card, widget model, expense parser
- `shared/data` – Ktor API client, SQLDelight DB, offline outbox + SyncEngine, repositories
- `shared/designsystem` – Noir theme, fonts, icons (generated from the web), components
- `shared/feature` – screens + ViewModels; `shared/app` – navigation, shell, DI
  - `shared/feature/tasks` – Tasks screen (Inbox / Today / Upcoming / Projects), task form + detail, projects, focus timer, multi-select
  - `shared/feature/money` – Money dashboard, transactions + transaction sheet, budgets, categories, recurring bills, subscriptions
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
`mitra://task/<id>`, `mitra://tasks`, `mitra://quick-add/task`, `mitra://money`, `mitra://quick-add/expense`, `mitra://money/budgets`.

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
`./gradlew testAndroidHostTest` runs all shared unit tests on the JVM (428 tests at time of writing).

## Icons
After changing `mitra-frontend/src/lib/icons.ts`:
`python3 -I tools/gen_icons.py ../mitra-frontend/src/lib/icons.ts shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons/MitraIcons.kt tech.beyondscale.mitra.designsystem.icons`
