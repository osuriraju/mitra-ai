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
