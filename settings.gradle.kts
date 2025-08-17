pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "asd"

// Detect Android SDK presence to conditionally include Android modules
val localPropsFile = file("local.properties")
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

if (hasAndroidSdk) {
    include(":android_sensor_node")
    project(":android_sensor_node").projectDir = file("android_sensor_node")
    include(":android_sensor_node:app")
    project(":android_sensor_node:app").projectDir = file("android_sensor_node/app")
} else {
    println("[settings] Android SDK not found; skipping inclusion of :android_sensor_node modules")
}

include(":pc_controller")
project(":pc_controller").projectDir = file("pc_controller")
