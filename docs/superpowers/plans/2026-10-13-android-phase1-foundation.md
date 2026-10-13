# Mitra Android — Phase 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `mitra-mobile/` Kotlin Multiplatform project and ship an installable Android debug APK where a user signs in with their existing Mitra account, lands in the app shell (bottom bar, More sheet, Today, Settings), changes theme and module toggles (synced through the offline outbox), and signs out.

**Architecture:** KMP + Compose Multiplatform, Clean Architecture (feature → domain ← data) with MVVM. SQLDelight is the local source of truth; writes go to an `outbox` table drained in order by `SyncEngine`. The session is the backend's existing httpOnly cookie, held by Ktor `HttpCookies` in an encrypted store. Android-only code lives in `androidApp/` and `*/androidMain`.

**Tech Stack:** Kotlin 2.4.21, AGP 9.4.1 (`com.android.kotlin.multiplatform.library` for shared modules), Gradle 9.8.1, Compose Multiplatform 1.12.1 (material3 1.9.0), JetBrains Navigation 2.9.2, JetBrains Lifecycle 2.11.0, Koin 4.2.2, Ktor 3.6.0, SQLDelight 2.4.1, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0, kotlinx-datetime 0.8.0, WorkManager 2.12.0, Turbine 1.2.1.

**Spec:** `docs/superpowers/specs/2026-10-13-android-app-design.md` (this plan covers spec §13 phase 1; phases 2–6 get their own plans).

## Global Constraints

