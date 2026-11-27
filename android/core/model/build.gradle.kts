plugins {
    id("phonxiety.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.phonxiety.bridge.model"
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
}
