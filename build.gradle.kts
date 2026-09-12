plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

allprojects {
    group = "dev.ccpocket"
    version = "0.0.1-SNAPSHOT"
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        // Synthetic repository failures must not become production Analytics/Sentry traffic.
        systemProperty("ccpocket.test", "true")
    }
}