- All new code lives under `mitra-mobile/` (repo root is `/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai` — **the path contains a space; always quote it**).
- Kotlin package root `tech.beyondscale.mitra`; Android `applicationId` `tech.beyondscale.mitra` (debug suffix `.debug`).
- compileSdk **37**, targetSdk **36**, minSdk **26**. (Spec said compileSdk 36; current Ktor/OkHttp require 37 — verified in a spike. Install with `sdkmanager "platforms;android-37.0"` if missing.)
- JDK 17 (`/opt/homebrew/opt/openjdk@17`). Gradle via wrapper only.
- Versions are exactly those in `gradle/libs.versions.toml` (Task 1). They were compiled together in a throwaway spike on 2026-10-13; do not bump them in this phase.
- `shared/*/commonMain` must not import Android APIs. Platform code goes in `androidMain` (or `iosMain` later).
- `domain` depends only on `core` (+ coroutines). `feature` never depends on `data`. Only `app` and `androidApp` wire `data` in.
- ViewModels depend on domain only: use cases where there is logic (auth validation), repository interfaces otherwise. *(Relaxation of spec §8 "ViewModels only call use cases" to avoid one-line pass-through use cases; flagged for the user.)*
- API paths in Kotlin are relative with **no leading slash** (`"auth/login"`), joined to a base URL ending in `/api/`.
- Every POST/PATCH with no payload sends `{}` (the backend's Fastify rejects empty JSON bodies).
- Money is INR; amounts on the wire are integer paise. Formatting helpers must match `mitra-frontend/src/lib/dates.ts` output exactly.
- UI copy, colors and icons follow the web app: Noir tokens from `mitra-frontend/src/app/globals.css`, icons generated from `mitra-frontend/src/lib/icons.ts`, fonts Manrope + JetBrains Mono.
- Local preferences (theme, last user id) are stored in a SQLDelight `pref` table, not DataStore — one storage engine. *(Deviation from spec §4; flagged for the user.)*
- Password reset: the app sends the reset email; the emailed link opens the existing web reset page. A native reset screen (with verified app links) is deferred to Phase 6. *(Flagged for the user.)*
- Tests: `./gradlew testAndroidHostTest` from `mitra-mobile/` runs every shared module's `commonTest` on the JVM.
- Commit after every task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `local.properties`.

## Review Focus

1. **Wrong password** — the backend answers `401 BAD_CREDENTIALS`; the user must see "Email/username or password is incorrect" and must *not* trigger the session-expired flow. Pinned in Task 5 (`ApiClientTest`) and Task 9 (`LoginViewModelTest`).
2. **Rapid settings toggles while a PATCH is in flight** — the second change must not be merged into the in-flight row (and lost), and the server's echo of the first change must not overwrite the newer local value. Pinned in Task 6 (`OutboxWriterTest`) and Task 7 (`SettingsRepositoryImplTest`).
3. **App killed mid-sync** — a row left `inFlight = 1` must be retried after restart. Pinned in Task 7 (`AuthRepositoryImplTest.restoreResetsInFlightRows`).
4. **Session expires with unsynced edits** — same user signing back in keeps and drains the outbox; a different user wipes it. Pinned in Task 7 (`AuthRepositoryImplTest`).
5. **Corrupt persisted data** — garbage in the stored cookie JSON or wrong-typed fields in `user.settings` must fall back to defaults, never crash. Pinned in Task 5 (`PersistentCookiesStorageTest`, `UserDtoTest`).

---

## File Map (all paths relative to `mitra-mobile/`)

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew*, gradle/wrapper/*
.gitignore, README.md, local.properties (untracked)
tools/gen_icons.py                                   web icons.ts → MitraIcons.kt generator
build-logic/{settings,build}.gradle.kts
build-logic/src/main/kotlin/mitra.kmp.gradle.kts     shared KMP + Android-library convention

shared/core/          …/core/{Outcome,AppError,AppClock,Ids,Dates,Money}.kt
shared/domain/        …/domain/model/{User,Settings,AppState}.kt
                      …/domain/repository/{AuthRepository,SettingsRepository,PreferencesRepository,SyncController}.kt
                      …/domain/usecase/{AuthValidation,AuthUseCases}.kt
shared/testing/       …/testing/{Fakes,FixedClock,ViewModelTest}.kt     (test doubles, used by commonTest of other modules)
shared/data/          sqldelight/…/data/db/{User,Outbox,Pref}.sq
                      …/data/local/{PrefStore,UserCache,LocalData}.kt
                      …/data/remote/{ApiClient,Dtos,SecureStore,PersistentCookiesStorage}.kt
                      …/data/sync/{OutboxWriter,SyncEngine}.kt
                      …/data/repository/{AuthRepositoryImpl,SettingsRepositoryImpl,PreferencesRepositoryImpl}.kt
                      …/data/di/DataModule.kt
                      androidMain …/data/platform/{KeystoreSecureStore,AndroidDataModule}.kt
shared/designsystem/  composeResources/font/{manrope,jetbrains_mono}.ttf
                      …/designsystem/theme/{Colors,Type,Theme}.kt
                      …/designsystem/icons/MitraIcons.kt (generated)
                      …/designsystem/components/{Card,Button,TextField,Basics,Bars}.kt
shared/feature/       …/feature/auth/{AuthScaffold,Login,Signup,ForgotPassword}*.kt
                      …/feature/today/Today*.kt, …/feature/settings/Settings*.kt, …/feature/common/ComingSoonScreen.kt
                      …/feature/di/FeatureModule.kt
shared/app/           …/app/{RootViewModel,MitraApp,Destinations,MainShell,AppModules}.kt
androidApp/           build.gradle.kts, src/main/AndroidManifest.xml, res/{values/themes.xml,mipmap-xxxhdpi/ic_launcher.png}
                      kotlin/tech/beyondscale/mitra/android/{MitraApplication,MainActivity,SyncWorker}.kt
```
`…` = `src/commonMain/kotlin/tech/beyondscale/mitra` (tests: `src/commonTest/kotlin/tech/beyondscale/mitra`).

---

### Task 1: Gradle scaffold, convention plugin, empty app that launches

**Files:**
- Create: `mitra-mobile/{settings.gradle.kts,build.gradle.kts,gradle.properties,.gitignore,local.properties}`
- Create: `mitra-mobile/gradle/libs.versions.toml`, wrapper files
- Create: `mitra-mobile/build-logic/{settings.gradle.kts,build.gradle.kts,src/main/kotlin/mitra.kmp.gradle.kts}`
- Create: `mitra-mobile/shared/{core,domain,testing,data,designsystem,feature,app}/build.gradle.kts`
- Create: `mitra-mobile/shared/app/src/commonMain/kotlin/tech/beyondscale/mitra/app/MitraApp.kt` (temporary body, replaced in Task 11)
- Create: `mitra-mobile/androidApp/build.gradle.kts`, `src/main/AndroidManifest.xml`, `src/main/res/values/themes.xml`, `src/main/kotlin/tech/beyondscale/mitra/android/MainActivity.kt`

**Interfaces:**
- Produces: convention plugin id `mitra.kmp` (KMP + Android library, iOS arm64/simulatorArm64 targets, coroutines in commonMain, kotlin-test + coroutines-test in commonTest, namespace `tech.beyondscale.mitra.<module-name>`); Gradle projects `:shared:core`, `:shared:domain`, `:shared:testing`, `:shared:data`, `:shared:designsystem`, `:shared:feature`, `:shared:app`, `:androidApp`; test task `testAndroidHostTest`.

- [ ] **Step 1: Make sure the API 37 platform is installed**

```bash
ls ~/Library/Android/sdk/platforms | grep -q android-37 || yes | ~/Library/Android/sdk/cmdline-tools/cmdline-tools/bin/sdkmanager "platforms;android-37.0"
```

- [ ] **Step 2: Generate the Gradle wrapper (before any build files exist)**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai" && mkdir -p mitra-mobile && cd mitra-mobile
touch settings.gradle.kts
~/.gradle/wrapper/dists/gradle-9.0.0-bin/*/gradle-9.0.0/bin/gradle wrapper --gradle-version 9.8.1 -q
ls gradlew gradle/wrapper/gradle-wrapper.properties
```
Expected: both files listed. (If the cached Gradle 9.0.0 is missing, `brew install gradle` and run `gradle wrapper --gradle-version 9.8.1`.)

- [ ] **Step 3: Write the version catalog** — `gradle/libs.versions.toml`

```toml
[versions]
kotlin = "2.4.21"
agp = "9.4.1"
compose-mp = "1.12.1"
material3 = "1.9.0"
ktor = "3.6.0"
sqldelight = "2.4.1"
koin = "4.2.2"
coroutines = "1.11.0"
serialization = "1.11.0"
datetime = "0.8.0"
nav = "2.9.2"
lifecycle = "2.11.0"
activity = "1.13.0"
work = "2.12.0"
turbine = "1.2.1"

[libraries]
gradle-kotlin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
gradle-agp = { module = "com.android.tools.build:gradle", version.ref = "agp" }
ktor-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
sqldelight-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }
sqldelight-sqlite = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
koin-compose-vm = { module = "io.insert-koin:koin-compose-viewmodel", version.ref = "koin" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
nav-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "nav" }
lifecycle-vm-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
work = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
cmp-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "compose-mp" }
cmp-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "compose-mp" }
cmp-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "compose-mp" }
cmp-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "compose-mp" }
cmp-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "material3" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose-mp = { id = "org.jetbrains.compose", version.ref = "compose-mp" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

- [ ] **Step 4: Root build files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "mitra-mobile"
include(
    ":androidApp",
    ":shared:core", ":shared:domain", ":shared:testing", ":shared:data",
    ":shared:designsystem", ":shared:feature", ":shared:app",
)
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.mp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.sqldelight) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
# iOS targets are declared for later but cannot build without Xcode; skip them quietly.
kotlin.native.ignoreDisabledTargets=true
```

`.gitignore`:
```
.gradle/
.kotlin/
build/
local.properties
.idea/
*.iml
captures/
```

`local.properties` (untracked):
```properties
sdk.dir=/Users/rajuosuri/Library/Android/sdk
# Emulator → host machine. For a physical phone use http://<your-mac-LAN-ip>:4000/api/
mitra.apiUrl=http://10.0.2.2:4000/api/
```

- [ ] **Step 5: Convention plugin**

`build-logic/settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`:
```kotlin
plugins { `kotlin-dsl` }
dependencies {
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.agp)
}
```

`build-logic/src/main/kotlin/mitra.kmp.gradle.kts`:
```kotlin
// Shared setup for every KMP module: Android library + iOS targets, coroutines, test deps.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    androidLibrary {
        namespace = "tech.beyondscale.mitra." + project.name.replace('-', '.')
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlin.uuid.ExperimentalUuidApi")
            }
        }
        commonMain.dependencies { implementation(lib("coroutines-core")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(lib("coroutines-test"))
        }
    }
}
```

- [ ] **Step 6: Module build files**

`shared/core/build.gradle.kts`:
```kotlin
plugins { id("mitra.kmp") }
kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.datetime) }
    }
}
```

`shared/domain/build.gradle.kts`:
```kotlin
plugins { id("mitra.kmp") }
kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":shared:core")) }
        commonTest.dependencies { implementation(project(":shared:testing")) }
    }
}
```

`shared/testing/build.gradle.kts`:
```kotlin
plugins { id("mitra.kmp") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(kotlin("test"))
            api(libs.coroutines.test)
            api(libs.turbine)
        }
    }
}
```

`shared/data/build.gradle.kts`:
```kotlin
plugins {
    id("mitra.kmp")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            implementation(libs.ktor.core)
            implementation(libs.serialization.json)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.sqldelight.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.darwin)
            implementation(libs.sqldelight.native)
        }
        commonTest.dependencies {
            implementation(project(":shared:testing"))
            implementation(libs.ktor.mock)
        }
        getByName("androidHostTest").dependencies { implementation(libs.sqldelight.sqlite) }
    }
}
sqldelight {
    databases { create("MitraDb") { packageName.set("tech.beyondscale.mitra.data.db") } }
}
```

`shared/designsystem/build.gradle.kts`:
```kotlin
plugins {
    id("mitra.kmp")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.mp)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            implementation(libs.cmp.resources)
        }
    }
}
compose.resources {
    packageOfResClass = "tech.beyondscale.mitra.designsystem.resources"
    publicResClass = false
}
```

`shared/feature/build.gradle.kts`:
```kotlin
plugins {
    id("mitra.kmp")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.mp)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(project(":shared:designsystem"))
            api(libs.lifecycle.vm.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.koin.compose.vm)
        }
        commonTest.dependencies { implementation(project(":shared:testing")) }
    }
}
```

`shared/app/build.gradle.kts`:
```kotlin
plugins {
    id("mitra.kmp")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.mp)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:feature"))
            api(project(":shared:data"))
            implementation(libs.nav.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.koin.compose.vm)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies { implementation(project(":shared:testing")) }
    }
}
```

`androidApp/build.gradle.kts`:
```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "tech.beyondscale.mitra.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "tech.beyondscale.mitra"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["cleartext"] = "true"
            buildConfigField("String", "API_URL", "\"${localProps.getProperty("mitra.apiUrl", "http://10.0.2.2:4000/api/")}\"")
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["cleartext"] = "false"
            buildConfigField("String", "API_URL", "\"${providers.gradleProperty("mitra.releaseApiUrl").getOrElse("")}\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":shared:app"))
    implementation(libs.activity.compose)
    implementation(libs.work)
    implementation(libs.koin.android)
}
```

`androidApp/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application
        android:label="Mitra"
        android:allowBackup="false"
        android:usesCleartextTraffic="${cleartext}"
        android:enableOnBackInvokedCallback="true"
        android:theme="@style/Theme.Mitra">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`androidApp/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Noir background from the first frame, so there's no white flash before Compose draws. -->
    <style name="Theme.Mitra" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
    </style>
</resources>
```

`shared/app/src/commonMain/kotlin/tech/beyondscale/mitra/app/MitraApp.kt` (temporary):
```kotlin
package tech.beyondscale.mitra.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun MitraApp() {
    Text("Mitra")
}
```

`androidApp/src/main/kotlin/tech/beyondscale/mitra/android/MainActivity.kt` (temporary):
```kotlin
package tech.beyondscale.mitra.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tech.beyondscale.mitra.app.MitraApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MitraApp() }
    }
}
```

- [ ] **Step 7: Build**

Run: `cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile" && ./gradlew :androidApp:assembleDebug testAndroidHostTest`
Expected: `BUILD SUCCESSFUL`; `androidApp/build/outputs/apk/debug/androidApp-debug.apk` exists. (First run downloads dependencies; allow ~10 min.)

- [ ] **Step 8: Commit**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai"
git add mitra-mobile && git status --short mitra-mobile | grep -c local.properties  # expect 0
git commit -m "feat(mobile): scaffold KMP project with convention plugin and Android app shell

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: core — Outcome, AppError, clock, ids, date and INR formatting

**Files:**
- Create: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/{Outcome,AppError,AppClock,Ids,Dates,Money}.kt`
- Test: `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/{DatesTest,MoneyTest}.kt`

**Interfaces:**
- Produces:
  - `sealed interface Outcome<out T> { data class Ok<out T>(val value: T); data class Err(val error: AppError) : Outcome<Nothing> }`, `Outcome.map`, `onOk`, `onErr`
  - `data class FieldIssue(path: String, message: String)`; `sealed interface AppError { val message: String }` with `Offline`, `Unauthenticated(code, message)`, `Validation(issues, message)`, `Server(status, code, message)`, `Unknown(cause)`
  - `interface AppClock { fun now(): kotlin.time.Instant; val zone: TimeZone }`, `object SystemClock : AppClock`, `AppClock.localNow(): LocalDateTime`
  - `fun newId(): String` (UUID v4 string)
  - `addDays(iso: String, n: Int): String`, `fmtLong(iso: String): String`, `fmtClock(hour: Int, minute: Int): String`, `greeting(hour: Int): String`
  - `inr(rupees: Double): String`, `inrShort(rupees: Double): String`

- [ ] **Step 1: Write failing tests**

`DatesTest.kt`:
```kotlin
package tech.beyondscale.mitra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DatesTest {
    @Test fun fmtLongMatchesWeb() = assertEquals("Tuesday, 13 October 2026", fmtLong("2026-10-13"))

    @Test fun addDaysCrossesMonthAndLeapBoundaries() {
        assertEquals("2026-11-01", addDays("2026-10-31", 1))
        assertEquals("2026-02-28", addDays("2026-03-01", -1))
        assertEquals("2028-02-29", addDays("2028-02-28", 1))
    }

    @Test fun fmtClockUses12HourTime() {
        assertEquals("12:05 AM", fmtClock(0, 5))
        assertEquals("12:30 PM", fmtClock(12, 30))
        assertEquals("1:00 PM", fmtClock(13, 0))
        assertEquals("11:59 PM", fmtClock(23, 59))
    }

    @Test fun greetingBoundariesMatchWeb() {
        assertEquals("Good morning", greeting(11))
        assertEquals("Good afternoon", greeting(12))
        assertEquals("Good afternoon", greeting(16))
        assertEquals("Good evening", greeting(17))
    }
}
```

`MoneyTest.kt`:
```kotlin
package tech.beyondscale.mitra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test fun inrUsesIndianGrouping() {
        assertEquals("₹0", inr(0.0))
        assertEquals("₹999", inr(999.0))
        assertEquals("₹1,000", inr(1000.0))
        assertEquals("₹1,00,000", inr(100000.0))
        assertEquals("₹12,34,567", inr(1234567.0))
    }

    @Test fun inrRoundsLikeJsMathRoundAndDropsSign() {
        assertEquals("₹1,501", inr(-1500.6))
        assertEquals("₹1,500", inr(-1500.5)) // JS Math.round(-1500.5) = -1500
        assertEquals("₹1,501", inr(1500.5))
    }

    @Test fun inrShortMatchesWeb() {
        assertEquals("₹1.50L", inrShort(150000.0))
        assertEquals("₹2.5k", inrShort(2500.0))
        assertEquals("₹-2.5k", inrShort(-2500.0))
        assertEquals("₹1.2k", inrShort(1234.0))
        assertEquals("₹999", inrShort(999.0))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:core:testAndroidHostTest`
Expected: FAIL — compilation errors `Unresolved reference 'fmtLong'`, `'inr'`.

- [ ] **Step 3: Implement**

`Outcome.kt`:
```kotlin
package tech.beyondscale.mitra.core

/** Result of an operation that can fail with a typed [AppError]. */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

inline fun <T> Outcome<T>.onOk(action: (T) -> Unit): Outcome<T> { if (this is Outcome.Ok) action(value); return this }
inline fun <T> Outcome<T>.onErr(action: (AppError) -> Unit): Outcome<T> { if (this is Outcome.Err) action(error); return this }
```

`AppError.kt`:
```kotlin
package tech.beyondscale.mitra.core

data class FieldIssue(val path: String, val message: String)

/** Every failure the UI can see. API errors (`{ error: { code, message, issues? } }`) map onto these. */
sealed interface AppError {
    val message: String

    data object Offline : AppError {
        override val message = "You appear to be offline"
    }

    /** 401. `code` is UNAUTHENTICATED for an expired session, BAD_CREDENTIALS for a wrong password. */
    data class Unauthenticated(val code: String = "UNAUTHENTICATED", override val message: String = "Please sign in") : AppError

    data class Validation(
        val issues: List<FieldIssue>,
        override val message: String = issues.firstOrNull()?.message ?: "Please check the form",
    ) : AppError

    data class Server(val status: Int, val code: String, override val message: String) : AppError

    data class Unknown(val cause: Throwable) : AppError {
        override val message = "Something went wrong"
    }
}
```

`AppClock.kt`:
```kotlin
package tech.beyondscale.mitra.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

interface AppClock {
    fun now(): Instant
    val zone: TimeZone
}

object SystemClock : AppClock {
    override fun now(): Instant = Clock.System.now()
    override val zone: TimeZone get() = TimeZone.currentSystemDefault()
}

fun AppClock.localNow(): LocalDateTime = now().toLocalDateTime(zone)
```

`Ids.kt`:
```kotlin
package tech.beyondscale.mitra.core

import kotlin.uuid.Uuid

/** Client-generated ids for optimistic creates — the API validates them as UUIDs. */
fun newId(): String = Uuid.random().toString()
```

`Dates.kt`:
```kotlin
package tech.beyondscale.mitra.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

/* Ports of mitra-frontend/src/lib/dates.ts — days are ISO strings ("2026-09-17") everywhere. */

private val DOW_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MON_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

fun addDays(iso: String, n: Int): String = LocalDate.parse(iso).plus(n, DateTimeUnit.DAY).toString()

/** "Tuesday, 13 October 2026" */
fun fmtLong(iso: String): String {
    val d = LocalDate.parse(iso)
    return "${DOW_LONG[d.dayOfWeek.isoDayNumber - 1]}, ${d.day} ${MON_LONG[d.month.number - 1]} ${d.year}"
}

/** "4:05 PM" */
fun fmtClock(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

fun greeting(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
```

`Money.kt`:
```kotlin
package tech.beyondscale.mitra.core

import kotlin.math.abs
import kotlin.math.floor

/* Ports of inr / inrShort in mitra-frontend/src/lib/dates.ts — output must match the web exactly. */

/** JS Math.round: halves round toward +infinity. */
private fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

private fun groupIndian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val parts = ArrayDeque<String>()
    var rest = s.dropLast(3)
    while (rest.length > 2) { parts.addFirst(rest.takeLast(2)); rest = rest.dropLast(2) }
    if (rest.isNotEmpty()) parts.addFirst(rest)
    return parts.joinToString(",") + "," + s.takeLast(3)
}

/** JS Number.prototype.toFixed for the magnitudes we format. */
internal fun toFixed(x: Double, digits: Int): String {
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = floor(abs(x) * factor + 0.5).toLong()
    val sign = if (x < 0 && scaled != 0L) "-" else ""
    val whole = scaled / factor
    if (digits == 0) return "$sign$whole"
    return "$sign$whole.${(scaled % factor).toString().padStart(digits, '0')}"
}

/** "₹12,34,567" — always positive, like the web. */
fun inr(rupees: Double): String = "₹" + groupIndian(abs(jsRound(rupees)))

/** "₹1.50L" · "₹2.5k" · "₹999" */
fun inrShort(rupees: Double): String = when {
    abs(rupees) >= 100_000 -> "₹${toFixed(rupees / 100_000, 2)}L"
    abs(rupees) >= 1_000 -> "₹${toFixed(rupees / 1_000, 1)}k"
    else -> inr(rupees)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:core:testAndroidHostTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add mitra-mobile/shared/core && git commit -m "feat(mobile): core outcome/error types and web-identical date + INR formatting

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: domain models, repository interfaces, auth use cases + shared test doubles

**Files:**
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/model/{User,Settings,AppState}.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/repository/{AuthRepository,SettingsRepository,PreferencesRepository,SyncController}.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/usecase/{AuthValidation,AuthUseCases}.kt`
- Create: `shared/testing/src/commonMain/kotlin/tech/beyondscale/mitra/testing/{Fakes,FixedClock,ViewModelTest}.kt`
- Test: `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/usecase/AuthUseCasesTest.kt`

**Interfaces:**
- Consumes: `Outcome`, `AppError`, `FieldIssue`, `AppClock` (Task 2).
- Produces:
  - `data class User(id, name, email, username: String?, phone: String?, settings: Settings, onboardedAt: String?, memberSince: String)` with `firstName: String`, `initials: String`
  - `data class Modules(money = true, wellness = true, ai = true)`; `data class Settings(...)` with web defaults (`Settings.DEFAULT_TODAY_SECTIONS`)
  - `sealed interface SessionState { Unknown; SignedOut; SignedIn(user) }`; `sealed interface SyncStatus { Idle; Syncing(pending: Long); Offline(pending: Long); Retrying(pending: Long) }`; `enum class ThemeMode { Dark, Light, Auto }`
  - `interface AuthRepository { val session: StateFlow<SessionState>; suspend fun restore(); suspend fun refresh(): Outcome<User>; suspend fun login(identifier, password): Outcome<User>; suspend fun signup(name, email, password): Outcome<User>; suspend fun forgotPassword(email): Outcome<Unit>; suspend fun logout() }`
  - `interface SettingsRepository { suspend fun updateModules(modules: Modules) }`
  - `interface PreferencesRepository { val theme: Flow<ThemeMode>; suspend fun setTheme(mode: ThemeMode) }`
  - `interface SyncController { val status: StateFlow<SyncStatus>; val pendingCount: Flow<Long>; val messages: Flow<String>; fun requestSync() }`
  - `LoginUseCase(repo)(identifier, password)`, `SignupUseCase(repo)(name, email, password)`, `ForgotPasswordUseCase(repo)(email)` — all `suspend operator fun invoke(...)`
  - testing: `FakeAuthRepository`, `FakeSettingsRepository`, `FakePreferencesRepository`, `FakeSyncController`, `testUser(...)`, `FixedClock(instant, zone)`, `abstract class ViewModelTest` (sets `Dispatchers.Main`)

- [ ] **Step 1: Domain models and interfaces** (no logic to test; written first so the tests compile)

`model/Settings.kt`:
```kotlin
package tech.beyondscale.mitra.domain.model

data class Modules(val money: Boolean = true, val wellness: Boolean = true, val ai: Boolean = true)

/** Mirrors `Settings` in mitra-frontend/src/store/types.ts; defaults mirror store/seed.ts SETTINGS. */
data class Settings(
    val reduceMotion: Boolean = false,
    val compact: Boolean = false,
    val modules: Modules = Modules(),
    val todaySections: List<String> = DEFAULT_TODAY_SECTIONS,
    val weekStart: String = "Monday",
    val dayStart: String = "06:00",
    val quietHours: Boolean = true,
    val digest: Boolean = true,
    val autoSave: String = "Small only",
    val shareWellness: Boolean = false,
    val focusMinutes: Int = 25,
) {
    companion object {
        val DEFAULT_TODAY_SECTIONS = listOf("priorities", "activity", "habits", "checkin", "money", "goals")
    }
}
```

`model/User.kt`:
```kotlin
package tech.beyondscale.mitra.domain.model

data class User(
    val id: String,
    val name: String,
    val email: String,
    val username: String?,
    val phone: String?,
    val settings: Settings,
    val onboardedAt: String?,
    val memberSince: String,
) {
    val firstName: String get() = name.trim().split(" ").first()
    val initials: String
        get() = name.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}
```

`model/AppState.kt`:
```kotlin
package tech.beyondscale.mitra.domain.model

sealed interface SessionState {
    /** Still reading the local cache / asking the server. */
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: User) : SessionState
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Syncing(val pending: Long) : SyncStatus
    /** No network; changes are queued and will go out when it returns. */
    data class Offline(val pending: Long) : SyncStatus
    /** Server error (5xx/429); retrying with backoff. */
    data class Retrying(val pending: Long) : SyncStatus
}

enum class ThemeMode { Dark, Light, Auto }
```

`repository/AuthRepository.kt`:
```kotlin
package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.StateFlow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.User

interface AuthRepository {
    val session: StateFlow<SessionState>
    /** On launch: show the cached user immediately (offline-first), then confirm with the server. */
    suspend fun restore()
    suspend fun refresh(): Outcome<User>
    suspend fun login(identifier: String, password: String): Outcome<User>
    suspend fun signup(name: String, email: String, password: String): Outcome<User>
    suspend fun forgotPassword(email: String): Outcome<Unit>
    /** Always succeeds locally, even offline: clears the session cookie and all user data on the device. */
    suspend fun logout()
}
```

`repository/SettingsRepository.kt`:
```kotlin
package tech.beyondscale.mitra.domain.repository

import tech.beyondscale.mitra.domain.model.Modules

interface SettingsRepository {
    suspend fun updateModules(modules: Modules)
}
```

`repository/PreferencesRepository.kt`:
```kotlin
package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.domain.model.ThemeMode

/** Device-local preferences (not synced). */
interface PreferencesRepository {
    val theme: Flow<ThemeMode>
    suspend fun setTheme(mode: ThemeMode)
}
```

`repository/SyncController.kt`:
```kotlin
package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tech.beyondscale.mitra.domain.model.SyncStatus

interface SyncController {
    val status: StateFlow<SyncStatus>
    val pendingCount: Flow<Long>
    /** User-facing messages, e.g. a change the server rejected. */
    val messages: Flow<String>
    fun requestSync()
}
```

- [ ] **Step 2: Test doubles in `shared/testing`**

`Fakes.kt`:
```kotlin
package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController

fun testUser(name: String = "Raju Osuri", settings: Settings = Settings(), id: String = "u1") = User(
    id = id, name = name, email = "raju@example.com", username = null, phone = null,
    settings = settings, onboardedAt = null, memberSince = "2026-09-01T00:00:00.000Z",
)

class FakeAuthRepository(initial: SessionState = SessionState.SignedOut) : AuthRepository {
    val sessionFlow = MutableStateFlow(initial)
    override val session: StateFlow<SessionState> = sessionFlow.asStateFlow()
    var result: Outcome<User> = Outcome.Ok(testUser())
    var forgotResult: Outcome<Unit> = Outcome.Ok(Unit)
    val calls = mutableListOf<String>()

    override suspend fun restore() { calls += "restore" }
    override suspend fun refresh(): Outcome<User> { calls += "refresh"; return result }
    override suspend fun login(identifier: String, password: String): Outcome<User> {
        calls += "login:$identifier"
        return result.also { if (it is Outcome.Ok) sessionFlow.value = SessionState.SignedIn(it.value) }
    }
    override suspend fun signup(name: String, email: String, password: String): Outcome<User> {
        calls += "signup:$name:$email"
        return result.also { if (it is Outcome.Ok) sessionFlow.value = SessionState.SignedIn(it.value) }
    }
    override suspend fun forgotPassword(email: String): Outcome<Unit> { calls += "forgot:$email"; return forgotResult }
    override suspend fun logout() { calls += "logout"; sessionFlow.value = SessionState.SignedOut }
}

class FakeSettingsRepository : SettingsRepository {
    val updates = mutableListOf<Modules>()
    override suspend fun updateModules(modules: Modules) { updates += modules }
}

class FakePreferencesRepository : PreferencesRepository {
    val themeFlow = MutableStateFlow(ThemeMode.Dark)
    override val theme = themeFlow
    override suspend fun setTheme(mode: ThemeMode) { themeFlow.value = mode }
}

class FakeSyncController : SyncController {
    val statusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = statusFlow
    val pending = MutableStateFlow(0L)
    override val pendingCount = pending
    val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages = messageFlow
    var requests = 0
    override fun requestSync() { requests++ }
}
```

`FixedClock.kt`:
```kotlin
package tech.beyondscale.mitra.testing

import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppClock
import kotlin.time.Instant

class FixedClock(var instant: Instant, override val zone: TimeZone = TimeZone.UTC) : AppClock {
    override fun now(): Instant = instant
}
```

`ViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Base class for ViewModel tests: viewModelScope runs on Dispatchers.Main, which tests must replace. */
abstract class ViewModelTest {
    @BeforeTest fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun resetMainDispatcher() { Dispatchers.resetMain() }
}
```

- [ ] **Step 3: Write failing use-case tests** — `AuthUseCasesTest.kt`

```kotlin
package tech.beyondscale.mitra.domain.usecase

import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.testing.FakeAuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthUseCasesTest {
    private val repo = FakeAuthRepository()

    @Test fun loginRejectsBlankFieldsWithoutCallingServer() = runTest {
        val r = LoginUseCase(repo)("  ", "")
        val err = assertIs<AppError.Validation>(assertIs<Outcome.Err>(r).error)
        assertEquals(
            listOf(FieldIssue("identifier", "Enter your email or username"), FieldIssue("password", "Enter your password")),
            err.issues,
        )
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun loginTrimsIdentifier() = runTest {
        LoginUseCase(repo)("  raju@example.com ", "secret")
        assertEquals(listOf("login:raju@example.com"), repo.calls)
    }

    @Test fun signupValidatesLikeServer() = runTest {
        val r = SignupUseCase(repo)("", "not-an-email", "short")
        val issues = assertIs<AppError.Validation>(assertIs<Outcome.Err>(r).error).issues
        assertEquals(listOf("name", "email", "password"), issues.map { it.path })
        assertEquals("Password must be at least 8 characters", issues.last().message)
    }

    @Test fun signupNormalisesEmail() = runTest {
        SignupUseCase(repo)(" Raju ", " Raju@Example.COM ", "longenough")
        assertEquals(listOf("signup:Raju:raju@example.com"), repo.calls)
    }

    @Test fun forgotPasswordValidatesEmail() = runTest {
        assertIs<Outcome.Err>(ForgotPasswordUseCase(repo)("nope"))
        assertIs<Outcome.Ok<Unit>>(ForgotPasswordUseCase(repo)("raju@example.com"))
        assertEquals(listOf("forgot:raju@example.com"), repo.calls)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :shared:domain:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'LoginUseCase'`.

- [ ] **Step 5: Implement use cases**

`usecase/AuthValidation.kt`:
```kotlin
package tech.beyondscale.mitra.domain.usecase

import tech.beyondscale.mitra.core.FieldIssue

/** Client-side copy of mitra-backend/src/auth/schemas.ts so the form reacts instantly; the server still validates. */
internal object AuthValidation {
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun login(identifier: String, password: String) = buildList {
        if (identifier.isBlank()) add(FieldIssue("identifier", "Enter your email or username"))
        if (password.isEmpty()) add(FieldIssue("password", "Enter your password"))
    }

    fun signup(name: String, email: String, password: String) = buildList {
        if (name.isBlank()) add(FieldIssue("name", "Enter your name"))
        addAll(email(email))
        if (password.length < 8) add(FieldIssue("password", "Password must be at least 8 characters"))
    }

    fun email(email: String) = buildList {
        if (!EMAIL.matches(email.trim())) add(FieldIssue("email", "Enter a valid email"))
    }
}
```

`usecase/AuthUseCases.kt`:
```kotlin
package tech.beyondscale.mitra.domain.usecase

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository

private fun invalid(issues: List<FieldIssue>) = Outcome.Err(AppError.Validation(issues))

class LoginUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(identifier: String, password: String): Outcome<User> {
        val issues = AuthValidation.login(identifier, password)
        return if (issues.isNotEmpty()) invalid(issues) else repo.login(identifier.trim(), password)
    }
}

class SignupUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(name: String, email: String, password: String): Outcome<User> {
        val issues = AuthValidation.signup(name, email, password)
        return if (issues.isNotEmpty()) invalid(issues) else repo.signup(name.trim(), email.trim().lowercase(), password)
    }
}

class ForgotPasswordUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(email: String): Outcome<Unit> {
        val issues = AuthValidation.email(email)
        return if (issues.isNotEmpty()) invalid(issues) else repo.forgotPassword(email.trim().lowercase())
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :shared:domain:testAndroidHostTest :shared:testing:compileAndroidMain`
Expected: PASS (5 tests). *(`domain`'s tests depend on `testing`, which depends on `domain`'s main code — Gradle resolves this per source set. If Gradle reports a circular dependency, move the `FakeAuthRepository` used here into `shared/domain/src/commonTest` and keep `testing` for the other modules.)*

- [ ] **Step 7: Commit**

```bash
git add mitra-mobile/shared/domain mitra-mobile/shared/testing && git commit -m "feat(mobile): domain models, repository contracts, auth use cases and test doubles

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: data — SQLDelight schema, PrefStore, LocalData, test driver

**Files:**
- Create: `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/{User,Outbox,Pref}.sq`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/{PrefStore,LocalData}.kt`
- Create: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/TestDriver.kt`, `src/androidHostTest/kotlin/tech/beyondscale/mitra/data/TestDriver.android.kt`, `src/iosTest/kotlin/tech/beyondscale/mitra/data/TestDriver.ios.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/local/LocalStoreTest.kt`

**Interfaces:**
- Produces: `MitraDb` with `userQueries`, `outboxQueries`, `prefQueries` (generated); `Outbox` row class (`seq, id, entity, method, path, body, coalesceKey, inFlight, attempts, createdAt`); `class PrefStore(db, io) { get(key): String?; put(key, value); remove(key); observe(key): Flow<String?> }`; `object PrefKeys { THEME, LAST_USER_ID }`; `class LocalData(db) { fun clearUserData() }`; test-only `expect fun testDriver(): SqlDriver`.

- [ ] **Step 1: Schema**

`User.sq`:
```sql
-- The signed-in user exactly as the server returned it (JSON of UserDto).
CREATE TABLE cached_user (
  id TEXT NOT NULL PRIMARY KEY,
  json TEXT NOT NULL
);

selectCurrent:
SELECT json FROM cached_user LIMIT 1;

upsert:
INSERT OR REPLACE INTO cached_user(id, json) VALUES (?, ?);

deleteAll:
DELETE FROM cached_user;
```

`Outbox.sq`:
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
  createdAt INTEGER NOT NULL
);

insert:
INSERT INTO outbox(id, entity, method, path, body, coalesceKey, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?);

selectNext:
SELECT * FROM outbox ORDER BY seq LIMIT 1;

selectAll:
SELECT * FROM outbox ORDER BY seq;

-- Only rows not currently being sent can absorb a newer patch.
pendingByCoalesceKey:
SELECT * FROM outbox WHERE coalesceKey = ? AND inFlight = 0 ORDER BY seq DESC LIMIT 1;

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

`Pref.sq`:
```sql
-- Device-local key/value preferences (theme, last signed-in user id).
CREATE TABLE pref (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

get:
SELECT value FROM pref WHERE key = ?;

put:
INSERT OR REPLACE INTO pref(key, value) VALUES (?, ?);

remove:
DELETE FROM pref WHERE key = ?;
```

- [ ] **Step 2: Test driver (expect/actual)**

`commonTest/.../data/TestDriver.kt`:
```kotlin
package tech.beyondscale.mitra.data

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory database with the schema created. */
expect fun testDriver(): SqlDriver
```

`androidHostTest/.../data/TestDriver.android.kt`:
```kotlin
package tech.beyondscale.mitra.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tech.beyondscale.mitra.data.db.MitraDb

actual fun testDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MitraDb.Schema.create(it) }
```

`iosTest/.../data/TestDriver.ios.kt`:
```kotlin
package tech.beyondscale.mitra.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import tech.beyondscale.mitra.data.db.MitraDb

actual fun testDriver(): SqlDriver = inMemoryDriver(MitraDb.Schema)
```

- [ ] **Step 3: Write failing test** — `local/LocalStoreTest.kt`

```kotlin
package tech.beyondscale.mitra.data.local

import app.cash.turbine.test
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.data.db.MitraDb
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
        db.outboxQueries.insert("o1", "user-settings", "PATCH", "users/me/settings", "{}", null, 0)

        LocalData(db).clearUserData()

        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertEquals(0L, db.outboxQueries.count().executeAsOne())
        assertEquals("Light", prefs.get(PrefKeys.THEME))
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'PrefStore'`.

- [ ] **Step 5: Implement**

`local/PrefStore.kt`:
```kotlin
package tech.beyondscale.mitra.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.data.db.MitraDb

object PrefKeys {
    const val THEME = "theme"
    const val LAST_USER_ID = "lastUserId"
}

/** Synchronous key/value access; callers on the main thread wrap calls in withContext(io). */
class PrefStore(private val db: MitraDb, private val io: CoroutineDispatcher) {
    fun get(key: String): String? = db.prefQueries.get(key).executeAsOneOrNull()
    fun put(key: String, value: String) = db.prefQueries.put(key, value)
    fun remove(key: String) = db.prefQueries.remove(key)
    fun observe(key: String): Flow<String?> = db.prefQueries.get(key).asFlow().mapToOneOrNull(io)
}
```

`local/LocalData.kt`:
```kotlin
package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.MitraDb

class LocalData(private val db: MitraDb) {
    /** Removes everything that belongs to the signed-in account. Each later module adds its tables here. */
    fun clearUserData() = db.transaction {
        db.outboxQueries.deleteAll()
        db.userQueries.deleteAll()
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add mitra-mobile/shared/data && git commit -m "feat(mobile): SQLDelight schema for user cache, outbox and prefs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: data — API client, DTOs, encrypted-store cookie persistence

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/remote/{ApiClient,Dtos,SecureStore,PersistentCookiesStorage}.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/UserCache.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/remote/{ApiClientTest,PersistentCookiesStorageTest,UserDtoTest,InMemorySecureStore,MockServer}.kt`

**Interfaces:**
- Consumes: `Outcome`, `AppError`, `AppClock`, `User`, `Settings`, `Modules` (Tasks 2–3); `MitraDb` (Task 4).
- Produces:
  - `class ApiClient(engine: HttpClientEngine, baseUrl: String, cookies: CookiesStorage)` with `val json: Json`, `val unauthorized: SharedFlow<Unit>`, `suspend fun send(method: HttpMethod, path: String, body: JsonElement? = null): Outcome<JsonElement?>`, `suspend inline fun <reified T> call(method, path, body = null): Outcome<T>`, `companion fun toAppError(status: Int, element: JsonElement?): AppError`
  - `@Serializable data class UserDto(...)`, `UserEnvelope(user: UserDto)`, `fun UserDto.toDomain(json: Json): User`, `fun parseSettings(json: Json, obj: JsonObject): Settings`
  - `interface SecureStore { fun get(key: String): String?; fun put(key: String, value: String?) }`
  - `class PersistentCookiesStorage(store: SecureStore, clock: AppClock) : CookiesStorage` with `suspend fun clear()`
  - `class UserCache(db, json, io) { fun observe(): Flow<User?>; fun current(): UserDto?; fun put(dto: UserDto) }`

- [ ] **Step 1: Test helpers**

`InMemorySecureStore.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

class InMemorySecureStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}
```

`MockServer.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

const val TEST_BASE = "http://test.local/api/"

fun userJson(id: String = "u1", name: String = "Raju Osuri", settings: String = "{}") =
    """{"user":{"id":"$id","name":"$name","email":"raju@example.com","username":null,"phone":null,"settings":$settings,"onboardedAt":null,"deletedAt":null,"memberSince":"2026-09-01T00:00:00.000Z"}}"""

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), *extra.map { it.first to listOf(it.second) }.toTypedArray()))

fun errorJson(code: String, message: String) = """{"error":{"code":"$code","message":"$message"}}"""

/** Routes requests by "METHOD /path"; `offline = true` makes every call throw like a dead network. */
class MockServer {
    var offline = false
    val requests = mutableListOf<HttpRequestData>()
    val routes = mutableMapOf<String, MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val engine = MockEngine { req ->
        if (offline) throw Exception("network down")
        requests += req
        val handler = routes["${req.method.value} ${req.url.encodedPath}"]
        handler?.invoke(this, req) ?: respond("", HttpStatusCode.NotFound)
    }
    val log: List<String> get() = requests.map { "${it.method.value} ${it.url.encodedPath}" }
}
```

- [ ] **Step 2: Write failing tests**

`ApiClientTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import app.cash.turbine.test
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiClientTest {
    private val server = MockServer()
    private val cookies = PersistentCookiesStorage(InMemorySecureStore(), FixedClock(Instant.parse("2026-10-13T04:30:00Z")))
    private val api = ApiClient(server.engine, TEST_BASE, cookies)

    @Test fun decodesSuccessAndSendsJsonBody() = runTest {
        server.routes["POST /api/auth/login"] = { req ->
            assertEquals("""{"identifier":"raju","password":"pw"}""", String(req.body.toByteArray()))
            json(userJson())
        }
        val r = api.call<UserEnvelope>(HttpMethod.Post, "auth/login", buildJsonObject { put("identifier", "raju"); put("password", "pw") })
        assertEquals("Raju Osuri", assertIs<Outcome.Ok<UserEnvelope>>(r).value.user.name)
    }

    @Test fun sessionCookieIsStoredAndSentBack() = runTest {
        server.routes["POST /api/auth/login"] = { json(userJson(), HttpStatusCode.OK, HttpHeaders.SetCookie to "mitra_sid=abc.sig%2B1; Path=/; HttpOnly; SameSite=Lax") }
        server.routes["GET /api/auth/me"] = { req ->
            assertTrue(req.headers[HttpHeaders.Cookie].orEmpty().contains("mitra_sid=abc.sig%2B1"), "cookie header was ${req.headers[HttpHeaders.Cookie]}")
            json(userJson())
        }
        api.send(HttpMethod.Post, "auth/login", buildJsonObject { })
        assertIs<Outcome.Ok<*>>(api.send(HttpMethod.Get, "auth/me"))
    }

    @Test fun badCredentialsIsUnauthenticatedButDoesNotSignOut() = runTest {
        server.routes["POST /api/auth/login"] = { json(errorJson("BAD_CREDENTIALS", "Email/username or password is incorrect"), HttpStatusCode.Unauthorized) }
        api.unauthorized.test {
            val r = api.send(HttpMethod.Post, "auth/login", buildJsonObject { })
            assertEquals(AppError.Unauthenticated("BAD_CREDENTIALS", "Email/username or password is incorrect"), assertIs<Outcome.Err>(r).error)
            expectNoEvents()
        }
    }

    @Test fun expiredSessionBroadcastsUnauthorized() = runTest {
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        api.unauthorized.test {
            api.send(HttpMethod.Get, "auth/me")
            awaitItem()
        }
    }

    @Test fun validationIssuesAreMapped() = runTest {
        server.routes["POST /api/auth/signup"] = {
            json("""{"error":{"code":"BAD_REQUEST","message":"Enter a valid email","issues":[{"path":"email","message":"Enter a valid email"}]}}""", HttpStatusCode.BadRequest)
        }
        val err = assertIs<Outcome.Err>(api.send(HttpMethod.Post, "auth/signup", buildJsonObject { })).error
        assertEquals(AppError.Validation(listOf(FieldIssue("email", "Enter a valid email")), "Enter a valid email"), err)
    }

    @Test fun serverAndNetworkFailures() = runTest {
        server.routes["GET /api/tasks/bootstrap"] = { json(errorJson("INTERNAL", "Something went wrong"), HttpStatusCode.InternalServerError) }
        assertEquals(AppError.Server(500, "INTERNAL", "Something went wrong"), assertIs<Outcome.Err>(api.send(HttpMethod.Get, "tasks/bootstrap")).error)
        server.offline = true
        assertEquals(AppError.Offline, assertIs<Outcome.Err>(api.send(HttpMethod.Get, "tasks/bootstrap")).error)
    }

    @Test fun nonJsonErrorBodyStillMaps() {
        assertEquals(AppError.Server(502, "ERROR", "Request failed (502)"), ApiClient.toAppError(502, null))
    }
}
```

`PersistentCookiesStorageTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistentCookiesStorageTest {
    private val url = Url(TEST_BASE)
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val store = InMemorySecureStore()

    @Test fun survivesProcessRestart() = runTest {
        PersistentCookiesStorage(store, clock).addCookie(url, Cookie("mitra_sid", "abc"))
        val reloaded = PersistentCookiesStorage(store, clock).get(url)
        assertEquals(listOf("mitra_sid" to "abc"), reloaded.map { it.name to it.value })
    }

    @Test fun expiredOrClearedCookieIsRemoved() = runTest {
        val jar = PersistentCookiesStorage(store, clock)
        jar.addCookie(url, Cookie("mitra_sid", "abc"))
        jar.addCookie(url, Cookie("mitra_sid", "", expires = GMTDate(0)))
        assertTrue(jar.get(url).isEmpty())
    }

    @Test fun cookieWithMaxAgeExpires() = runTest {
        val jar = PersistentCookiesStorage(store, clock)
        jar.addCookie(url, Cookie("mitra_sid", "abc", maxAge = 60))
        clock.instant = Instant.parse("2026-10-13T04:31:01Z")
        assertTrue(jar.get(url).isEmpty())
    }

    @Test fun corruptStoredValueIsIgnored() = runTest {
        store.put("cookies", "{not json")
        assertTrue(PersistentCookiesStorage(store, clock).get(url).isEmpty())
    }

    @Test fun clearRemovesEverything() = runTest {
        val jar = PersistentCookiesStorage(store, clock)
        jar.addCookie(url, Cookie("mitra_sid", "abc"))
        jar.clear()
        assertTrue(PersistentCookiesStorage(store, clock).get(url).isEmpty())
    }
}
```

`UserDtoTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class UserDtoTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test fun mapsServerUserAndMergesSettingsOverDefaults() {
        val env = json.decodeFromString<UserEnvelope>(userJson(settings = """{"modules":{"money":false,"wellness":true,"ai":true},"focusMinutes":50,"unknownKey":1}"""))
        val user = env.user.toDomain(json)
        assertEquals("Raju", user.firstName)
        assertEquals("RO", user.initials)
        assertEquals(Modules(money = false), user.settings.modules)
        assertEquals(50, user.settings.focusMinutes)
        assertEquals(Settings.DEFAULT_TODAY_SECTIONS, user.settings.todaySections)
    }

    @Test fun wrongTypedSettingsFallBackToDefaults() {
        val obj = json.parseToJsonElement("""{"modules":"yes","focusMinutes":"lots"}""").jsonObject
        assertEquals(Settings(), parseSettings(json, obj))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'ApiClient'`.

- [ ] **Step 4: Implement**

`remote/SecureStore.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

/** Small encrypted key/value store for secrets (session cookie). Android: Keystore-backed; iOS later: Keychain. */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
```

`remote/PersistentCookiesStorage.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.core.AppClock

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long? = null,
    val path: String? = null,
    val domain: String? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
)

/**
 * Keeps the backend's session cookie across app restarts. We talk to a single API host,
 * so every stored cookie is sent with every request.
 */
class PersistentCookiesStorage(private val store: SecureStore, private val clock: AppClock) : CookiesStorage {
    private val mutex = Mutex()
    private var cache: MutableMap<String, StoredCookie>? = null

    private fun cookies(): MutableMap<String, StoredCookie> = cache ?: run {
        val saved = store.get(KEY)?.let { runCatching { Json.decodeFromString<List<StoredCookie>>(it) }.getOrNull() }.orEmpty()
        saved.associateBy { it.name }.toMutableMap().also { cache = it }
    }

    private fun persist() = store.put(KEY, Json.encodeToString(cookies().values.toList()))

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val jar = cookies()
        if (jar.values.removeAll { it.expiresAt != null && it.expiresAt <= now }) persist()
        jar.values.map { Cookie(it.name, it.value, path = it.path, domain = it.domain, secure = it.secure, httpOnly = it.httpOnly) }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie): Unit = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val expiresAt = cookie.maxAge?.takeIf { it > 0 }?.let { now + it * 1000L } ?: cookie.expires?.timestamp
        val jar = cookies()
        if (cookie.value.isEmpty() || (expiresAt != null && expiresAt <= now)) {
            jar.remove(cookie.name)
        } else {
            jar[cookie.name] = StoredCookie(cookie.name, cookie.value, expiresAt, cookie.path, cookie.domain, cookie.secure, cookie.httpOnly)
        }
        persist()
    }

    suspend fun clear() = mutex.withLock { cookies().clear(); persist() }

    override fun close() {}

    private companion object { const val KEY = "cookies" }
}
```
*(If `cookie.maxAge` is a non-null `Int` in this Ktor version the `?.` only produces a warning — leave it.)*

`remote/Dtos.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.User

/** Shape of `toPublic()` in mitra-backend/src/users/users.service.ts. */
@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val username: String? = null,
    val phone: String? = null,
    val settings: JsonObject = JsonObject(emptyMap()),
    val onboardedAt: String? = null,
    val memberSince: String = "",
)

@Serializable
data class UserEnvelope(val user: UserDto)

@Serializable
private data class ModulesDto(val money: Boolean = true, val wellness: Boolean = true, val ai: Boolean = true)

@Serializable
private data class SettingsDto(
    val reduceMotion: Boolean = false,
    val compact: Boolean = false,
    val modules: ModulesDto = ModulesDto(),
    val todaySections: List<String> = Settings.DEFAULT_TODAY_SECTIONS,
    val weekStart: String = "Monday",
    val dayStart: String = "06:00",
    val quietHours: Boolean = true,
    val digest: Boolean = true,
    val autoSave: String = "Small only",
    val shareWellness: Boolean = false,
    val focusMinutes: Int = 25,
)

/** The settings document is free-form JSONB on the server; anything malformed falls back to defaults. */
fun parseSettings(json: Json, obj: JsonObject): Settings = runCatching {
    val s = json.decodeFromJsonElement(SettingsDto.serializer(), obj)
    Settings(
        reduceMotion = s.reduceMotion, compact = s.compact,
        modules = Modules(s.modules.money, s.modules.wellness, s.modules.ai),
        todaySections = s.todaySections, weekStart = s.weekStart, dayStart = s.dayStart,
        quietHours = s.quietHours, digest = s.digest, autoSave = s.autoSave,
        shareWellness = s.shareWellness, focusMinutes = s.focusMinutes,
    )
}.getOrDefault(Settings())

fun UserDto.toDomain(json: Json): User = User(
    id = id, name = name, email = email, username = username, phone = phone,
    settings = parseSettings(json, settings), onboardedAt = onboardedAt, memberSince = memberSince,
)
```

`remote/ApiClient.kt`:
```kotlin
package tech.beyondscale.mitra.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome

/** Thin Ktor wrapper: every failure becomes an [AppError], mirroring mitra-frontend/src/lib/api.ts. */
class ApiClient(engine: HttpClientEngine, baseUrl: String, cookies: CookiesStorage) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val base = baseUrl.trimEnd('/') + "/"
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the server says the session is gone (not on a wrong password at login). */
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpCookies) { storage = cookies }
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000 }
    }

    /** [path] is relative to the API base with no leading slash, e.g. "auth/login". */
    suspend fun send(method: HttpMethod, path: String, body: JsonElement? = null): Outcome<JsonElement?> {
        val response = try {
            http.request(base + path) {
                this.method = method
                if (body != null) setBody(TextContent(json.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Err(AppError.Offline)
        }
        val text = response.bodyAsText()
        val element = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (response.status.isSuccess()) return Outcome.Ok(element)
        val error = toAppError(response.status.value, element)
        if (error is AppError.Unauthenticated && error.code == "UNAUTHENTICATED") _unauthorized.tryEmit(Unit)
        return Outcome.Err(error)
    }

    suspend inline fun <reified T> call(method: HttpMethod, path: String, body: JsonElement? = null): Outcome<T> =
        when (val r = send(method, path, body)) {
            is Outcome.Err -> r
            is Outcome.Ok -> try {
                Outcome.Ok(json.decodeFromJsonElement<T>(r.value ?: JsonNull))
            } catch (e: IllegalArgumentException) { // includes SerializationException
                Outcome.Err(AppError.Unknown(e))
            }
        }

    companion object {
        /** Parses `{ error: { code, message, issues? } }` (mitra-backend/src/common/http-exception.filter.ts). */
        fun toAppError(status: Int, element: JsonElement?): AppError {
            val err = (element as? JsonObject)?.get("error") as? JsonObject
            val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull ?: "ERROR"
            val message = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Request failed ($status)"
            val issues = (err?.get("issues") as? JsonArray)?.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                FieldIssue(
                    (o["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (o["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            return when {
                status == 401 -> AppError.Unauthenticated(code, message)
                status == 400 && !issues.isNullOrEmpty() -> AppError.Validation(issues, message)
                else -> AppError.Server(status, code, message)
            }
        }
    }
}
```

`local/UserCache.kt`:
```kotlin
package tech.beyondscale.mitra.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.domain.model.User

class UserCache(private val db: MitraDb, private val json: Json, private val io: CoroutineDispatcher) {
    fun observe(): Flow<User?> = db.userQueries.selectCurrent().asFlow().mapToOneOrNull(io)
        .map { raw -> raw?.let { json.decodeFromString<UserDto>(it).toDomain(json) } }

    fun current(): UserDto? = db.userQueries.selectCurrent().executeAsOneOrNull()?.let { json.decodeFromString<UserDto>(it) }

    fun put(dto: UserDto) = db.userQueries.upsert(dto.id, json.encodeToString(UserDto.serializer(), dto))
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: PASS (all of ApiClientTest, PersistentCookiesStorageTest, UserDtoTest, LocalStoreTest). If `sessionCookieIsStoredAndSentBack` fails only because the cookie value's encoding differs (e.g. `abc.sig+1`), print the header, and make storage round-trip the value byte-for-byte by storing and restoring `cookie.encoding` too — the server must receive exactly what it sent.

- [ ] **Step 6: Commit**

```bash
git add mitra-mobile/shared/data && git commit -m "feat(mobile): API client with typed errors, user DTOs, persistent session cookies

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: data — outbox writer and SyncEngine

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/sync/{OutboxWriter,SyncEngine}.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/sync/{OutboxWriterTest,SyncEngineTest}.kt`

**Interfaces:**
- Consumes: `ApiClient`, `MitraDb`, `AppClock`, `newId`, `SyncController`, `SyncStatus`.
- Produces:
  - `class OutboxWriter(db, json, clock) { fun enqueue(entity: String, method: String, path: String, body: JsonObject?, coalesceKey: String? = null) }` — call inside `db.transaction {}` together with the local write
  - `fun interface SyncScheduler { fun scheduleRetry() }`
  - `interface SyncHandler { suspend fun onSuccess(response: JsonElement?); suspend fun onRejected(error: AppError) }`
  - `class SyncEngine(api, db, scheduler, scope, io = Dispatchers.IO, debounceMs = 500) : SyncController` with `fun register(entity: String, handler: SyncHandler)`, `suspend fun drain(): SyncEngine.Result`, `enum class Result { Drained, Offline, Retry, SignedOut }`

- [ ] **Step 1: Write failing tests**

`OutboxWriterTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OutboxWriterTest {
    private val db = MitraDb(testDriver())
    private val writer = OutboxWriter(db, Json, FixedClock(Instant.parse("2026-10-13T04:30:00Z")))

    @Test fun coalescesPatchesWithSameKeyShallowly() {
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("compact", true); put("digest", true) }, "user-settings")
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("digest", false) }, "user-settings")
        val rows = db.outboxQueries.selectAll().executeAsList()
        assertEquals(1, rows.size)
        assertEquals("""{"compact":true,"digest":false}""", rows.single().body)
    }

    @Test fun doesNotMergeIntoRowAlreadyInFlight() {
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("digest", true) }, "user-settings")
        db.outboxQueries.markInFlight(db.outboxQueries.selectNext().executeAsOne().id)
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("digest", false) }, "user-settings")
        assertEquals(listOf("""{"digest":true}""", """{"digest":false}"""), db.outboxQueries.selectAll().executeAsList().map { it.body })
    }

    @Test fun keepsInsertionOrderWithoutKey() {
        writer.enqueue("a", "POST", "x", null)
        writer.enqueue("b", "DELETE", "y", null)
        assertEquals(listOf("a", "b"), db.outboxQueries.selectAll().executeAsList().map { it.entity })
    }
}
```

`SyncEngineTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.sync

