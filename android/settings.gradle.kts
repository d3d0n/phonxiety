import org.gradle.api.initialization.resolve.RepositoriesMode

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

rootProject.name = "phonxiety-android"

// Surfaces and host.
include(":app")
include(":benchmark")
include(":feature:onboarding")
include(":feature:dashboard")
include(":feature:livecall")
include(":feature:calls")
include(":feature:lab")
include(":feature:settings")
include(":feature:updates")
include(":feature:doctor")
include(":feature:widgets")

// Core layers.
include(":core:model")
include(":core:designsystem")
include(":core:data")
include(":core:device")
include(":core:root")
