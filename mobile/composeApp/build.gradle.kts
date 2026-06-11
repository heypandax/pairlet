import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
}

kotlin {
    jvmToolchain(17)
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") } // SecureStore is an expect object (Beta API, stable enough)
    androidTarget()
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources) // localized strings (en default, values-zh)
            implementation(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.qrkit) // in-app camera QR scanner (ios/android/desktop)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.cryptography.provider.jdk) // E2E crypto provider (registers on this target)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.crashlytics)
            implementation(libs.peekaboo) // image picker + resize (android variant)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.cryptography.provider.jdk)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.cryptography.provider.openssl3.prebuilt) // Apple provider lacks ECDH at 0.4.0
            implementation(libs.peekaboo) // image picker + resize (ios native variant)
        }
    }
}

android {
    namespace = "dev.ccpocket.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.panda.ccpocket" // matches the iOS bundle id + the Firebase google-services.json client
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0" // keep in lockstep with the iOS CFBundleShortVersionString
    }
    // release signing comes from ~/.gradle/gradle.properties (CCPOCKET_KEYSTORE*) — keys never
    // live in the repo; on machines without them the release build falls back to unsigned
    val releaseKeystore = providers.gradleProperty("CCPOCKET_KEYSTORE").orNull?.let(::File)
    if (releaseKeystore?.exists() == true) {
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storePassword = providers.gradleProperty("CCPOCKET_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("CCPOCKET_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("CCPOCKET_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    packageOfResClass = "dev.ccpocket.app.resources"
}

compose.desktop {
    application {
        mainClass = "dev.ccpocket.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "cc-pocket"
            packageVersion = "1.0.0"
        }
    }
}