import app.cash.turbine.test
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SyncEngineTest {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)
    private var retries = 0
    private val successes = mutableListOf<JsonElement?>()
    private val rejections = mutableListOf<AppError>()

    private fun TestScope.engine() = SyncEngine(api, db, { retries++ }, backgroundScope, UnconfinedTestDispatcher(testScheduler), debounceMs = 500).also {
        it.register("t", object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) { successes += response }
            override suspend fun onRejected(error: AppError) { rejections += error }
        })
    }

    private fun enqueue(path: String, body: String = "x") =
        writer.enqueue("t", "PATCH", path, buildJsonObject { put("v", body) })

    private fun pending() = db.outboxQueries.selectAll().executeAsList()

    @Test fun drainsInOrderAndCallsHandler() = runTest {
        server.routes["PATCH /api/a"] = { req -> assertEquals("""{"v":"1"}""", String(req.body.toByteArray())); json("""{"ok":1}""") }
        server.routes["PATCH /api/b"] = { json("""{"ok":2}""") }
        enqueue("a", "1"); enqueue("b", "2")
        val engine = engine()
        assertEquals(SyncEngine.Result.Drained, engine.drain())
        assertEquals(listOf("PATCH /api/a", "PATCH /api/b"), server.log)
        assertEquals(2, successes.size)
        assertEquals(emptyList(), pending())
        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test fun offlineKeepsRowAndSchedulesRetry() = runTest {
        enqueue("a"); server.offline = true
        val engine = engine()
        assertEquals(SyncEngine.Result.Offline, engine.drain())
        assertEquals(1, pending().size)
        assertEquals(0L, pending().single().inFlight)
        assertEquals(SyncStatus.Offline(1), engine.status.value)
        assertEquals(1, retries)
    }

    @Test fun serverErrorRetriesLater() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("INTERNAL", "boom"), HttpStatusCode.InternalServerError) }
        enqueue("a")
        val engine = engine()
        assertEquals(SyncEngine.Result.Retry, engine.drain())
        assertEquals(SyncStatus.Retrying(1), engine.status.value)
        assertEquals(1, pending().size)
        assertEquals(1, retries)
    }

    @Test fun expiredSessionStopsButKeepsQueue() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        enqueue("a")
        assertEquals(SyncEngine.Result.SignedOut, engine().drain())
        assertEquals(1, pending().size)
        assertEquals(0, retries)
    }

    @Test fun rejectedWriteIsDroppedReportedAndQueueContinues() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        server.routes["PATCH /api/b"] = { json("{}") }
        enqueue("a"); enqueue("b")
        val engine = engine()
        engine.messages.test {
            assertEquals(SyncEngine.Result.Drained, engine.drain())
            assertEquals("Couldn't save · Task not found", awaitItem())
        }
        assertEquals(listOf(AppError.Server(404, "NOT_FOUND", "Task not found")), rejections)
        assertEquals(listOf("PATCH /api/a", "PATCH /api/b"), server.log)
        assertEquals(emptyList(), pending())
    }

    @Test fun requestSyncIsDebounced() = runTest {
        server.routes["PATCH /api/a"] = { json("{}") }
        val engine = engine()
        runCurrent()
        enqueue("a")
        repeat(3) { engine.requestSync() }
        advanceTimeBy(499); runCurrent()
        assertEquals(emptyList(), server.log)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("PATCH /api/a"), server.log)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'OutboxWriter'`.

- [ ] **Step 3: Implement**

`sync/OutboxWriter.kt`:
```kotlin
package tech.beyondscale.mitra.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.data.db.MitraDb

