plugins {
    // 8.9.1 is the documented minimum for compileSdk 36; 8.13 is the latest
    // 8.x and is what recent Android Studio releases expect. Requires Gradle
    // 8.13, which gradle/wrapper/gradle-wrapper.properties pins.
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
