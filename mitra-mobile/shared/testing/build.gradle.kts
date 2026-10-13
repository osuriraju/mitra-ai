plugins { id("mitra.kmp") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(kotlin("test"))
            api(libs.coroutines.test)
            api(libs.turbine)
        }
        // kotlin-test in a *main* source set doesn't auto-select the JUnit actuals (only test compilations do).
        androidMain.dependencies { api(kotlin("test-junit")) }
    }
}
