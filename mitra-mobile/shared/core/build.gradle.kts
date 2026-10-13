plugins { id("mitra.kmp") }
kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.datetime) }
    }
}
