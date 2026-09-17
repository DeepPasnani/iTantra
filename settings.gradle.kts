pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "itantra-offline-voice-mesh"

include(
    ":core-messaging",
    ":transport-api",
    ":core-routing",
    ":core-reliability",
    ":transport-wifi",
    ":app",
)
