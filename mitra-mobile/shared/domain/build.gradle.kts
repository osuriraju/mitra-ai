plugins { id("mitra.kmp") }
kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":shared:core")) }
        commonTest.dependencies { implementation(project(":shared:testing")) }
    }
}
