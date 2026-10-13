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
