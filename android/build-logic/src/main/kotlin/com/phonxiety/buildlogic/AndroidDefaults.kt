package com.phonxiety.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** One SDK level for every module, so an upgrade is a single edit. */
internal object AndroidSdk {
    const val COMPILE_API = 37
    const val COMPILE_MINOR = 1
    const val MIN_API = 34
    const val TARGET_API = 37
    const val BUILD_TOOLS = "37.0.0"
}

/**
 * The configuration every module shares: SDK levels, one Java level and one
 * lint severity policy.  A module script then only owns what makes that module
 * different from its neighbours.
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

/** Turns on Compose: a Kotlin compiler mode, so the compiler plugin comes with it. */
internal fun Project.configureCompose(android: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    android.buildFeatures.compose = true
}

/** A library alias of the catalog of the build that applies this convention. */
internal fun Project.library(alias: String): Provider<MinimalExternalModuleDependency> {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    return catalog.findLibrary(alias).orElseThrow {
        IllegalStateException("libs.versions.toml declares no library alias '$alias'")
    }
}
