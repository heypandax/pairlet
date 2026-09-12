plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}
kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "dev.ccpocket.observability.sentry"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies { api(project(":observability")) }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.sentry.java)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmTest.dependencies { implementation(kotlin("test")); implementation(libs.sentry.java) }
    }
}

tasks.withType<Test>().configureEach {
    // The process-boundary fixture must use the actual tested SDK/runtime, not Gradle's worker jar.
    doFirst { systemProperty("ccpocket.sentry.fixtureClasspath", classpath.asPath) }
}

// Explicit one-shot staging probe; does not launch a daemon or enable persistent telemetry.
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Send one synthetic error and log to the configured staging Sentry project"
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("dev.ccpocket.observability.sentry.SentrySmokeKt")
    args(providers.gradleProperty("diagnosticComponent").getOrElse("desktop"))
}
