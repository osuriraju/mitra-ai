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
