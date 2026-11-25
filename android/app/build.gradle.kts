plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.phonxiety.bridge"

    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.phonxiety.bridge"
        minSdk {
            version = release(34)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            // `mise run phone:iroh` builds only arm64-v8a (Pixel 6a). Without
            // this filter JNA's @aar contributes libjnidispatch.so for five
            // more ABIs that can never be paired with libiroh_ffi.so.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            // Keep libiroh_ffi.so and libjnidispatch.so uncompressed and
            // 16 KB page-aligned inside the APK (extractNativeLibs=false,
            // injected into the merged manifest). The module installs this
            // APK into /system/priv-app, where there is no writable per-app
            // directory to extract native libraries into, and Android 15+
            // devices may use 16 KB pages. With this the linker loads the
            // libraries straight out of the APK for both JNA's
            // System.loadLibrary("jnidispatch") and System.loadLibrary("iroh_ffi").
            useLegacyPackaging = false
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
    }

    signingConfigs {
        // A privileged app only needs *a* valid signature; it does not need the
        // platform certificate because the Magisk privapp allowlist grants the
        // privileged permissions.  Set the PHONXIETY_KEYSTORE* environment
        // variables to override the debug key.  Passwords are read from the
        // environment and never written into build output.
        val store = System.getenv("PHONXIETY_KEYSTORE")
        if (!store.isNullOrBlank()) {
            create("phonxietyRelease") {
                storeFile = file(store)
                storePassword = System.getenv("PHONXIETY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHONXIETY_KEY_ALIAS")
                keyPassword = System.getenv("PHONXIETY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("phonxietyRelease")
                ?: signingConfigs.getByName("debug")
        }
    }

    // No baseline profile is generated for this app, so the installer that
    // Activity and Lifecycle pull transitively is dead weight in the manifest.
    configurations.configureEach {
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:device"))
    // Root layer ships the module payload as library assets merged into :app.
    implementation(project(":core:root"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:livecall"))
    implementation(project(":feature:calls"))
    implementation(project(":feature:lab"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:updates"))
    implementation(project(":feature:doctor"))
    implementation(project(":feature:widgets"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)

    // Google code scanner: pairs by scanning the daemon's QR code without a
    // camera permission.
    implementation(libs.play.services.code.scanner)
    implementation(platform(libs.androidx.compose.bom))

    testImplementation(libs.junit)
}
