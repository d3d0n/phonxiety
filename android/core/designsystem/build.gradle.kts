plugins {
    id("phonxiety.android.compose")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.phonxiety.bridge.designsystem"

    testFixtures {
        enable = true
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    api(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.accessibility.check)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testFixturesApi(platform(libs.androidx.compose.bom))
    testFixturesApi(libs.androidx.compose.ui.test.junit4)
    testFixturesApi(libs.roborazzi.accessibility.check)
    testFixturesImplementation(libs.roborazzi)
    testFixturesImplementation(libs.roborazzi.compose)
    testFixturesImplementation(libs.junit)
}
