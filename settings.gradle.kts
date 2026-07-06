@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Fresh clones rarely have the exact JDK: let Gradle auto-download the jvmToolchain(17)
    // the modules declare instead of failing on a machine-specific JAVA_HOME pin.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "cc-pocket"

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
