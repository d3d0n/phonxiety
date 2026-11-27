plugins {
    `kotlin-dsl`
}

group = "com.phonxiety.buildlogic"

// The plugins below stay off this build's runtime classpath on purpose: the
// app's root script declares each of them with `apply false`, so the very
// same classes are already loaded for every project and the conventions only
// need their public DSL at compile time.
dependencies {
    compileOnly(libs.androidGradlePlugin)
    compileOnly(libs.composeCompilerGradlePlugin)
    compileOnly(libs.hiltGradlePlugin)
    compileOnly(libs.kotlinGradlePlugin)
    compileOnly(libs.kspGradlePlugin)
    compileOnly(libs.roborazziGradlePlugin)
}

gradlePlugin {
    // Each convention publishes one id; a module script applies that id.
    val conventions = listOf(
        "library" to "AndroidLibraryConventionPlugin",
        "compose" to "AndroidComposeConventionPlugin",
        "feature" to "AndroidFeatureConventionPlugin",
        "application" to "AndroidApplicationConventionPlugin",
        "test" to "AndroidTestConventionPlugin"
    )
    for ((name, type) in conventions) {
        plugins.register(name) {
            id = "phonxiety.android.$name"
            implementationClass = "com.phonxiety.buildlogic.$type"
        }
    }
}
