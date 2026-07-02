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

// Single source of truth for the app version: the Android versionName AND the in-app "About" version both
// derive from this (the latter via the generated constant below, so it can never drift — which is how it
// got stuck at 0.1.0). Keep in lockstep with the iOS CFBundleShortVersionString in iosApp/iosApp/Info.plist.
val appVersionName = "1.2.0"

// Emit a commonMain constant from [appVersionName] so the displayed version always matches the build.
val generateAppVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/appversion")
    inputs.property("v", appVersionName)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("dev/ccpocket/app/AppVersion.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package dev.ccpocket.app\n\n/** Generated from build.gradle.kts appVersionName — do not edit. */\ninternal const val APP_VERSION = \"$appVersionName\"\n")
        }
    }
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
            implementation(libs.firebase.messaging) // FCM push (task-complete notifications)
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
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(compose.desktop.currentOs) // skiko runtime for headless ui-test rendering
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(kotlin("test"))
        }
    }
}

// wire the generated version constant into commonMain (drives the in-app About row)
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateAppVersion)

android {
    namespace = "dev.ccpocket.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.panda.ccpocket" // matches the iOS bundle id + the Firebase google-services.json client
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 8
        versionName = appVersionName // single source of truth (see top); lockstep with iOS CFBundleShortVersionString
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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi) // Dmg built on macOS, Msi on Windows (jpackage picks per host)
            packageName = "cc-pocket"
            packageVersion = "1.2.0"
            macOS {
                bundleID = "dev.ccpocket.app"
                // Developer ID signing — pass -PccpocketSignId="Developer ID Application: … (TEAMID)".
                // Off by default so unsigned dev builds still work. Notarization is done after packaging
                // (xcrun notarytool) so the DMG installs with no Gatekeeper warning.
                (findProperty("ccpocketSignId") as String?)?.takeIf { it.isNotBlank() }?.let { id ->
                    signing {
                        sign.set(true)
                        identity.set(id)
                    }
                }
            }
        }
    }
}

// Forward -PccpocketLive=1 to the test JVM. Gates DesktopLiveTest, which connects to a real daemon and so
// must stay opt-in (skipped by default in local + CI runs).
tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    systemProperty("ccpocket.live", providers.gradleProperty("ccpocketLive").getOrElse("0"))
}
