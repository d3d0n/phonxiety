plugins {
    id("phonxiety.android.compose")
}

android {
    namespace = "com.phonxiety.bridge.feature.widgets"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:device"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
}
