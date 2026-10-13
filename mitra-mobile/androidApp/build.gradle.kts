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
