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
