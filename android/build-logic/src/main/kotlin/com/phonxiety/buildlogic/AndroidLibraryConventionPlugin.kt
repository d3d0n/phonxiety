package com.phonxiety.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** An Android library on the shared SDK, Java and lint policy. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            val android = extensions.getByType<LibraryExtension>()
            configureAndroidSdk(android)
            configureJvmUnitTests(android)
        }
    }
}
