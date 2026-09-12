plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
}

// Reuse the shared app version consumed by the in-app About screen and release scripts.
evaluationDependsOn(":mobile:composeApp")
val appVersionName = project(":mobile:composeApp").extra["appVersionName"] as String

android {
    namespace = "dev.ccpocket.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.panda.ccpocket" // matches the iOS bundle id + the Firebase google-services.json client
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 31
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

// The Android entry activity and platform implementations live in the shared KMP library.
dependencies {
    implementation(project(":mobile:composeApp"))
}
