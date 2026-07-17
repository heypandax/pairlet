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
        // JediTerm (the desktop app's embedded terminal engine, issue #153) is published to JetBrains'
        // intellij-dependencies repo, not Maven Central. Content-filtered so ONLY that group ever
        // resolves from here — everything else keeps coming from the two repos above.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

include(":protocol")
include(":daemon")
include(":relay")
include(":mobile:composeApp")
