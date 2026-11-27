package com.phonxiety.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** One SDK level for every module, so an upgrade is a single edit. */
internal object AndroidSdk {
    const val COMPILE_API = 37
    const val COMPILE_MINOR = 1
    const val MIN_API = 34
    const val TARGET_API = 37
    const val BUILD_TOOLS = "37.0.0"
}

/**
 * The configuration every module shares: SDK levels and one Java level.  A
 * module script then only owns what makes that module different from its
 * neighbours.
 */
internal fun Project.configureAndroidSdk(android: CommonExtension) {
    android.compileSdk {
        version = release(AndroidSdk.COMPILE_API) { minorApiLevel = AndroidSdk.COMPILE_MINOR }
    }
    android.buildToolsVersion = AndroidSdk.BUILD_TOOLS
    android.defaultConfig.minSdk { version = release(AndroidSdk.MIN_API) }
    android.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * The lint policy of the code that ships: any warning fails the build.  The
 * benchmark module ships nothing and keeps lint's own defaults.
 */
internal fun configureStrictLint(android: CommonExtension) {
    android.lint.apply {
        warningsAsErrors = true
        abortOnError = true
    }
}

/**
 * JVM unit tests see the module's resources and get defaults instead of
 * exceptions from stubbed `android.*` calls, which is what both the
 * Robolectric-backed and the plain tests in this build expect.
 */
internal fun configureJvmUnitTests(android: CommonExtension) {
    android.testOptions.unitTests {
        isReturnDefaultValues = true
        isIncludeAndroidResources = true
    }
}

/**
 * Turns on Compose: a Kotlin compiler mode, so the compiler plugin comes with it.
 * The stability file is part of the switch, so no module can enable Compose
 * without the model types its screens carry being declared stable.
 */
internal fun Project.configureCompose(android: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    android.buildFeatures.compose = true
    val stability = rootProject.layout.projectDirectory.file(STABILITY_CONFIGURATION)
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFiles.add(stability)
    }
    // The compiler reads the file through the plugin, which does not declare it
    // as a task input; without this an edited allowlist would leave every screen
    // on its previous skip decisions until something else rebuilt it.
    tasks.withType<KotlinCompile>().configureEach {
        inputs.file(stability).withPropertyName("composeStabilityConfiguration")
    }
}

/** One stability file for the whole build, next to the root script. */
private const val STABILITY_CONFIGURATION = "compose-stability.conf"

/** A library alias of the catalog of the build that applies this convention. */
internal fun Project.library(alias: String): Provider<MinimalExternalModuleDependency> {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    return catalog.findLibrary(alias).orElseThrow {
        IllegalStateException("libs.versions.toml declares no library alias '$alias'")
    }
}
