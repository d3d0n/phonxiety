package com.phonxiety.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** The installed app: the shared SDK policy, a target level and Compose. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            val android = extensions.getByType<ApplicationExtension>()
            configureAndroidSdk(android)
            configureStrictLint(android)
            android.defaultConfig.targetSdk { version = release(AndroidSdk.TARGET_API) }
            // The app is the module that ships: lint has to see its libraries too.
            android.lint.checkDependencies = true
            configureJvmUnitTests(android)
            configureCompose(android)
        }
    }
}
