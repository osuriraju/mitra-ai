# Mitra Mobile (Android, KMP + Compose Multiplatform)

Native Android app for Mitra AI. Shared Kotlin code in `shared/*` is structured for a future iOS app.

## Layout
- `shared/core` – Outcome/AppError, clock, date + INR formatting (matches the web)
- `shared/domain` – models, repository interfaces, use cases (no frameworks)
- `shared/data` – Ktor API client, SQLDelight DB, offline outbox + SyncEngine, repositories
- `shared/designsystem` – Noir theme, fonts, icons (generated from the web), components
- `shared/feature` – screens + ViewModels; `shared/app` – navigation, shell, DI
  - `shared/feature/tasks` – Tasks screen (Inbox / Today / Upcoming / Projects), task form + detail, projects, focus timer, multi-select
  - `quickadd` – Quick Add sheet and natural-language parser
- `androidApp` – Android entry point, Keystore storage, WorkManager sync
  - `androidApp/widget` – "Mitra · Today" home-screen widget (Glance)

## Phase 2 – Tasks
Offline-first tasks and projects (outbox sync with hardening), module refresher + periodic sync + refresh on foreground, Tasks screen with swipe (complete / move to tomorrow) and undo, filters and pull-to-refresh, task form/detail with Subtask-of picker and notes autosave, projects, focus timer, Quick Add, real data on Today, long-press multi-select, and the Tasks home-screen widget.

### Quick Add syntax
Type a title plus any of: `today`, `tomorrow`, a weekday name (`friday`), `p1`–`p3` (priority), `#project`. Example: `Call John tomorrow p1 #work`.

### Widgets
Long-press the home screen, open Widgets, and add **Mitra · Today**. Tasks can be completed from the widget (works offline and syncs later); it refreshes at midnight. Tapping a title opens the task, ＋ opens Quick Add.

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
`./gradlew testAndroidHostTest` runs all shared unit tests on the JVM (257 tests at time of writing).

## Icons
After changing `mitra-frontend/src/lib/icons.ts`:
`python3 -I tools/gen_icons.py ../mitra-frontend/src/lib/icons.ts shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons/MitraIcons.kt tech.beyondscale.mitra.designsystem.icons`