class OutboxWriter(private val db: MitraDb, private val json: Json, private val clock: AppClock) {
    /**
     * Queues a server write. Call inside the same `db.transaction {}` as the local change it mirrors.
     * With a [coalesceKey], a newer patch is shallow-merged into a queued (not in-flight) one —
     * the server merges settings/patches shallowly too, so the result is identical.
     */
    fun enqueue(entity: String, method: String, path: String, body: JsonObject?, coalesceKey: String? = null) {
        if (coalesceKey != null) {
            val queued = db.outboxQueries.pendingByCoalesceKey(coalesceKey).executeAsOneOrNull()
            if (queued != null) {
                val old = queued.body?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
                val merged = JsonObject(old + (body ?: JsonObject(emptyMap())))
                db.outboxQueries.updateBody(json.encodeToString(JsonObject.serializer(), merged), queued.id)
                return
            }
        }
        db.outboxQueries.insert(
            id = newId(), entity = entity, method = method, path = path,
            body = body?.let { json.encodeToString(JsonObject.serializer(), it) },
            coalesceKey = coalesceKey, createdAt = clock.now().toEpochMilliseconds(),
        )
    }
}
```

`sync/SyncEngine.kt`:
```kotlin
package tech.beyondscale.mitra.data.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
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

/** Per-entity reaction to a sent outbox row. */
interface SyncHandler {
    suspend fun onSuccess(response: JsonElement?)
    /** The server refused the write (4xx); the local copy should be restored from the server. */
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
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val messages: Flow<String> = _messages
    override val pendingCount: Flow<Long> = db.outboxQueries.count().asFlow().mapToOne(io)

    init {
        scope.launch { requests.debounce(debounceMs).collect { drain() } }
    }

    fun register(entity: String, handler: SyncHandler) { handlers[entity] = handler }

    override fun requestSync() { requests.tryEmit(Unit) }

    suspend fun drain(): Result = mutex.withLock { drainLocked() }

