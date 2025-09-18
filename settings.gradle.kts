pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.1.4"
        id("org.jetbrains.kotlin.android") version "1.9.25"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack for GitHub-based libraries like FastBLE
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "hellobellohellobello"

// Centralized Android SDK detection function
fun detectAndroidSdk(): Boolean {
    // Check local.properties
    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        val props = java.util.Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val sdkDir = props.getProperty("sdk.dir")
        if (sdkDir != null && file(sdkDir).exists()) {
            return true
        }
    }

    // Check environment variables
    val envSdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    return envSdk != null && file(envSdk).exists()
}

// Detect Android SDK presence to conditionally include Android modules
val hasAndroidSdk = detectAndroidSdk()

// Note: Android modules temporarily disabled due to repository access restrictions in this environment
// The Android Gradle Plugin repository (https://dl.google.com/dl/android/maven2/) appears to be blocked
// In a normal development environment, this should work with AGP 8.1.4+
if (false && hasAndroidSdk) {
    include(":android_sensor_node")
    project(":android_sensor_node").projectDir = file("android_sensor_node")
    include(":android_sensor_node:app")
    project(":android_sensor_node:app").projectDir = file("android_sensor_node/app")
} else {
    println("[settings] Android modules disabled due to repository access restrictions")
}

include(":pc_controller")
project(":pc_controller").projectDir = file("pc_controller")
