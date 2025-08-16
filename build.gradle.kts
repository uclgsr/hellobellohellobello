plugins {
    base
}

// Root orchestrator for multi-project build (Android + Python)
allprojects {
    group = "org.example"
    version = "1.0-SNAPSHOT"
}

// Aggregate verification: Android unit tests + Python pytest
tasks.register("checkAll") {
    group = "verification"
    description = "Run Android unit tests and Python pytest"
    dependsOn(":pc_controller:pyTest")
    // Android unit test task (Debug)
    dependsOn(":android_sensor_node:app:testDebugUnitTest")
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
