plugins {
    base
}

// Root orchestrator for multi-project build (Android + Python)
allprojects {
    group = "org.example"
    version = "1.0-SNAPSHOT"
}

// Aggregate verification: Android unit tests + Python pytest
// Also provide a root-level pyTest task so this repo can run tests without a Gradle subproject in pc_controller.

// Detect whether an Android SDK is available on this machine
val localPropsFile = rootProject.file("local.properties")
val props = java.util.Properties()
var hasAndroidSdk = false
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { props.load(it) }
    val sdkDir = props.getProperty("sdk.dir")
    if (sdkDir != null) {
        hasAndroidSdk = file(sdkDir).exists()
    }
}
if (!hasAndroidSdk) {
    val envSdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    if (envSdk != null) {
        hasAndroidSdk = file(envSdk).exists()
    }
}

// Root-level pytest task
val pyTest = tasks.register<Exec>("pyTest") {
    group = "verification"
    description = "Run Python pytest suite as defined by pytest.ini"
    workingDir = rootDir
    commandLine("python3", "-m", "pytest")
}

// Combined check task
tasks.register("checkAll") {
    group = "verification"
    description = if (hasAndroidSdk) {
        "Run Android unit tests and Python pytest"
    } else {
        "Run Python pytest (Android SDK not found; skipping Android unit tests)"
    }
    dependsOn(pyTest)
    if (hasAndroidSdk) {
        // Android unit test task (Debug)
        dependsOn(":android_sensor_node:app:testDebugUnitTest")
    } else {
        doFirst {
            println("[checkAll] Android SDK not found; skipping Android unit tests")
        }
    }
}

// Packaging tasks
tasks.register("packageAll") {
    group = "build"
    description = "Package PC Controller exe (PyInstaller) and Android APK (release)"
    dependsOn(":pc_controller:assemblePcController")
    dependsOn(":android_sensor_node:app:assembleRelease")
}

// Placeholder classes task to satisfy IDEs/tools that invoke :classes at the root
// This repository's root project does not produce Java classes.
tasks.register("classes") {
    group = "build"
    description =
        "No-op placeholder for root project; use module-specific tasks instead (e.g., :android_sensor_node:app:assembleDebug, :pc_controller:pyInstaller)."
}


// Placeholder testClasses task to satisfy tools that invoke :testClasses at the root
// This repository's root project does not have Java tests; use module-specific tasks instead.
tasks.register("testClasses") {
    group = "verification"
    description =
        "No-op placeholder for root project; use module-specific tasks instead (e.g., :android_sensor_node:app:testDebugUnitTest, :pc_controller:pyTest)."
}
