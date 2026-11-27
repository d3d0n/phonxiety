package com.phonxiety.buildlogic

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** A test-only module that instruments the app on a device. */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            val android = extensions.getByType<TestExtension>()
            configureAndroidSdk(android)
            android.defaultConfig.targetSdk { version = release(AndroidSdk.TARGET_API) }
        }
    }
}
