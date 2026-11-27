plugins {
    id("phonxiety.android.feature")
}

android {
    namespace = "com.phonxiety.bridge.feature.livecall"

    // The shared `/app/v1` contracts are unit-test inputs here too: the events pages
    // run through the live call's own reducer.
    sourceSets {
        getByName("test") {
            resources.directories.add("../../../contracts")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
