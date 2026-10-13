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
