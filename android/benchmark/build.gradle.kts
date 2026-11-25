plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Macrobenchmark and baseline-profile collection.  The module is a
// `com.android.test` producer: it never ships in the app, it drives the release
// APK on a connected device (or a Gradle-managed device) and writes the profile
// back into `:app`.
android {
    namespace = "com.phonxiety.bridge.benchmark"

    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk {
            version = release(34)
        }
        targetSdk {
            version = release(37)
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The profile is collected from the same APK users install: minified,
    // non-debuggable release. The app module's `testBuildType` picks it.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    testOptions {
        // Macrobenchmark needs a non-debuggable target and a profileable process;
        // both come from the release build type of :app.
        animationsDisabled = true
    }
}

baselineProfile {
    // Uses the device `adb` sees.  A physical device must run Android 13 (API 33)
    // or be rooted; an emulator must be an AOSP image, because the profile
    // collector has to set the process's compilation mode.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
