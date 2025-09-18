plugins {
    base
}

// Root orchestrator for multi-project build (Android + Python)
allprojects {
    group = "org.hellobellohellobello"
    version = "1.0-SNAPSHOT"
}

// Performance: Enable build caching and parallel execution
gradle.startParameter.apply {
    isBuildCacheEnabled = true
    maxWorkerCount = Runtime.getRuntime().availableProcessors()
}

// Aggregate verification: Android unit tests + Python pytest
// Also provide a root-level pyTest task so this repo can run tests without a Gradle subproject in pc_controller.

// Detect whether an Android SDK is available on this machine
fun detectAndroidSdk(): Boolean {
    // Check local.properties
    val localPropsFile = rootProject.file("local.properties")
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

val hasAndroidSdk = detectAndroidSdk()

// Detect whether pytest is available (configuration cache compatible)
fun isPytestAvailable(): Boolean {
    // Check if pytest module is available without running external process during configuration
    // This will be checked at execution time instead to avoid configuration cache issues
    return true // Default to true, actual check happens at task execution
}

// Root-level pytest task - Configuration cache compatible
tasks.register<Exec>("pyTest") {
    group = "verification"
    description = "Run Python pytest suite as defined by pytest.ini"
    workingDir = file(".")
    commandLine("python3", "-m", "pytest")

    // Configuration cache compatible - check availability at execution time
    doFirst {
        val pytestAvailable = try {
            val process = ProcessBuilder("python3", "-m", "pytest", "--version")
                .directory(file("."))
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
        
        if (!pytestAvailable) {
            throw RuntimeException("pytest module not available; install with: python3 -m pip install pytest")
        }
    }
}

// Combined check task
tasks.register("checkAll") {
    group = "verification"
    description = if (hasAndroidSdk) {
        "Run Android unit tests and Python pytest"
    } else {
        "Run Python pytest (Android SDK not found; skipping Android unit tests)"
    }
    dependsOn("pyTest")
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
    description = if (hasAndroidSdk) {
        "Package PC Controller exe (PyInstaller) and Android APK (release)"
    } else {
        "Package PC Controller exe (PyInstaller) only (Android SDK not found)"
    }
    dependsOn(":pc_controller:assemblePcController")
    if (hasAndroidSdk) {
        dependsOn(":android_sensor_node:app:assembleRelease")
    }
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
