plugins {
    id("phonxiety.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.phonxiety.bridge.root"

    // Module payload for ModuleZipBuilder: the same files mise-tasks/phone/build
    // ships, merged into the app's assets (library assets are packaged into :app).
    sourceSets.named("main") {
        assets.srcDir("../../magisk")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
