plugins {
    id("phonxiety.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.phonxiety.bridge.data"

    // The shared `/app/v1` contracts are unit-test inputs: Gradle tracks them, so the
    // build cache cannot serve a test run made against older files.
    sourceSets {
        getByName("test") {
            resources.directories.add("../../../contracts")
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:device"))
    // Module update, provider detection and the kept APK rollback.
    implementation(project(":core:root"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
}
