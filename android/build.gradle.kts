// The Android platform is API 37.0 (Android 17).  AGP 8.x cannot compile that
// platform; the first AGP line that supports API 37 is 9.x, and AGP 9.5.0
// requires Gradle 9.6.0.  AGP 9 also provides Kotlin compilation directly, so
// there is deliberately no org.jetbrains.kotlin.android plugin here.
plugins {
    id("com.android.application") version "9.5.0" apply false
}