    private suspend fun drainLocked(): Result {
        var result: Result? = null
        while (result == null) {
            val entry = withContext(io) { db.outboxQueries.selectNext().executeAsOneOrNull() }
            if (entry == null) {
                _status.value = SyncStatus.Idle
                result = Result.Drained
                continue
            }
            val pending = withContext(io) { db.outboxQueries.count().executeAsOne() }
            _status.value = SyncStatus.Syncing(pending)
            withContext(io) { db.outboxQueries.markInFlight(entry.id) }

            val body = entry.body?.let { api.json.parseToJsonElement(it) }
            when (val r = api.send(HttpMethod.parse(entry.method), entry.path, body)) {
                is Outcome.Ok -> {
                    withContext(io) { db.outboxQueries.delete(entry.id) }
                    handlers[entry.entity]?.onSuccess(r.value)
                }
                is Outcome.Err -> {
                    val e = r.error
                    val transient = e is AppError.Offline || (e is AppError.Server && (e.status >= 500 || e.status == 429))
                    if (transient || e is AppError.Unauthenticated) {
                        withContext(io) { db.outboxQueries.clearInFlight(entry.id) }
                        if (transient) scheduler.scheduleRetry()
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
                        withContext(io) { db.outboxQueries.delete(entry.id) }
                        handlers[entry.entity]?.onRejected(e)
                        _messages.tryEmit("Couldn't save · ${e.message}")
                    }
                }
            }
        }
        return result
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: PASS (OutboxWriterTest 3, SyncEngineTest 6, plus earlier tests).

- [ ] **Step 5: Commit**

```bash
git add mitra-mobile/shared/data && git commit -m "feat(mobile): offline outbox with coalescing and ordered SyncEngine

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: data — repositories and Koin data module

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/{AuthRepositoryImpl,SettingsRepositoryImpl,PreferencesRepositoryImpl}.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/di/DataModule.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/repository/{AuthRepositoryImplTest,SettingsRepositoryImplTest}.kt`

**Interfaces:**
- Consumes: everything from Tasks 4–6; domain interfaces from Task 3.
- Produces:
  - `AuthRepositoryImpl(api, db, cache, prefs, localData, cookies, sync: SyncController, scope, io)`
  - `SettingsRepositoryImpl(db, cache, outbox: OutboxWriter, engine: SyncEngine, auth: AuthRepository, json, io)`; `SettingsRepositoryImpl.ENTITY = "user-settings"`
  - `PreferencesRepositoryImpl(prefs, io)`
  - `class AppScope { val scope: CoroutineScope }`; `fun dataModule(baseUrl: String): Module` — expects the platform to provide `SqlDriver`, `SecureStore`, `HttpClientEngine`, `SyncScheduler`

- [ ] **Step 1: Write failing tests**

`AuthRepositoryImplTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.repository

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.http.Cookie
import io.ktor.http.Url
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.remote.userJson
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FakeSyncController
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AuthRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val secure = InMemorySecureStore()
    private val cookies = PersistentCookiesStorage(secure, clock)
    private val api = ApiClient(server.engine, TEST_BASE, cookies)
    private val sync = FakeSyncController()
    private val writer = OutboxWriter(db, api.json, clock)

    private fun TestScope.repo(): AuthRepositoryImpl {
        val io = UnconfinedTestDispatcher(testScheduler)
        return AuthRepositoryImpl(api, db, UserCache(db, api.json, io), PrefStore(db, io), LocalData(db), cookies, sync, backgroundScope, io)
    }
    private fun seedCachedUser(id: String = "u1") {
        db.userQueries.upsert(id, api.json.encodeToString(UserDto.serializer(), UserDto(id, "Raju Osuri", "raju@example.com")))
        db.prefQueries.put(PrefKeys.LAST_USER_ID, id)
    }
    private fun queueOne() = writer.enqueue("t", "PATCH", "x", buildJsonObject { put("v", 1) })
    private fun outboxSize() = db.outboxQueries.count().executeAsOne()

    @Test fun loginCachesUserAndSignsIn() = runTest {
        server.routes["POST /api/auth/login"] = { json(userJson("u1")) }
        val repo = repo()
        assertIs<Outcome.Ok<*>>(repo.login("raju", "pw"))
        runCurrent()
        assertEquals("Raju Osuri", assertIs<SessionState.SignedIn>(repo.session.value).user.name)
        assertEquals("u1", db.prefQueries.get(PrefKeys.LAST_USER_ID).executeAsOneOrNull())
        assertEquals(1, sync.requests)
    }

    @Test fun wrongPasswordLeavesSessionSignedOut() = runTest {
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        server.routes["POST /api/auth/login"] = { json(errorJson("BAD_CREDENTIALS", "Email/username or password is incorrect"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        repo.restore(); runCurrent()
        val err = assertIs<Outcome.Err>(repo.login("raju", "nope")).error
        assertEquals("Email/username or password is incorrect", err.message)
        assertEquals(SessionState.SignedOut, repo.session.value)
    }

    @Test fun sameUserReloginKeepsQueuedChanges() = runTest {
        seedCachedUser("u1"); queueOne()
        server.routes["POST /api/auth/login"] = { json(userJson("u1")) }
        repo().login("raju", "pw")
        assertEquals(1L, outboxSize())
    }

    @Test fun differentUserLoginWipesPreviousData() = runTest {
        seedCachedUser("u1"); queueOne()
        server.routes["POST /api/auth/login"] = { json(userJson("u2", name = "Someone Else")) }
        val repo = repo()
        repo.login("other", "pw"); runCurrent()
        assertEquals(0L, outboxSize())
        assertEquals("Someone Else", assertIs<SessionState.SignedIn>(repo.session.value).user.name)
    }

    @Test fun restoreShowsCachedUserEvenOffline() = runTest {
        seedCachedUser(); server.offline = true
        val repo = repo()
        repo.restore(); runCurrent()
        assertIs<SessionState.SignedIn>(repo.session.value)
    }

    @Test fun restoreWithoutCacheAndNoSessionSignsOut() = runTest {
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        repo.restore(); runCurrent()
        assertEquals(SessionState.SignedOut, repo.session.value)
    }

    @Test fun restoreResetsInFlightRows() = runTest {
        seedCachedUser(); queueOne(); server.offline = true
        db.outboxQueries.markInFlight(db.outboxQueries.selectNext().executeAsOne().id)
        repo().restore()
        assertEquals(0L, db.outboxQueries.selectNext().executeAsOne().inFlight)
    }

    @Test fun expiredSessionSignsOutButKeepsLocalData() = runTest {
        seedCachedUser(); queueOne()
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        runCurrent()
        repo.restore(); runCurrent()
        assertEquals(SessionState.SignedOut, repo.session.value)
        assertEquals(1L, outboxSize())
        assertTrue(db.userQueries.selectCurrent().executeAsOneOrNull() != null)
    }

    @Test fun logoutWorksOfflineAndClearsEverything() = runTest {
        seedCachedUser(); queueOne()
        cookies.addCookie(Url(TEST_BASE), Cookie("mitra_sid", "abc"))
        server.offline = true
        val repo = repo()
        repo.restore(); runCurrent()
        repo.logout(); runCurrent()
        assertEquals(SessionState.SignedOut, repo.session.value)
        assertEquals(0L, outboxSize())
        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertTrue(cookies.get(Url(TEST_BASE)).isEmpty())
    }

    @Test fun forgotPasswordPostsEmail() = runTest {
        server.routes["POST /api/auth/forgot-password"] = { json("""{"ok":true}""") }
        assertEquals(Outcome.Ok(Unit), repo().forgotPassword("raju@example.com"))
    }
}
```

`SettingsRepositoryImplTest.kt`:
```kotlin
package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.remote.userJson
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class SettingsRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val cookies = PersistentCookiesStorage(InMemorySecureStore(), clock)
    private val api = ApiClient(server.engine, TEST_BASE, cookies)

    private class Env(val auth: AuthRepositoryImpl, val settings: SettingsRepositoryImpl, val engine: SyncEngine)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val cache = UserCache(db, api.json, io)
        val engine = SyncEngine(api, db, {}, backgroundScope, io)
        val auth = AuthRepositoryImpl(api, db, cache, PrefStore(db, io), LocalData(db), cookies, engine, backgroundScope, io)
        val settings = SettingsRepositoryImpl(db, cache, OutboxWriter(db, api.json, clock), engine, auth, api.json, io)
        db.userQueries.upsert("u1", api.json.encodeToString(UserDto.serializer(), UserDto("u1", "Raju Osuri", "raju@example.com")))
        db.prefQueries.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(auth, settings, engine)
    }

    @Test fun toggleAppliesLocallyAndPatchesServer() = runTest {
        server.routes["PATCH /api/users/me/settings"] = { req ->
            assertEquals("""{"modules":{"money":false,"wellness":true,"ai":true}}""", String(req.body.toByteArray()))
            json(userJson(settings = """{"modules":{"money":false,"wellness":true,"ai":true}}"""))
        }
        server.offline = true
        val e = env()
        e.auth.restore(); runCurrent()
        e.settings.updateModules(Modules(money = false)); runCurrent()
        assertEquals(Modules(money = false), assertIs<SessionState.SignedIn>(e.auth.session.value).user.settings.modules)

        server.offline = false
        e.engine.drain(); runCurrent()
        assertEquals(listOf("PATCH /api/users/me/settings"), server.log.filter { it.startsWith("PATCH") })
    }

    @Test fun serverEchoDoesNotOverwriteNewerLocalChange() = runTest {
        val e = env()
        var reentered = false
        server.routes["PATCH /api/users/me/settings"] = { req ->
            val sent = String(req.body.toByteArray())
            // While the first PATCH is on the wire, the user flips AI off too.
            if (!reentered) { reentered = true; e.settings.updateModules(Modules(money = false, ai = false)) }
            json(userJson(settings = sent)) // the server echoes the merged settings it now holds
        }
        server.offline = true
        e.auth.restore(); runCurrent()
        e.settings.updateModules(Modules(money = false))
        server.offline = false
        e.engine.drain(); runCurrent()

        val modules = assertIs<SessionState.SignedIn>(e.auth.session.value).user.settings.modules
        assertEquals(Modules(money = false, ai = false), modules)
        assertEquals(2, server.log.count { it == "PATCH /api/users/me/settings" })
    }
}
```
*(The second test's handler re-enters `updateModules` from inside the mock engine; the row being sent is `inFlight = 1`, so the new change is queued as a second row and sent in the same `drain()`.)*

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'AuthRepositoryImpl'`.

- [ ] **Step 3: Implement**

`repository/AuthRepositoryImpl.kt`:
```kotlin
package tech.beyondscale.mitra.data.repository

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.map
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.UserEnvelope
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.SyncController

class AuthRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val cache: UserCache,
    private val prefs: PrefStore,
    private val localData: LocalData,
    private val cookies: PersistentCookiesStorage,
    private val sync: SyncController,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AuthRepository {
    private enum class Auth { Unknown, SignedOut, SignedIn }

    private val auth = MutableStateFlow(Auth.Unknown)

    override val session: StateFlow<SessionState> = combine(auth, cache.observe()) { a, user ->
        when {
            a == Auth.SignedIn && user != null -> SessionState.SignedIn(user)
            a == Auth.SignedOut -> SessionState.SignedOut
            else -> SessionState.Unknown
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Unknown)

    init {
        // Session gone server-side: back to Login, but keep local data + queued changes for the same user.
        scope.launch { api.unauthorized.collect { auth.value = Auth.SignedOut } }
    }

    override suspend fun restore() {
        val cached = withContext(io) {
            db.outboxQueries.resetInFlight() // the app may have been killed mid-send
            cache.current()
        }
        if (cached != null) {
            auth.value = Auth.SignedIn
            sync.requestSync()
            scope.launch { refresh() }
        } else if (refresh() is Outcome.Err) {
            auth.value = Auth.SignedOut
        }
    }

    override suspend fun refresh(): Outcome<User> = when (val r = api.call<UserEnvelope>(HttpMethod.Get, "auth/me")) {
        is Outcome.Err -> r
        is Outcome.Ok -> adopt(r.value.user)
    }

    override suspend fun login(identifier: String, password: String): Outcome<User> = signIn(
        api.call<UserEnvelope>(HttpMethod.Post, "auth/login", buildJsonObject {
            put("identifier", identifier); put("password", password); put("remember", true)
        }),
    )

    override suspend fun signup(name: String, email: String, password: String): Outcome<User> = signIn(
        api.call<UserEnvelope>(HttpMethod.Post, "auth/signup", buildJsonObject {
            put("name", name); put("email", email); put("password", password)
        }),
    )

    override suspend fun forgotPassword(email: String): Outcome<Unit> =
        api.send(HttpMethod.Post, "auth/forgot-password", buildJsonObject { put("email", email) }).map { }

    override suspend fun logout() {
        api.send(HttpMethod.Post, "auth/logout", JsonObject(emptyMap())) // best effort; offline is fine
        cookies.clear()
        withContext(io) {
            db.transaction {
                localData.clearUserData()
                prefs.remove(PrefKeys.LAST_USER_ID)
            }
        }
        auth.value = Auth.SignedOut
    }

    private suspend fun signIn(r: Outcome<UserEnvelope>): Outcome<User> = when (r) {
        is Outcome.Err -> r
        is Outcome.Ok -> adopt(r.value.user).also { sync.requestSync() }
    }

    /** Stores the server's user. A different account than the last one on this device wipes local data first. */
    private suspend fun adopt(dto: UserDto): Outcome<User> {
        withContext(io) {
            db.transaction {
                if (prefs.get(PrefKeys.LAST_USER_ID) != dto.id) localData.clearUserData()
                prefs.put(PrefKeys.LAST_USER_ID, dto.id)
                cache.put(dto)
            }
        }
        auth.value = Auth.SignedIn
        return Outcome.Ok(dto.toDomain(api.json))
    }
}
```

`repository/SettingsRepositoryImpl.kt`:
```kotlin
package tech.beyondscale.mitra.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.UserEnvelope
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository

/** Account settings live in the user's JSONB `settings`; PATCH /users/me/settings shallow-merges. */
class SettingsRepositoryImpl(
    private val db: MitraDb,
    private val cache: UserCache,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val auth: AuthRepository,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    init {
        engine.register(ENTITY, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val user = response?.let { runCatching { json.decodeFromJsonElement(UserEnvelope.serializer(), it).user }.getOrNull() } ?: return
                withContext(io) {
                    // A newer local change is still queued: keep it rather than flashing the older server copy.
                    if (db.outboxQueries.pendingByCoalesceKey(ENTITY).executeAsOneOrNull() == null) cache.put(user)
                }
            }
            override suspend fun onRejected(error: AppError) { auth.refresh() }
        })
    }

    override suspend fun updateModules(modules: Modules) {
        val patch = buildJsonObject {
            putJsonObject("modules") { put("money", modules.money); put("wellness", modules.wellness); put("ai", modules.ai) }
        }
        withContext(io) {
            db.transaction {
                val dto = cache.current() ?: return@transaction
                cache.put(dto.copy(settings = JsonObject(dto.settings + patch)))
                outbox.enqueue(ENTITY, "PATCH", "users/me/settings", patch, coalesceKey = ENTITY)
            }
        }
        engine.requestSync()
    }

    companion object { const val ENTITY = "user-settings" }
}
```

`repository/PreferencesRepositoryImpl.kt`:
```kotlin
package tech.beyondscale.mitra.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.PreferencesRepository

class PreferencesRepositoryImpl(private val prefs: PrefStore, private val io: CoroutineDispatcher = Dispatchers.IO) : PreferencesRepository {
    override val theme: Flow<ThemeMode> = prefs.observe(PrefKeys.THEME).map { v -> ThemeMode.entries.firstOrNull { it.name == v } ?: ThemeMode.Dark }
    override suspend fun setTheme(mode: ThemeMode) = withContext(io) { prefs.put(PrefKeys.THEME, mode.name) }
}
```

`di/DataModule.kt`:
```kotlin
package tech.beyondscale.mitra.data.di

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.SystemClock
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.repository.AuthRepositoryImpl
import tech.beyondscale.mitra.data.repository.PreferencesRepositoryImpl
import tech.beyondscale.mitra.data.repository.SettingsRepositoryImpl
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController

/** Process-wide scope for repositories and sync (outlives screens). */
class AppScope { val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }

/** Platform modules must also provide: SqlDriver, SecureStore, HttpClientEngine, SyncScheduler. */
fun dataModule(baseUrl: String): Module = module {
    single { AppScope() }
    single<AppClock> { SystemClock }
    single { MitraDb(get<SqlDriver>()) }
    single { PersistentCookiesStorage(get(), get()) }
    single { ApiClient(get<HttpClientEngine>(), baseUrl, get<PersistentCookiesStorage>()) }
    single<Json> { get<ApiClient>().json }
    single { UserCache(get(), get(), Dispatchers.IO) }
    single { PrefStore(get(), Dispatchers.IO) }
    single { LocalData(get()) }
    single { OutboxWriter(get(), get(), get()) }
    single { SyncEngine(get(), get(), get(), get<AppScope>().scope) }
    single<SyncController> { get<SyncEngine>() }
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get<AppScope>().scope) }
    // Eager: it registers the user-settings sync handler, which must exist before the first drain.
    single<SettingsRepository>(createdAtStart = true) { SettingsRepositoryImpl(get(), get(), get(), get(), get(), get()) }
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:data:testAndroidHostTest`
Expected: PASS (AuthRepositoryImplTest 10, SettingsRepositoryImplTest 2, plus earlier).

- [ ] **Step 5: Commit**

```bash
git add mitra-mobile/shared/data && git commit -m "feat(mobile): auth/settings/preferences repositories and Koin data module

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: designsystem — Noir theme, fonts, icons, components

**Files:**
- Create: `mitra-mobile/tools/gen_icons.py`
- Create: `shared/designsystem/src/commonMain/composeResources/font/{manrope.ttf,jetbrains_mono.ttf}` (downloaded)
- Create: `shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/theme/{Colors,Type,Theme}.kt`
- Create: `shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons/MitraIcons.kt` (generated)
- Create: `shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/components/{Card,Button,TextField,Basics,Bars}.kt`

**Interfaces:**
- Produces:
  - `MitraTheme(dark: Boolean, content)`; `Mitra.colors: MitraColors`, `Mitra.type: MitraType`; `MitraDimens.{gap,pad,radius,radiusSm,radiusLg}`
  - `MitraColors` fields `bg, surface, surface2, text, muted, faint, border, accent, onAccent, success, warning, danger, info, navBg, isDark` + `accentSoft, dangerSoft, warningSoft, track`
  - `MitraType` fields `h1, h2, h3, body, bodyBold, sm, xs, eyebrow, num, numLg`
  - `MitraIcons.<Name>` (e.g. `Sun`, `CheckSquare`, `Wallet`, `Grid`, `Plus`, `Repeat`, `Target`, `Heart`, `Note`, `Sparkles`, `Settings`, `Logout`, `Eye`, `ArrowLeft`, `ChevronRight`, `WifiOff`, `Refresh`, `Moon`, `Sun`, `User`, `Mail`, `Lock`) and `MitraIcons.byName(String)`
  - Components: `MitraCard(modifier, tone: CardTone, contentPadding, onClick, content)`, `MitraButton(text, onClick, modifier, kind: ButtonKind, size: ButtonSize, icon, loading, enabled)`, `MitraTextField(value, onValueChange, label, modifier, error, keyboardType, isPassword, imeAction, onImeAction)`, `Eyebrow(text)`, `MitraMark(size)`, `Avatar(initials, size)`, `StatCard(label, value, sub, modifier, onClick)`, `EmptyState(icon, title, body, modifier, action)`, `MitraBanner(text, tone: BannerTone)`, `SyncPill(text, tone: PillTone)`, `MitraTopBar(title, modifier, onBack, actions)`, `MitraSegmented(options, selectedIndex, onSelect, modifier)`, `MitraSwitchRow(title, checked, onCheckedChange, sub)`, `SectionHeader(title)`

- [ ] **Step 1: Fonts and icon generator**

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile"
FONTS=shared/designsystem/src/commonMain/composeResources/font && mkdir -p "$FONTS" tools
curl -sfL -o "$FONTS/manrope.ttf" "https://github.com/google/fonts/raw/main/ofl/manrope/Manrope%5Bwght%5D.ttf"
curl -sfL -o "$FONTS/jetbrains_mono.ttf" "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
file "$FONTS"/*.ttf   # both: "TrueType Font data"
```

Write `tools/gen_icons.py` (verified in the spike — produced 80 icons that compile):
```python
#!/usr/bin/env python3
"""Generate MitraIcons.kt (Compose ImageVectors) from the web icon set in mitra-frontend/src/lib/icons.ts.
Usage: gen_icons.py <icons.ts> <out.kt> <package>"""
import re, sys

def fmt(n: float) -> str:
    s = f"{n:.4f}".rstrip('0').rstrip('.')
    return s if s not in ('', '-0') else '0'

def attrs(tag: str) -> dict:
    return dict(re.findall(r'([a-z-]+)="([^"]*)"', tag))

def circle(a):
    cx, cy, r = float(a['cx']), float(a['cy']), float(a['r'])
    return f"M{fmt(cx - r)},{fmt(cy)} a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0 a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0"

def rect(a):
    x, y, w, h = (float(a[k]) for k in ('x', 'y', 'width', 'height'))
    rx = min(float(a.get('rx', 0)), w / 2, h / 2)
    if rx == 0:
        return f"M{fmt(x)},{fmt(y)} h{fmt(w)} v{fmt(h)} h{fmt(-w)} z"
    return (f"M{fmt(x + rx)},{fmt(y)} h{fmt(w - 2 * rx)} a{fmt(rx)},{fmt(rx)} 0 0 1 {fmt(rx)},{fmt(rx)} "
            f"v{fmt(h - 2 * rx)} a{fmt(rx)},{fmt(rx)} 0 0 1 {fmt(-rx)},{fmt(rx)} h{fmt(-(w - 2 * rx))} "
            f"a{fmt(rx)},{fmt(rx)} 0 0 1 {fmt(-rx)},{fmt(-rx)} v{fmt(-(h - 2 * rx))} a{fmt(rx)},{fmt(rx)} 0 0 1 {fmt(rx)},{fmt(-rx)} z")

def to_paths(svg: str) -> list[str]:
    out = []
    for tag, rest in re.findall(r'<(path|circle|rect)\b([^>]*)/?>', svg):
        a = attrs(rest)
        out.append(a['d'] if tag == 'path' else circle(a) if tag == 'circle' else rect(a))
    return out

def prop(name: str) -> str:
    return ''.join(p[:1].upper() + p[1:] for p in re.split(r'[-_]', name))

def main(src, dst, pkg):
    text = open(src, encoding='utf-8').read()
    body = text[text.index('{') + 1:text.rindex('}')]
    entries = re.findall(r"'?([a-z][a-z0-9-]*)'?\s*:\s*'([^']*)'", body)
    if not entries:
        sys.exit('no icons found')
    lines = [
        f"package {pkg}", "",
        "// GENERATED by tools/gen_icons.py from mitra-frontend/src/lib/icons.ts — do not edit by hand.", "",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.addPathNodes",
        "import androidx.compose.ui.unit.dp", "",
        "private fun icon(name: String, vararg paths: String): ImageVector =",
        "    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {",
        "        paths.forEach { d ->",
        "            addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,",
        "                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)",
        "        }",
        "    }.build()", "",
        "object MitraIcons {",
    ]
    for name, svg in entries:
        ps = ', '.join('"' + p + '"' for p in to_paths(svg))
        lines.append(f'    val {prop(name)}: ImageVector by lazy {{ icon("{name}", {ps}) }}')
    lines += ["", "    /** Lookup by the web's string id (server activity rows carry these); unknown ids fall back to a circle. */",
              "    fun byName(name: String): ImageVector = when (name) {"]
    for name, _ in entries:
        lines.append(f'        "{name}" -> {prop(name)}')
    lines += ["        else -> Circle", "    }", "}", ""]
    open(dst, 'w', encoding='utf-8').write('\n'.join(lines))
    print(f"wrote {len(entries)} icons to {dst}")

