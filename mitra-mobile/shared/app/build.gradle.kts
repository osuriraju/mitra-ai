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
