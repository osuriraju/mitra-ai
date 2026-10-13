# Mitra Mobile (Android, KMP + Compose Multiplatform)

Native Android app for Mitra AI. Shared Kotlin code in `shared/*` is structured for a future iOS app.

## Layout
- `shared/core` – Outcome/AppError, clock, date + INR formatting (matches the web)
- `shared/domain` – models, repository interfaces, use cases (no frameworks)
- `shared/data` – Ktor API client, SQLDelight DB, offline outbox + SyncEngine, repositories
- `shared/designsystem` – Noir theme, fonts, icons (generated from the web), components
- `shared/feature` – screens + ViewModels; `shared/app` – navigation, shell, DI
- `androidApp` – Android entry point, Keystore storage, WorkManager sync

## Run locally
1. Start the backend (`cd ../mitra-backend && pnpm dev`; needs Postgres + Redis).
2. `local.properties`: `sdk.dir=…` and `mitra.apiUrl=http://10.0.2.2:4000/api/` (emulator) or `http://<Mac LAN IP>:4000/api/` (phone).
3. `./gradlew :androidApp:installDebug`

### Physical phone
1. Enable USB debugging on the phone and connect it to the Mac.
2. Put the phone and the Mac on the same Wi‑Fi network.
3. In `local.properties`, set `mitra.apiUrl=http://<Mac LAN IP>:4000/api/`.
4. `./gradlew :androidApp:installDebug`

## Test
`./gradlew testAndroidHostTest` runs all shared unit tests on the JVM (86 tests at time of writing).

## Icons
After changing `mitra-frontend/src/lib/icons.ts`:
`python3 -I tools/gen_icons.py ../mitra-frontend/src/lib/icons.ts shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons/MitraIcons.kt tech.beyondscale.mitra.designsystem.icons`