if __name__ == '__main__':
    main(*sys.argv[1:4])
```

Generate:
```bash
OUT=shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons
mkdir -p "$OUT" && python3 -I tools/gen_icons.py ../mitra-frontend/src/lib/icons.ts "$OUT/MitraIcons.kt" tech.beyondscale.mitra.designsystem.icons
```
Expected: `wrote 80 icons to …/MitraIcons.kt`.

- [ ] **Step 2: Theme**

`theme/Colors.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Noir tokens from mitra-frontend/src/app/globals.css (`.frame` and `.frame[data-mode=light]`). */
@Immutable
data class MitraColors(
    val bg: Color, val surface: Color, val surface2: Color,
    val text: Color, val muted: Color, val faint: Color, val border: Color,
    val accent: Color, val onAccent: Color,
    val success: Color, val warning: Color, val danger: Color, val info: Color,
    val navBg: Color, val isDark: Boolean,
) {
    // color-mix(in srgb, X n%, surface) equivalents
    val accentSoft: Color get() = lerp(surface, accent, 0.12f)
    val successSoft: Color get() = lerp(surface, success, 0.14f)
    val warningSoft: Color get() = lerp(surface, warning, 0.16f)
    val dangerSoft: Color get() = lerp(surface, danger, 0.12f)
    val track: Color get() = lerp(surface, text, 0.08f)
}

val NoirDark = MitraColors(
    bg = Color(0xFF0A0B0F), surface = Color(0xFF13151C), surface2 = Color(0xFF1B1E27),
    text = Color(0xFFECEEF3), muted = Color(0xFF8C92A3), faint = Color(0xFF565C6C), border = Color.White.copy(alpha = 0.09f),
    accent = Color(0xFF2DD4BF), onAccent = Color(0xFF04110F),
    success = Color(0xFF4ADE80), warning = Color(0xFFFBBF24), danger = Color(0xFFFB7185), info = Color(0xFF60A5FA),
    navBg = Color(0xFF0E1015), isDark = true,
)

val NoirLight = MitraColors(
    bg = Color(0xFFEDEFF3), surface = Color(0xFFFBFBFD), surface2 = Color(0xFFE4E7EE),
    text = Color(0xFF0E1016), muted = Color(0xFF5C6273), faint = Color(0xFF9AA0AF), border = Color(0xFFD6D9E2),
    accent = Color(0xFF0D9488), onAccent = Color.White,
    success = Color(0xFF15803D), warning = Color(0xFFB45309), danger = Color(0xFFE11D48), info = Color(0xFF2563EB),
    navBg = Color(0xFFF5F6F9), isDark = false,
)
```

`theme/Type.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import tech.beyondscale.mitra.designsystem.resources.Res
import tech.beyondscale.mitra.designsystem.resources.jetbrains_mono
import tech.beyondscale.mitra.designsystem.resources.manrope

@Immutable
data class MitraType(
    val h1: TextStyle, val h2: TextStyle, val h3: TextStyle,
    val body: TextStyle, val bodyBold: TextStyle, val sm: TextStyle, val xs: TextStyle,
    val eyebrow: TextStyle, val num: TextStyle, val numLg: TextStyle,
)

@Composable
private fun manrope() = FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    Font(Res.font.manrope, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

@Composable
private fun mono() = FontFamily(listOf(400, 500, 600).map { w ->
    Font(Res.font.jetbrains_mono, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

/** Sizes follow globals.css: body 14px/1.45, .sm 12.5, .xs 11.5, .eyebrow 11 bold +.09em, headings -0.03em. */
@Composable
internal fun mitraType(): MitraType {
    val m = manrope(); val n = mono()
    val body = TextStyle(fontFamily = m, fontSize = 14.sp, lineHeight = 20.sp)
    return MitraType(
        h1 = TextStyle(fontFamily = m, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.03).em),
        h2 = TextStyle(fontFamily = m, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.03).em),
        h3 = TextStyle(fontFamily = m, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = (-0.015).em),
        body = body,
        bodyBold = body.copy(fontWeight = FontWeight.SemiBold),
        sm = TextStyle(fontFamily = m, fontSize = 12.5.sp, lineHeight = 18.sp),
        xs = TextStyle(fontFamily = m, fontSize = 11.5.sp, lineHeight = 16.sp),
        eyebrow = TextStyle(fontFamily = m, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.09.em),
        num = TextStyle(fontFamily = n, fontWeight = FontWeight.Medium, fontSize = 14.sp, fontFeatureSettings = "tnum"),
        numLg = TextStyle(fontFamily = n, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, fontFeatureSettings = "tnum"),
    )
}
```

`theme/Theme.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalMitraColors = staticCompositionLocalOf { NoirDark }
val LocalMitraType = staticCompositionLocalOf<MitraType> { error("MitraTheme is missing") }

object Mitra {
    val colors: MitraColors @Composable @ReadOnlyComposable get() = LocalMitraColors.current
    val type: MitraType @Composable @ReadOnlyComposable get() = LocalMitraType.current
}

/** --gap, --pad, --r, --r-sm, --r-lg from globals.css */
object MitraDimens {
    val gap = 14.dp
    val pad = 16.dp
    val radius = 10.dp
    val radiusSm = 7.dp
    val radiusLg = 14.dp
}

@Composable
fun MitraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) NoirDark else NoirLight
    val type = mitraType()
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent,
        background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
        outline = c.border, outlineVariant = c.border, error = c.danger,
        inverseSurface = c.text, inverseOnSurface = c.bg,
    )
    CompositionLocalProvider(LocalMitraColors provides c, LocalMitraType provides type) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                headlineSmall = type.h1, titleLarge = type.h2, titleMedium = type.h3,
                bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.sm, labelLarge = type.bodyBold,
            ),
            shapes = Shapes(
                small = RoundedCornerShape(MitraDimens.radiusSm),
                medium = RoundedCornerShape(MitraDimens.radius),
                large = RoundedCornerShape(MitraDimens.radiusLg),
            ),
            content = content,
        )
    }
}
```

- [ ] **Step 3: Components**

`components/Card.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

enum class CardTone { Default, Soft, Accent }

@Composable
fun MitraCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Default,
    contentPadding: PaddingValues = PaddingValues(MitraDimens.pad),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mitra.colors
    val (bg, border) = when (tone) {
        CardTone.Default -> c.surface to c.border
        CardTone.Soft -> c.surface2 to Color.Transparent
        CardTone.Accent -> c.accentSoft to c.accent.copy(alpha = 0.25f)
    }
    val shape = RoundedCornerShape(MitraDimens.radius)
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
```

`components/Button.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

enum class ButtonKind { Primary, Outline, Ghost, Soft, Danger }
enum class ButtonSize { Md, Lg }

@Composable
fun MitraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Md,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Mitra.colors
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.accent, c.onAccent, Color.Transparent)
        ButtonKind.Outline -> Triple(Color.Transparent, c.text, c.border)
        ButtonKind.Ghost -> Triple(Color.Transparent, c.muted, Color.Transparent)
        ButtonKind.Soft -> Triple(c.accentSoft, c.accent, Color.Transparent)
        ButtonKind.Danger -> Triple(c.dangerSoft, c.danger, Color.Transparent)
    }
    val shape = RoundedCornerShape(MitraDimens.radiusSm)
    Row(
        modifier
            .heightIn(min = if (size == ButtonSize.Lg) 48.dp else 40.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
        Text(text, style = Mitra.type.bodyBold, color = fg)
    }
}
```

`components/TextField.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun MitraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val c = Mitra.colors
    var revealed by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            textStyle = Mitra.type.body.copy(color = c.text),
            visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(MitraIcons.Eye, contentDescription = if (revealed) "Hide password" else "Show password", tint = if (revealed) c.accent else c.muted)
                    }
                }
            } else null,
            shape = RoundedCornerShape(MitraDimens.radiusSm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.border, errorBorderColor = c.danger,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, errorContainerColor = c.surface,
                cursorColor = c.accent,
            ),
        )
        if (error != null) Text(error, style = Mitra.type.xs, color = c.danger)
    }
}
```

`components/Basics.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = Mitra.type.eyebrow, color = Mitra.colors.muted)

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) =
    Text(title, modifier.padding(top = 4.dp), style = Mitra.type.h3, color = Mitra.colors.text)

/** The app's logo mark: accent tile with an "M". */
@Composable
fun MitraMark(size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Mitra.colors.accent),
        contentAlignment = Alignment.Center,
    ) { Text("M", color = Mitra.colors.onAccent, style = Mitra.type.h1.copy(fontSize = (size.value * 0.5f).sp)) }
}

@Composable
fun Avatar(initials: String, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Mitra.colors.accentSoft), contentAlignment = Alignment.Center) {
        Text(initials, style = Mitra.type.xs.copy(fontWeight = FontWeight.Bold), color = Mitra.colors.accent)
    }
}

@Composable
fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    MitraCard(modifier.width(148.dp), onClick = onClick) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        Text(value, style = Mitra.type.numLg, color = Mitra.colors.text)
        Text(sub, style = Mitra.type.xs, color = Mitra.colors.faint)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = MitraDimens.pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Mitra.colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Mitra.colors.accent)
        }
        Text(title, style = Mitra.type.h3, color = Mitra.colors.text, textAlign = TextAlign.Center)
        Text(body, style = Mitra.type.sm, color = Mitra.colors.muted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

enum class BannerTone { Danger, Info }

@Composable
fun MitraBanner(text: String, tone: BannerTone = BannerTone.Danger, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val (bg, fg) = if (tone == BannerTone.Danger) c.dangerSoft to c.danger else c.accentSoft to c.accent
    Text(text, modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radiusSm)).background(bg).padding(12.dp), style = Mitra.type.sm, color = fg)
}

enum class PillTone { Neutral, Warning, Accent }

@Composable
fun SyncPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val dot = when (tone) { PillTone.Neutral -> c.muted; PillTone.Warning -> c.warning; PillTone.Accent -> c.accent }
    Row(
        modifier.clip(CircleShape).background(c.surface2).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
    }
}

@Composable
fun MitraSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, sub: String? = null, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Mitra.type.bodyBold, color = c.text)
            if (sub != null) Text(sub, style = Mitra.type.xs, color = c.muted)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.track, uncheckedBorderColor = c.border, uncheckedThumbColor = c.muted),
        )
    }
}
```

`components/Bars.kt`:
```kotlin
package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun MitraTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (onBack == null) MitraDimens.pad else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(MitraIcons.ArrowLeft, contentDescription = "Back", tint = Mitra.colors.text) }
        Text(title, Modifier.weight(1f), style = Mitra.type.h2, color = Mitra.colors.text)
        actions()
    }
}

@Composable
fun MitraSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radiusSm)).background(c.surface2).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(MitraDimens.radiusSm - 2.dp))
                    .background(if (on) c.surface else c.surface2)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.text else c.muted, textAlign = TextAlign.Center)
            }
        }
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :shared:designsystem:compileAndroidMain :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. *(No unit tests here — this module is presentation only; it is checked visually in Task 13.)*

- [ ] **Step 5: Commit**

```bash
git add mitra-mobile/tools mitra-mobile/shared/designsystem && git commit -m "feat(mobile): Noir design system — tokens, fonts, generated web icons, base components

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: feature/auth — login, signup, forgot password

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/auth/{AuthScaffold,LoginViewModel,LoginScreen,SignupViewModel,SignupScreen,ForgotPasswordViewModel,ForgotPasswordScreen}.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/auth/{LoginViewModelTest,SignupViewModelTest,ForgotPasswordViewModelTest}.kt`

**Interfaces:**
- Consumes: `LoginUseCase`, `SignupUseCase`, `ForgotPasswordUseCase`, `AppError`, design-system components.
- Produces: `LoginRoute(onSignup, onForgot)`, `SignupRoute(onSignIn)`, `ForgotPasswordRoute(onBack)` composables; `LoginViewModel`, `SignupViewModel`, `ForgotPasswordViewModel`; `internal fun AppError.fieldErrors(): Map<String, String>` and `internal fun AppError.bannerText(): String?` (in `LoginViewModel.kt`).

- [ ] **Step 1: Write failing tests**

`LoginViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.LoginUseCase
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LoginViewModelTest : ViewModelTest() {
    private val repo = FakeAuthRepository()
    private fun vm() = LoginViewModel(LoginUseCase(repo))

    @Test fun emptySubmitShowsFieldErrors() {
        val vm = vm()
        vm.onAction(LoginAction.Submit)
        assertEquals(setOf("identifier", "password"), vm.state.value.fieldErrors.keys)
        assertFalse(vm.state.value.loading)
    }

    @Test fun typingClearsThatFieldsError() {
        val vm = vm()
        vm.onAction(LoginAction.Submit)
        vm.onAction(LoginAction.Identifier("raju"))
        assertEquals(setOf("password"), vm.state.value.fieldErrors.keys)
    }

    @Test fun wrongPasswordShowsServerMessage() {
        repo.result = Outcome.Err(AppError.Unauthenticated("BAD_CREDENTIALS", "Email/username or password is incorrect"))
        val vm = vm()
        vm.onAction(LoginAction.Identifier("raju")); vm.onAction(LoginAction.Password("nope")); vm.onAction(LoginAction.Submit)
        assertEquals("Email/username or password is incorrect", vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun offlineShowsOfflineMessage() {
        repo.result = Outcome.Err(AppError.Offline)
        val vm = vm()
        vm.onAction(LoginAction.Identifier("raju")); vm.onAction(LoginAction.Password("pw")); vm.onAction(LoginAction.Submit)
        assertEquals("You appear to be offline", vm.state.value.error)
    }

    @Test fun successCallsRepository() {
        val vm = vm()
        vm.onAction(LoginAction.Identifier("raju")); vm.onAction(LoginAction.Password("pw")); vm.onAction(LoginAction.Submit)
        assertEquals(listOf("login:raju"), repo.calls)
        assertNull(vm.state.value.error)
    }
}
```

`SignupViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.SignupUseCase
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SignupViewModelTest : ViewModelTest() {
    private val repo = FakeAuthRepository()

    @Test fun serverEmailTakenShowsAsBanner() {
        repo.result = Outcome.Err(AppError.Server(409, "EMAIL_TAKEN", "An account with this email already exists"))
        val vm = SignupViewModel(SignupUseCase(repo))
        vm.onAction(SignupAction.Name("Raju")); vm.onAction(SignupAction.Email("raju@example.com")); vm.onAction(SignupAction.Password("longenough"))
        vm.onAction(SignupAction.Submit)
        assertEquals("An account with this email already exists", vm.state.value.error)
    }

    @Test fun shortPasswordIsFieldError() {
        val vm = SignupViewModel(SignupUseCase(repo))
        vm.onAction(SignupAction.Name("Raju")); vm.onAction(SignupAction.Email("raju@example.com")); vm.onAction(SignupAction.Password("short"))
        vm.onAction(SignupAction.Submit)
        assertEquals(mapOf("password" to "Password must be at least 8 characters"), vm.state.value.fieldErrors)
    }
}
```

`ForgotPasswordViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgotPasswordViewModelTest : ViewModelTest() {
    @Test fun sendsAndShowsConfirmation() {
        val repo = FakeAuthRepository()
        val vm = ForgotPasswordViewModel(ForgotPasswordUseCase(repo))
        vm.onAction(ForgotAction.Email("raju@example.com")); vm.onAction(ForgotAction.Submit)
        assertTrue(vm.state.value.sent)
        assertEquals(listOf("forgot:raju@example.com"), repo.calls)
    }

    @Test fun invalidEmailStaysOnForm() {
        val vm = ForgotPasswordViewModel(ForgotPasswordUseCase(FakeAuthRepository()))
        vm.onAction(ForgotAction.Email("nope")); vm.onAction(ForgotAction.Submit)
        assertFalse(vm.state.value.sent)
        assertEquals("Enter a valid email", vm.state.value.fieldError)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:feature:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'LoginViewModel'`.

- [ ] **Step 3: Implement ViewModels**

`LoginViewModel.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.LoginUseCase

data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
)

sealed interface LoginAction {
    data class Identifier(val value: String) : LoginAction
    data class Password(val value: String) : LoginAction
    data object Submit : LoginAction
}

internal fun AppError.fieldErrors(): Map<String, String> =
    (this as? AppError.Validation)?.issues?.associate { it.path to it.message }.orEmpty()

/** Validation errors sit under their fields; everything else shows as a banner. */
internal fun AppError.bannerText(): String? = if (this is AppError.Validation) null else message

/** On success the app root sees SessionState.SignedIn and swaps to the main shell — no navigation here. */
class LoginViewModel(private val login: LoginUseCase) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.Identifier -> _state.update { it.copy(identifier = action.value, fieldErrors = it.fieldErrors - "identifier", error = null) }
            is LoginAction.Password -> _state.update { it.copy(password = action.value, fieldErrors = it.fieldErrors - "password", error = null) }
            LoginAction.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = login(s.identifier, s.password)
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false)
                    is Outcome.Err -> it.copy(loading = false, fieldErrors = r.error.fieldErrors(), error = r.error.bannerText())
                }
            }
        }
    }
}
```

`SignupViewModel.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.SignupUseCase

data class SignupUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
)

sealed interface SignupAction {
    data class Name(val value: String) : SignupAction
    data class Email(val value: String) : SignupAction
    data class Password(val value: String) : SignupAction
    data object Submit : SignupAction
}

class SignupViewModel(private val signup: SignupUseCase) : ViewModel() {
    private val _state = MutableStateFlow(SignupUiState())
    val state: StateFlow<SignupUiState> = _state.asStateFlow()

    fun onAction(action: SignupAction) {
        when (action) {
            is SignupAction.Name -> _state.update { it.copy(name = action.value, fieldErrors = it.fieldErrors - "name", error = null) }
            is SignupAction.Email -> _state.update { it.copy(email = action.value, fieldErrors = it.fieldErrors - "email", error = null) }
            is SignupAction.Password -> _state.update { it.copy(password = action.value, fieldErrors = it.fieldErrors - "password", error = null) }
            SignupAction.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = signup(s.name, s.email, s.password)
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false)
                    is Outcome.Err -> it.copy(loading = false, fieldErrors = r.error.fieldErrors(), error = r.error.bannerText())
                }
            }
        }
    }
}
```

