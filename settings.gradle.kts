@file:Suppress("UnstableApiUsage")

rootProject.name = "cc-pocket"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":protocol")
include(":daemon")
include(":relay")
include(":mobile:composeApp")
