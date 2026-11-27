package com.phonxiety.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * A feature module: Compose, dependency injection through Hilt's KSP
 * processor, golden-image tests, and the one test stack every screen shares, so
 * a module script lists only what its screens actually use.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(AndroidComposeConventionPlugin::class.java)
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            pluginManager.apply("io.github.takahirom.roborazzi")

            dependencies {
                add("testImplementation", testFixtures(project(":core:designsystem")))
                for (alias in sharedTestLibraries) {
                    add("testImplementation", library(alias))
                }
                add("debugImplementation", library("androidx-compose-ui-tooling"))
                add("debugImplementation", library("androidx-compose-ui-test-manifest"))
            }
        }
    }

    private companion object {
        /**
         * JUnit, coroutines test, Turbine, Robolectric and the Compose/Roborazzi
         * assertion and golden tooling.  Every feature drives its screen through
         * this set, whether or not a given screen happens to use all of it.
         */
        val sharedTestLibraries = listOf(
            "junit",
            "kotlinx-coroutines-test",
            "turbine",
            "robolectric",
            "androidx-test-junit",
            "androidx-compose-ui-test-junit4",
            "roborazzi",
            "roborazzi-compose",
            "roborazzi-accessibility-check"
        )
    }
}