`ForgotPasswordViewModel.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase

data class ForgotUiState(
    val email: String = "",
    val loading: Boolean = false,
    val sent: Boolean = false,
    val fieldError: String? = null,
    val error: String? = null,
)

sealed interface ForgotAction {
    data class Email(val value: String) : ForgotAction
    data object Submit : ForgotAction
}

class ForgotPasswordViewModel(private val forgot: ForgotPasswordUseCase) : ViewModel() {
    private val _state = MutableStateFlow(ForgotUiState())
    val state: StateFlow<ForgotUiState> = _state.asStateFlow()

    fun onAction(action: ForgotAction) {
        when (action) {
            is ForgotAction.Email -> _state.update { it.copy(email = action.value, fieldError = null, error = null) }
            ForgotAction.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = forgot(s.email)
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false, sent = true)
                    is Outcome.Err -> it.copy(loading = false, fieldError = r.error.fieldErrors()["email"], error = r.error.bannerText())
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:feature:testAndroidHostTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Screens**

`AuthScaffold.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.components.MitraMark
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
internal fun AuthScaffold(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Mitra.colors.bg).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(MitraDimens.gap)) {
            MitraMark()
            Spacer(Modifier.height(4.dp))
            Text(title, style = Mitra.type.h1, color = Mitra.colors.text)
            Text(subtitle, style = Mitra.type.body, color = Mitra.colors.muted)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
```

`LoginScreen.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraTextField

@Composable
fun LoginRoute(onSignup: () -> Unit, onForgot: () -> Unit, vm: LoginViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LoginScreen(state, vm::onAction, onSignup, onForgot)
}

@Composable
fun LoginScreen(state: LoginUiState, onAction: (LoginAction) -> Unit, onSignup: () -> Unit, onForgot: () -> Unit) {
    AuthScaffold(title = "Welcome back", subtitle = "Sign in to your Mitra account.") {
        MitraTextField(
            value = state.identifier, onValueChange = { onAction(LoginAction.Identifier(it)) },
            label = "Email or username", error = state.fieldErrors["identifier"], keyboardType = KeyboardType.Email,
        )
        MitraTextField(
            value = state.password, onValueChange = { onAction(LoginAction.Password(it)) },
            label = "Password", error = state.fieldErrors["password"], isPassword = true,
            imeAction = ImeAction.Done, onImeAction = { onAction(LoginAction.Submit) },
        )
        state.error?.let { MitraBanner(it) }
        MitraButton("Sign in", onClick = { onAction(LoginAction.Submit) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg, loading = state.loading)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MitraButton("Forgot password?", onClick = onForgot, kind = ButtonKind.Ghost)
            MitraButton("Create account", onClick = onSignup, kind = ButtonKind.Ghost)
        }
    }
}
```

`SignupScreen.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraTextField

@Composable
fun SignupRoute(onSignIn: () -> Unit, vm: SignupViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SignupScreen(state, vm::onAction, onSignIn)
}

@Composable
fun SignupScreen(state: SignupUiState, onAction: (SignupAction) -> Unit, onSignIn: () -> Unit) {
    AuthScaffold(title = "Create your account", subtitle = "Tasks, habits, money and more — in one place.") {
        MitraTextField(state.name, { onAction(SignupAction.Name(it)) }, label = "Name", error = state.fieldErrors["name"])
        MitraTextField(state.email, { onAction(SignupAction.Email(it)) }, label = "Email", error = state.fieldErrors["email"], keyboardType = KeyboardType.Email)
        MitraTextField(
            state.password, { onAction(SignupAction.Password(it)) }, label = "Password", error = state.fieldErrors["password"],
            isPassword = true, imeAction = ImeAction.Done, onImeAction = { onAction(SignupAction.Submit) },
        )
        state.error?.let { MitraBanner(it) }
        MitraButton("Create account", onClick = { onAction(SignupAction.Submit) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg, loading = state.loading)
        MitraButton("I already have an account", onClick = onSignIn, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}
```

`ForgotPasswordScreen.kt`:
```kotlin
package tech.beyondscale.mitra.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ButtonSize
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.theme.Mitra

@Composable
fun ForgotPasswordRoute(onBack: () -> Unit, vm: ForgotPasswordViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ForgotPasswordScreen(state, vm::onAction, onBack)
}

@Composable
fun ForgotPasswordScreen(state: ForgotUiState, onAction: (ForgotAction) -> Unit, onBack: () -> Unit) {
    AuthScaffold(title = "Reset your password", subtitle = "We'll email you a link to choose a new one.") {
        if (state.sent) {
            MitraCard(tone = CardTone.Accent) {
                Text("Check your inbox", style = Mitra.type.h3, color = Mitra.colors.text)
                Text(
                    "If an account exists for ${state.email.trim()}, a reset link is on its way. Open it on any device to set a new password, then sign in here.",
                    style = Mitra.type.sm, color = Mitra.colors.muted,
                )
            }
        } else {
            MitraTextField(
                state.email, { onAction(ForgotAction.Email(it)) }, label = "Email", error = state.fieldError,
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, onImeAction = { onAction(ForgotAction.Submit) },
            )
            state.error?.let { MitraBanner(it) }
            MitraButton("Send reset link", onClick = { onAction(ForgotAction.Submit) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.Lg, loading = state.loading)
        }
        MitraButton("Back to sign in", onClick = onBack, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}
```

- [ ] **Step 6: Build and test**

Run: `./gradlew :shared:feature:testAndroidHostTest :shared:feature:compileAndroidMain`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add mitra-mobile/shared/feature && git commit -m "feat(mobile): sign-in, sign-up and forgot-password screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: feature — Today, Settings, Coming-soon, Koin feature module

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/today/{TodayViewModel,TodayScreen}.kt`
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/settings/{SettingsViewModel,SettingsScreen}.kt`
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/common/ComingSoonScreen.kt`
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/di/FeatureModule.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/{today/TodayViewModelTest,settings/SettingsViewModelTest}.kt`

**Interfaces:**
- Consumes: `AuthRepository`, `SettingsRepository`, `PreferencesRepository`, `SyncController`, `AppClock`, `fmtLong`, `fmtClock`, `greeting`.
- Produces: `TodayRoute()`, `SettingsRoute()`, `ComingSoonScreen(title: String, icon: ImageVector)`; `TodayViewModel(auth, clock)`, `SettingsViewModel(auth, settings, prefs, sync)`; `val featureModule: Module`.

- [ ] **Step 1: Write failing tests**

`TodayViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.feature.today

import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TodayViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    @Test fun greetsByFirstNameInLocalTime() {
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), clock)
        assertEquals("Good morning, Raju", vm.state.value.greeting)
        assertEquals("Tuesday, 13 October 2026 · 10:00 AM", vm.state.value.dateLine)
        assertEquals(listOf("Tasks", "Habits", "Spent today", "Sleep"), vm.state.value.stats.map { it.label })
    }
}
```

`SettingsViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakePreferencesRepository
import tech.beyondscale.mitra.testing.FakeSettingsRepository
import tech.beyondscale.mitra.testing.FakeSyncController
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest : ViewModelTest() {
    private val auth = FakeAuthRepository(SessionState.SignedIn(testUser(settings = Settings(modules = Modules(wellness = false)))))
    private val settings = FakeSettingsRepository()
    private val prefs = FakePreferencesRepository()
    private val sync = FakeSyncController()
    private fun vm() = SettingsViewModel(auth, settings, prefs, sync)

    @Test fun exposesAccountThemeAndPending() = runTest {
        sync.pending.value = 3
        val s = vm().state.first { it.email.isNotEmpty() }
        assertEquals("RO", s.initials)
        assertEquals(Modules(wellness = false), s.modules)
        assertEquals(3L, s.pending)
    }

    @Test fun togglingAModuleKeepsTheOthers() {
        vm().onAction(SettingsAction.SetModule(ModuleKey.Money, false))
        assertEquals(listOf(Modules(money = false, wellness = false, ai = true)), settings.updates)
    }

    @Test fun setThemeAndSignOut() {
        val vm = vm()
        vm.onAction(SettingsAction.SetTheme(ThemeMode.Light))
        assertEquals(ThemeMode.Light, prefs.themeFlow.value)
        vm.onAction(SettingsAction.SignOut)
        assertEquals(listOf("logout"), auth.calls)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:feature:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'TodayViewModel'`.

- [ ] **Step 3: Implement ViewModels**

`today/TodayViewModel.kt`:
```kotlin
package tech.beyondscale.mitra.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.fmtClock
import tech.beyondscale.mitra.core.fmtLong
import tech.beyondscale.mitra.core.greeting
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.repository.AuthRepository

data class StatUi(val label: String, val value: String, val sub: String)
data class TodayUiState(val dateLine: String, val greeting: String, val stats: List<StatUi>)

/** Phase 1: header + stat strip skeleton. Each later module fills its stat and Today section. */
class TodayViewModel(auth: AuthRepository, private val clock: AppClock) : ViewModel() {
    val state: StateFlow<TodayUiState> = auth.session.map(::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(auth.session.value))

    private fun build(session: SessionState): TodayUiState {
        val now = clock.localNow()
        val first = (session as? SessionState.SignedIn)?.user?.firstName
        return TodayUiState(
            dateLine = "${fmtLong(now.date.toString())} · ${fmtClock(now.hour, now.minute)}",
            greeting = greeting(now.hour) + (first?.let { ", $it" } ?: ""),
            stats = listOf(
                StatUi("Tasks", "—", "Done today"),
                StatUi("Habits", "—", "Due today"),
                StatUi("Spent today", "—", "Of monthly budget"),
                StatUi("Sleep", "—", "Last night"),
            ),
        )
    }
}
```

`settings/SettingsViewModel.kt`:
```kotlin
package tech.beyondscale.mitra.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController

data class SettingsUiState(
    val name: String = "",
    val email: String = "",
    val initials: String = "",
    val theme: ThemeMode = ThemeMode.Dark,
    val modules: Modules = Modules(),
    val pending: Long = 0,
    val signingOut: Boolean = false,
)

enum class ModuleKey { Money, Wellness, Ai }

sealed interface SettingsAction {
    data class SetTheme(val mode: ThemeMode) : SettingsAction
    data class SetModule(val key: ModuleKey, val on: Boolean) : SettingsAction
    data object SignOut : SettingsAction
}

class SettingsViewModel(
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val prefs: PreferencesRepository,
    sync: SyncController,
) : ViewModel() {
    private val signingOut = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(auth.session, prefs.theme, sync.pendingCount, signingOut) { session, theme, pending, out ->
        val user = (session as? SessionState.SignedIn)?.user
        SettingsUiState(user?.name.orEmpty(), user?.email.orEmpty(), user?.initials.orEmpty(), theme, user?.settings?.modules ?: Modules(), pending, out)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetTheme -> viewModelScope.launch { prefs.setTheme(action.mode) }
            is SettingsAction.SetModule -> viewModelScope.launch {
                val current = (auth.session.value as? SessionState.SignedIn)?.user?.settings?.modules ?: return@launch
                settings.updateModules(
                    when (action.key) {
                        ModuleKey.Money -> current.copy(money = action.on)
                        ModuleKey.Wellness -> current.copy(wellness = action.on)
                        ModuleKey.Ai -> current.copy(ai = action.on)
                    },
                )
            }
            SettingsAction.SignOut -> {
                signingOut.value = true
                viewModelScope.launch { auth.logout() }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:feature:testAndroidHostTest`
Expected: PASS (13 tests in module).

- [ ] **Step 5: Screens and Koin module**

`today/TodayScreen.kt`:
```kotlin
package tech.beyondscale.mitra.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.StatCard
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun TodayRoute(vm: TodayViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    TodayScreen(state)
}

@Composable
fun TodayScreen(state: TodayUiState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        Column(Modifier.padding(horizontal = MitraDimens.pad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(state.dateLine)
            Text(state.greeting, style = Mitra.type.h1, color = Mitra.colors.text)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = MitraDimens.pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.stats, key = { it.label }) { StatCard(it.label, it.value, it.sub) }
        }
        MitraCard(Modifier.padding(horizontal = MitraDimens.pad)) {
            EmptyState(
                icon = MitraIcons.Sun,
                title = "Your day starts here",
                body = "Priorities, habits, money and your check-in will appear here as each module arrives in the app.",
            )
        }
    }
}
```

`settings/SettingsScreen.kt`:
```kotlin
package tech.beyondscale.mitra.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.Avatar
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.ThemeMode

@Composable
fun SettingsRoute(vm: SettingsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(state, vm::onAction)
}

@Composable
fun SettingsScreen(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MitraDimens.pad),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        MitraCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.initials, size = 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(state.name, style = Mitra.type.h3, color = Mitra.colors.text)
                    Text(state.email, style = Mitra.type.sm, color = Mitra.colors.muted)
                }
            }
        }

        SectionHeader("Appearance")
        val modes = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.Auto)
        MitraSegmented(listOf("Dark", "Light", "Auto"), modes.indexOf(state.theme), { onAction(SettingsAction.SetTheme(modes[it])) })

        SectionHeader("Modules")
        MitraCard {
            MitraSwitchRow("Money", state.modules.money, { onAction(SettingsAction.SetModule(ModuleKey.Money, it)) }, sub = "Expenses, budgets, bills")
            MitraSwitchRow("Wellness", state.modules.wellness, { onAction(SettingsAction.SetModule(ModuleKey.Wellness, it)) }, sub = "Check-ins, sleep, trends")
            MitraSwitchRow("AI assistant", state.modules.ai, { onAction(SettingsAction.SetModule(ModuleKey.Ai, it)) }, sub = "Ask Mitra, suggestions")
        }

        SectionHeader("Sync")
        Text(
            if (state.pending == 0L) "All changes are synced." else "${state.pending} change${if (state.pending == 1L) "" else "s"} waiting to sync.",
            style = Mitra.type.sm, color = Mitra.colors.muted,
        )

        MitraButton(
            "Sign out", icon = MitraIcons.Logout, kind = ButtonKind.Danger, loading = state.signingOut,
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (state.pending > 0) confirmSignOut = true else onAction(SettingsAction.SignOut) },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out now?") },
            text = { Text("${state.pending} change${if (state.pending == 1L) " hasn't" else "s haven't"} synced yet and will be lost. Connect to the internet first to keep them.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onAction(SettingsAction.SignOut) }) { Text("Sign out", color = Mitra.colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
            containerColor = Mitra.colors.surface,
        )
    }
}
```

`common/ComingSoonScreen.kt`:
```kotlin
package tech.beyondscale.mitra.feature.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

/** Placeholder for modules that arrive in later phases. */
@Composable
fun ComingSoonScreen(title: String, icon: ImageVector) {
    EmptyState(
        icon = icon,
        title = "$title is on its way",
        body = "This module is being rebuilt for Android. Until then, use it on the web — everything stays in sync.",
        modifier = Modifier.fillMaxSize().padding(MitraDimens.pad),
    )
}
```

`di/FeatureModule.kt`:
```kotlin
package tech.beyondscale.mitra.feature.di

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase
import tech.beyondscale.mitra.domain.usecase.LoginUseCase
import tech.beyondscale.mitra.domain.usecase.SignupUseCase
import tech.beyondscale.mitra.feature.auth.ForgotPasswordViewModel
import tech.beyondscale.mitra.feature.auth.LoginViewModel
import tech.beyondscale.mitra.feature.auth.SignupViewModel
import tech.beyondscale.mitra.feature.settings.SettingsViewModel
import tech.beyondscale.mitra.feature.today.TodayViewModel

val featureModule = module {
    factoryOf(::LoginUseCase)
    factoryOf(::SignupUseCase)
    factoryOf(::ForgotPasswordUseCase)
    viewModelOf(::LoginViewModel)
    viewModelOf(::SignupViewModel)
    viewModelOf(::ForgotPasswordViewModel)
    viewModelOf(::TodayViewModel)
    viewModelOf(::SettingsViewModel)
}
```

- [ ] **Step 6: Build and test**

Run: `./gradlew :shared:feature:testAndroidHostTest :shared:feature:compileAndroidMain`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add mitra-mobile/shared/feature && git commit -m "feat(mobile): Today header, Settings (theme, modules, sign out), coming-soon placeholder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: app — root session gate, navigation, bottom bar, More sheet

**Files:**
- Create: `shared/app/src/commonMain/kotlin/tech/beyondscale/mitra/app/{RootViewModel,Destinations,MainShell,AppModules}.kt`
- Modify: `shared/app/src/commonMain/kotlin/tech/beyondscale/mitra/app/MitraApp.kt` (replace temporary body)
- Test: `shared/app/src/commonTest/kotlin/tech/beyondscale/mitra/app/{RootViewModelTest,SyncLabelTest}.kt`

**Interfaces:**
- Consumes: `featureModule`, `dataModule`, feature routes, design system, domain interfaces.
- Produces: `@Composable fun MitraApp(onDarkTheme: (Boolean) -> Unit = {})`; `fun appModules(baseUrl: String): List<Module>`; `RootViewModel(auth, prefs, sync)`; `internal fun syncLabel(status: SyncStatus): Pair<String, PillTone>?`.

- [ ] **Step 1: Write failing tests**

`RootViewModelTest.kt`:
```kotlin
package tech.beyondscale.mitra.app

import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakePreferencesRepository
import tech.beyondscale.mitra.testing.FakeSyncController
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RootViewModelTest : ViewModelTest() {
    @Test fun restoresSessionOnStartAndFollowsTheme() {
        val auth = FakeAuthRepository(); val prefs = FakePreferencesRepository(); val sync = FakeSyncController()
        val vm = RootViewModel(auth, prefs, sync)
        assertEquals(listOf("restore"), auth.calls)
        prefs.themeFlow.value = ThemeMode.Light
        assertEquals(ThemeMode.Light, vm.state.value.theme)
        vm.onForeground()
        assertEquals(1, sync.requests)
    }
}
```

`SyncLabelTest.kt`:
```kotlin
package tech.beyondscale.mitra.app

import tech.beyondscale.mitra.designsystem.components.PillTone
import tech.beyondscale.mitra.domain.model.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncLabelTest {
    @Test fun labels() {
        assertNull(syncLabel(SyncStatus.Idle))
        assertEquals("Syncing…" to PillTone.Accent, syncLabel(SyncStatus.Syncing(2)))
        assertEquals("Offline · 3 pending" to PillTone.Warning, syncLabel(SyncStatus.Offline(3)))
        assertEquals("Waiting to sync · 1" to PillTone.Neutral, syncLabel(SyncStatus.Retrying(1)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:app:testAndroidHostTest`
Expected: FAIL — `Unresolved reference 'RootViewModel'`.

- [ ] **Step 3: Implement**

`RootViewModel.kt`:
```kotlin
package tech.beyondscale.mitra.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.components.PillTone
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SyncController

data class RootUiState(
    val session: SessionState = SessionState.Unknown,
    val theme: ThemeMode = ThemeMode.Dark,
    val sync: SyncStatus = SyncStatus.Idle,
)

class RootViewModel(auth: AuthRepository, prefs: PreferencesRepository, private val sync: SyncController) : ViewModel() {
    val state: StateFlow<RootUiState> = combine(auth.session, prefs.theme, sync.status, ::RootUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState())

    val messages: Flow<String> = sync.messages

    init { viewModelScope.launch { auth.restore() } }

    fun onForeground() = sync.requestSync()
}

/** Top-bar pill text; null when everything is synced. */
internal fun syncLabel(status: SyncStatus): Pair<String, PillTone>? = when (status) {
    SyncStatus.Idle -> null
    is SyncStatus.Syncing -> "Syncing…" to PillTone.Accent
    is SyncStatus.Offline -> "Offline · ${status.pending} pending" to PillTone.Warning
    is SyncStatus.Retrying -> "Waiting to sync · ${status.pending}" to PillTone.Neutral
}
```

`Destinations.kt`:
```kotlin
package tech.beyondscale.mitra.app

import kotlinx.serialization.Serializable

@Serializable data object LoginDest
@Serializable data object SignupDest
@Serializable data object ForgotDest

@Serializable data object TodayDest
@Serializable data object TasksDest
@Serializable data object MoneyDest
@Serializable data object HabitsDest
@Serializable data object GoalsDest
@Serializable data object WellnessDest
@Serializable data object NotesDest
@Serializable data object AiDest
@Serializable data object SettingsDest
```

`MainShell.kt`:
```kotlin
package tech.beyondscale.mitra.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.components.Avatar
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SyncPill
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.feature.common.ComingSoonScreen
import tech.beyondscale.mitra.feature.settings.SettingsRoute
import tech.beyondscale.mitra.feature.today.TodayRoute

private data class Dest(val label: String, val icon: ImageVector, val route: Any)

private val TODAY = Dest("Today", MitraIcons.Sun, TodayDest)
private val TASKS = Dest("Tasks", MitraIcons.CheckSquare, TasksDest)
private val MONEY = Dest("Money", MitraIcons.Wallet, MoneyDest)
private val HABITS = Dest("Habits", MitraIcons.Repeat, HabitsDest)
private val GOALS = Dest("Goals", MitraIcons.Target, GoalsDest)
private val WELLNESS = Dest("Wellness", MitraIcons.Heart, WellnessDest)
private val NOTES = Dest("Notes", MitraIcons.Note, NotesDest)
private val AI = Dest("AI", MitraIcons.Sparkles, AiDest)
private val SETTINGS = Dest("Settings", MitraIcons.Settings, SettingsDest)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(user: User, sync: SyncStatus, messages: Flow<String>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }

    // Bottom bar: Today · Tasks · ＋ · Money (Habits if Money is off) · More
    val modules = user.settings.modules
    val tabs = listOf(TODAY, TASKS, if (modules.money) MONEY else HABITS)
    val more = buildList {
        if (modules.money) add(HABITS)
        add(GOALS)
        if (modules.wellness) add(WELLNESS)
        add(NOTES)
        if (modules.ai) add(AI)
        add(SETTINGS)
    }
    val entry by nav.currentBackStackEntryAsState()
    val current = (tabs + more).firstOrNull { d -> entry?.destination?.hierarchy?.any { it.hasRoute(d.route::class) } == true }

    fun go(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        containerColor = Mitra.colors.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MitraTopBar(title = current?.label ?: "Mitra", modifier = Modifier.statusBarsPadding()) {
                syncLabel(sync)?.let { (text, tone) -> SyncPill(text, tone) }
                IconButton(onClick = { go(SettingsDest) }) { Avatar(user.initials) }
            }
        },
        bottomBar = {
            BottomBar(
                tabs = tabs, current = current, moreSelected = current != null && current in more,
                onSelect = { go(it.route) }, onMore = { moreOpen = true },
                onAdd = { scope.launch { snackbar.showSnackbar("Quick add arrives with Tasks") } },
            )
        },
    ) { padding ->
        NavHost(nav, startDestination = TodayDest, modifier = Modifier.padding(padding)) {
            composable<TodayDest> { TodayRoute() }
            composable<TasksDest> { ComingSoonScreen("Tasks", MitraIcons.CheckSquare) }
            composable<MoneyDest> { ComingSoonScreen("Money", MitraIcons.Wallet) }
            composable<HabitsDest> { ComingSoonScreen("Habits", MitraIcons.Repeat) }
            composable<GoalsDest> { ComingSoonScreen("Goals", MitraIcons.Target) }
            composable<WellnessDest> { ComingSoonScreen("Wellness", MitraIcons.Heart) }
            composable<NotesDest> { ComingSoonScreen("Notes", MitraIcons.Note) }
            composable<AiDest> { ComingSoonScreen("AI", MitraIcons.Sparkles) }
            composable<SettingsDest> { SettingsRoute() }
        }
    }

    if (moreOpen) {
        ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = Mitra.colors.surface) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("All sections", style = Mitra.type.h3, color = Mitra.colors.text)
                more.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { d -> MoreTile(d, selected = d == current) { moreOpen = false; go(d.route) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MoreTile(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val c = Mitra.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentSoft else c.surface2)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(d.icon, contentDescription = null, tint = if (selected) c.accent else c.text)
        Text(d.label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = if (selected) c.accent else c.text)
    }
}

@Composable
private fun BottomBar(tabs: List<Dest>, current: Dest?, moreSelected: Boolean, onSelect: (Dest) -> Unit, onAdd: () -> Unit, onMore: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxWidth().background(c.navBg)) {
        HorizontalDivider(color = c.border)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            TabItem(tabs[0], current == tabs[0]) { onSelect(tabs[0]) }
            TabItem(tabs[1], current == tabs[1]) { onSelect(tabs[1]) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.offset(y = (-10).dp).size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.accent)
                        .clickable(role = Role.Button, onClickLabel = "Quick add", onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(MitraIcons.Plus, contentDescription = "Quick add", tint = c.onAccent, modifier = Modifier.size(26.dp)) }
            }
            TabItem(tabs[2], current == tabs[2]) { onSelect(tabs[2]) }
            TabItem(Dest("More", MitraIcons.Grid, Unit), moreSelected, onMore)
        }
    }
}

@Composable
private fun RowScope.TabItem(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Mitra.colors.accent else Mitra.colors.muted
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(24.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Mitra.colors.accent else Color.Transparent))
        Spacer(Modifier.height(6.dp))
        Icon(d.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(d.label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp), color = color)
    }
}
```

`MitraApp.kt` (replace the whole file):
```kotlin
package tech.beyondscale.mitra.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.MitraMark
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraTheme
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.feature.auth.ForgotPasswordRoute
import tech.beyondscale.mitra.feature.auth.LoginRoute
import tech.beyondscale.mitra.feature.auth.SignupRoute

/** Root of the app on every platform. [onDarkTheme] lets the host style system bars. */
@Composable
fun MitraApp(onDarkTheme: (Boolean) -> Unit = {}, vm: RootViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dark = when (state.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.Auto -> isSystemInDarkTheme()
    }
    LaunchedEffect(dark) { onDarkTheme(dark) }

    MitraTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(Mitra.colors.bg)) {
            when (val s = state.session) {
                SessionState.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MitraMark() }
                SessionState.SignedOut -> AuthNav()
                is SessionState.SignedIn -> MainShell(s.user, state.sync, vm.messages)
            }
        }
    }
}

