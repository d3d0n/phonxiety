package com.phonxiety.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/** A library that draws its screens with Compose, with preview tooling in debug builds. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(AndroidLibraryConventionPlugin::class.java)
            configureCompose(extensions.getByType<LibraryExtension>())
            dependencies {
                add("debugImplementation", library("androidx-compose-ui-tooling"))
            }
        }
    }
}
