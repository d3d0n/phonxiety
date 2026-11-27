plugins {
    id("phonxiety.android.application")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

// -- Release signing ---------------------------------------------------------
// A release APK must never be signed with the debug key by accident: the key is
// how the phone trusts an update, and a debug-signed build installed over a
// release one is a signature mismatch at best.  `mise run phone:build --release`
// sets PHONXIETY_REQUIRE_SIGNING=1; a plain build keeps the debug-key fallback
// so a developer can install a test APK without owning the release key.
val releaseKeystore = System.getenv("PHONXIETY_KEYSTORE").orEmpty()
val releaseKeystorePassword = System.getenv("PHONXIETY_KEYSTORE_PASSWORD").orEmpty()
val releaseKeyAlias = System.getenv("PHONXIETY_KEY_ALIAS").orEmpty()
val releaseKeyPassword = System.getenv("PHONXIETY_KEY_PASSWORD").orEmpty()
val signingRequired = System.getenv("PHONXIETY_REQUIRE_SIGNING") == "1"

if (signingRequired) {
    val missing = buildList {
        if (releaseKeystore.isBlank()) add("PHONXIETY_KEYSTORE")
        if (releaseKeystorePassword.isBlank()) add("PHONXIETY_KEYSTORE_PASSWORD")
        if (releaseKeyAlias.isBlank()) add("PHONXIETY_KEY_ALIAS")
        if (releaseKeyPassword.isBlank()) add("PHONXIETY_KEY_PASSWORD")
    }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "PHONXIETY_REQUIRE_SIGNING=1 needs a release key; unset: ${missing.joinToString()}. " +
                "Run without --require-signing (and without that variable) for a debug-key build."
        )
    }
    if (!file(releaseKeystore).isFile) {
        throw GradleException("PHONXIETY_KEYSTORE points at $releaseKeystore, which is not a file")
    }
}

android {
    namespace = "com.phonxiety.bridge"

    defaultConfig {
        applicationId = "com.phonxiety.bridge"
        versionCode = 5
        versionName = "0.4.1"

        ndk {
            // `mise run phone:iroh` builds only arm64-v8a (Pixel 6a). Without
            // this filter JNA's @aar contributes libjnidispatch.so for five
            // more ABIs that can never be paired with libiroh_ffi.so.
            abiFilters += "arm64-v8a"
        }
    }

    // The :benchmark test module runs the release APK: the profile has to come
    // from the build users install, and Macrobenchmark needs a non-debuggable,
    // profileable process.
    testBuildType = "release"

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

    signingConfigs {
        // A privileged app only needs *a* valid signature; it does not need the
        // platform certificate because the Magisk privapp allowlist grants the
        // privileged permissions.  Set the PHONXIETY_KEYSTORE* environment
        // variables to override the debug key.  Passwords are read from the
        // environment and never written into build output.
        if (releaseKeystore.isNotBlank()) {
            create("phonxietyRelease") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and obfuscates the release APK; the keep rules in
            // proguard-rules.pro cover what the reflective clients need — JNA's
            // Structure and native registration, the uniffi bindings, the
            // kotlinx.serialization models and the navigation keys that
            // SavedState restores by name.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("phonxietyRelease")
                ?: signingConfigs.getByName("debug")
        }
    }
}

// The profile is collected on a device by :benchmark and committed next to the
// release variant it belongs to (`src/release/generated/baselineProfiles`).
// Generation is manual: `./gradlew :app:generateBaselineProfile`.
baselineProfile {
    saveInSrc = true
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

    // Installs the baseline profile collected by :benchmark; without one in the
    // sources it does nothing.
    implementation(libs.androidx.profileinstaller)

    // Generates the profile; a test-only module that is never shipped in the app.
    baselineProfile(project(":benchmark"))

    // Google code scanner: pairs by scanning the daemon's QR code without a
    // camera permission.
    implementation(libs.play.services.code.scanner)
    implementation(platform(libs.androidx.compose.bom))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    // The entry-point tests render the real activity and read the destination it drew.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The hang-up tests speak the daemon's outcome type through CallEnd's seam.
    testImplementation(project(":core:data"))
}
