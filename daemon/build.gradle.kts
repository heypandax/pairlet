plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("dev.ccpocket.daemon.MainKt")
    applicationName = "cc-pocket-daemon"
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.cryptography.core)          // E2ECrypto / E2ESession (protocol's e2e API)
    runtimeOnly(libs.cryptography.provider.jdk)      // registers the JDK crypto provider at runtime
    implementation(libs.nayuki.qrcodegen)            // terminal QR for `pair`

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    implementation(libs.clikt)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Let the daemon read from the real terminal stdin when run via Gradle (test-client REPL).
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// M3: self-contained app image with a bundled runtime (runs with no system Java).
tasks.register<Exec>("packageDaemon") {
    group = "distribution"
    description = "jpackage the daemon into a self-contained app image"
    dependsOn("installDist")
    val out = layout.buildDirectory.dir("jpackage")
    doFirst { out.get().asFile.deleteRecursively() }
    // jpackage can't cross-build: it bundles the host JRE, so each OS/arch artifact must be built
    // on a matching runner (see .github/workflows/release.yml). The launcher binary is jpackage.exe
    // on Windows. The release version comes from -PappVersion (falls back for plain local builds).
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val jpackageBin = "${System.getProperty("java.home")}/bin/jpackage" + if (isWindows) ".exe" else ""
    val appVersion = (findProperty("appVersion") as String?) ?: "1.1.5"
    commandLine(
        jpackageBin,
        "--type", "app-image",
        "--name", "cc-pocket-daemon",
        "--app-version", appVersion,
        "--input", layout.buildDirectory.dir("install/cc-pocket-daemon/lib").get().asFile.absolutePath,
        "--main-jar", "daemon-${project.version}.jar",
        "--main-class", "dev.ccpocket.daemon.MainKt",
        "--dest", out.get().asFile.absolutePath,
    )
}
