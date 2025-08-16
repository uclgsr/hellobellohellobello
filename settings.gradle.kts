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

include(":android_sensor_node")
project(":android_sensor_node").projectDir = file("android_sensor_node")
include(":android_sensor_node:app")
project(":android_sensor_node:app").projectDir = file("android_sensor_node/app")

include(":pc_controller")
project(":pc_controller").projectDir = file("pc_controller")