@Composable
private fun AuthNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = LoginDest) {
        composable<LoginDest> { LoginRoute(onSignup = { nav.navigate(SignupDest) }, onForgot = { nav.navigate(ForgotDest) }) }
        composable<SignupDest> { SignupRoute(onSignIn = { nav.popBackStack() }) }
        composable<ForgotDest> { ForgotPasswordRoute(onBack = { nav.popBackStack() }) }
    }
}
```

`AppModules.kt`:
```kotlin
package tech.beyondscale.mitra.app

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tech.beyondscale.mitra.data.di.dataModule
import tech.beyondscale.mitra.feature.di.featureModule

/** Everything except platform bindings (SqlDriver, SecureStore, HttpClientEngine, SyncScheduler). */
fun appModules(baseUrl: String): List<Module> = listOf(
    dataModule(baseUrl),
    featureModule,
    module { viewModelOf(::RootViewModel) },
)
```

- [ ] **Step 4: Run tests and build**

Run: `./gradlew :shared:app:testAndroidHostTest :shared:app:compileAndroidMain`
Expected: PASS (2 tests), BUILD SUCCESSFUL. *(`:androidApp` will not compile until Task 12 — `MitraApp` now needs Koin.)*

- [ ] **Step 5: Commit**

```bash
git add mitra-mobile/shared/app && git commit -m "feat(mobile): session-gated root, bottom bar with quick-add slot, More sheet, navigation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: androidApp — platform bindings, Keystore store, WorkManager sync, app icon

**Files:**
- Create: `shared/data/src/androidMain/kotlin/tech/beyondscale/mitra/data/platform/{KeystoreSecureStore,AndroidDataModule}.kt`
- Create: `androidApp/src/main/kotlin/tech/beyondscale/mitra/android/{MitraApplication,SyncWorker}.kt`
- Modify: `androidApp/src/main/kotlin/tech/beyondscale/mitra/android/MainActivity.kt` (replace)
- Modify: `androidApp/src/main/AndroidManifest.xml` (add `android:name` + icon)
- Create: `androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (copied)

**Interfaces:**
- Consumes: `appModules`, `MitraApp`, `SyncEngine`, `SyncScheduler`, `SyncController`, `SecureStore`, `MitraDb.Schema`.
- Produces: `fun androidDataModule(context: Context): Module`; `class KeystoreSecureStore(context) : SecureStore`; `class SyncWorker`; `class WorkManagerSyncScheduler(context) : SyncScheduler`; installable debug APK.

- [ ] **Step 1: Android platform bindings in `data/androidMain`**

`KeystoreSecureStore.kt`:
```kotlin
package tech.beyondscale.mitra.data.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import tech.beyondscale.mitra.data.remote.SecureStore

/** AES-GCM with a non-exportable Android Keystore key; ciphertext (IV ‖ data) kept in private SharedPreferences. */
class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs = context.getSharedPreferences("mitra_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    override fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        }.getOrNull() // unreadable (e.g. key reset) → treat as signed out
    }

    override fun put(key: String, value: String?) {
        if (value == null) { prefs.edit().remove(key).apply(); return }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private companion object {
        const val ALIAS = "mitra_secure_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
```

`AndroidDataModule.kt`:
```kotlin
package tech.beyondscale.mitra.data.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.SecureStore

/** Android bindings for dataModule(); the app adds its SyncScheduler. */
fun androidDataModule(context: Context): Module = module {
    single<SqlDriver> { AndroidSqliteDriver(MitraDb.Schema, context, "mitra.db") }
    single<SecureStore> { KeystoreSecureStore(context) }
    single<HttpClientEngine> { OkHttp.create() }
}
```

- [ ] **Step 2: App classes**

`SyncWorker.kt`:
```kotlin
package tech.beyondscale.mitra.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncScheduler

/** Drains the outbox once a network is available; WorkManager retries with exponential backoff. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val engine: SyncEngine by inject()

    override suspend fun doWork(): Result = when (engine.drain()) {
        SyncEngine.Result.Drained, SyncEngine.Result.SignedOut -> Result.success()
        SyncEngine.Result.Offline, SyncEngine.Result.Retry -> Result.retry()
    }
}

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    override fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("mitra-sync", ExistingWorkPolicy.KEEP, request)
    }
}
```

`MitraApplication.kt`:
```kotlin
package tech.beyondscale.mitra.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import tech.beyondscale.mitra.app.appModules
import tech.beyondscale.mitra.data.platform.androidDataModule
import tech.beyondscale.mitra.data.sync.SyncScheduler
import tech.beyondscale.mitra.domain.repository.SyncController

class MitraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.API_URL.isNotBlank()) { "API URL missing: pass -Pmitra.releaseApiUrl=https://…/api/ for release builds" }
        startKoin {
            androidContext(this@MitraApplication)
            modules(
                listOf(androidDataModule(this@MitraApplication), module { single<SyncScheduler> { WorkManagerSyncScheduler(this@MitraApplication) } }) +
                    appModules(BuildConfig.API_URL),
            )
        }
        // Network back → flush queued changes right away.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { get<SyncController>().requestSync() }
        })
    }
}
```

`MainActivity.kt` (replace):
```kotlin
package tech.beyondscale.mitra.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.android.inject
import tech.beyondscale.mitra.app.MitraApp
import tech.beyondscale.mitra.domain.repository.SyncController

class MainActivity : ComponentActivity() {
    private val sync: SyncController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MitraApp(onDarkTheme = { dark ->
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            })
        }
    }

    override fun onStart() {
        super.onStart()
        sync.requestSync()
    }
}
```

- [ ] **Step 3: Manifest and icon**

In `androidApp/src/main/AndroidManifest.xml`, change the `<application` opening tag attributes to:
```xml
    <application
        android:name=".MitraApplication"
        android:label="Mitra"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false"
        android:usesCleartextTraffic="${cleartext}"
        android:enableOnBackInvokedCallback="true"
        android:theme="@style/Theme.Mitra">
```

```bash
cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile"
mkdir -p androidApp/src/main/res/mipmap-xxxhdpi && cp ../mitra-frontend/public/icons/icon-192.png androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png
```

- [ ] **Step 4: Build everything and run all tests**

Run: `./gradlew testAndroidHostTest :androidApp:assembleDebug`
Expected: all tests PASS; `BUILD SUCCESSFUL`; APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add mitra-mobile && git commit -m "feat(mobile): Android bindings — Keystore cookie store, WorkManager sync, app wiring

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: End-to-end check against the local backend + README

**Files:**
- Create: `mitra-mobile/README.md`

**Interfaces:**
- Consumes: the debug APK from Task 12, a running `mitra-backend` (Postgres + Redis required), an emulator or phone.

- [ ] **Step 1: Backend up**

Run: `curl -s http://localhost:4000/api/health`
Expected: a JSON health response. If it fails, ask the user to start the backend (`cd mitra-backend && pnpm dev`, with Postgres and Redis running) — do not start databases yourself.

- [ ] **Step 2: Device**

Run: `~/Library/Android/sdk/platform-tools/adb devices`
If no device is listed: **ask the user** whether to (a) plug in a phone with USB debugging (then set `mitra.apiUrl=http://<Mac LAN IP>:4000/api/` in `local.properties` and rebuild), or (b) download an emulator (~3 GB: `sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"`, then `avdmanager create avd -n mitra -k "system-images;android-36;google_apis;arm64-v8a"` and `emulator -avd mitra &`). Do not download without a yes.

- [ ] **Step 3: Install and launch**

```bash
./gradlew :androidApp:installDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n tech.beyondscale.mitra.debug/tech.beyondscale.mitra.android.MainActivity
```

- [ ] **Step 4: Walk the checklist** (take an `adb exec-out screencap -p > /tmp/…png` screenshot at each ✓ and look at it)

1. Cold start shows the Login screen in Noir dark, Manrope font, logo mark.
2. Wrong password → red banner "Email/username or password is incorrect"; still on Login.
3. Correct credentials (an existing web account) → Today shows "Good …, <first name>" and the date line; bottom bar Today · Tasks · ＋ · Money · More.
4. Force-stop and relaunch (`adb shell am force-stop tech.beyondscale.mitra.debug`) → straight into Today, no Login (cookie persisted).
5. More → Settings → switch theme to Light → whole app turns light, status-bar icons dark.
6. Toggle Money off → Money tab becomes Habits. Check the web app (or `GET /api/auth/me`): `settings.modules.money` is `false`.
7. Airplane mode on (`adb shell cmd connectivity airplane-mode enable`) → toggle Wellness off → top bar pill "Offline · 1 pending". Airplane mode off → pill disappears within a few seconds; web reflects the change.
8. Sign out → back to Login; relaunch stays on Login.
9. Back gesture from Settings returns to Today; from Today it leaves the app.

If any item fails, use superpowers:systematic-debugging before changing code.

- [ ] **Step 5: README** — `mitra-mobile/README.md`

````markdown
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

## Test
`./gradlew testAndroidHostTest` — all shared unit tests on the JVM.

## Icons
After changing `mitra-frontend/src/lib/icons.ts`:
`python3 -I tools/gen_icons.py ../mitra-frontend/src/lib/icons.ts shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/icons/MitraIcons.kt tech.beyondscale.mitra.designsystem.icons`
````

- [ ] **Step 6: Commit**

```bash
git add mitra-mobile/README.md && git commit -m "docs(mobile): README with build, run and test instructions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Out of scope for Phase 1 (next plans)

Phase 2 Tasks (+ Quick Add sheet, swipe gestures, Tasks widget) · Phase 3 Money (+ Money widget) · Phase 4 Habits + Goals · Phase 5 Wellness + Notes + AI · Phase 6 polish (notifications, shortcuts, share target, biometric lock, profile/export/delete, onboarding, native password reset via app links, pinned tabs, release signing).
