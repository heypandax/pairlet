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

// The release version: -PappVersion in CI, fallback for local builds. Baked into the
// cc-pocket-version.properties resource so the daemon knows its own version at runtime —
// the self-update check compares it against GitHub releases/latest.
val appVersion = (findProperty("appVersion") as String?) ?: "1.3.5"

tasks.processResources {
    inputs.property("appVersion", appVersion)
    filesMatching("cc-pocket-version.properties") { expand("appVersion" to appVersion) }
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
    testImplementation(libs.ktor.network.tls.certificates) // builds test X.509 chains for RelayTrust
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        // CI has no test-report artifact — the console line is all we get on a failure, so it must
        // carry the assertion message + stack, not just "AssertionFailedError at Foo.kt:85"
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
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
    val jpackageArgs = buildList {
        add(jpackageBin)
        add("--type"); add("app-image")
        add("--name"); add("cc-pocket-daemon")
        add("--app-version"); add(appVersion)
        add("--input"); add(layout.buildDirectory.dir("install/cc-pocket-daemon/lib").get().asFile.absolutePath)
        add("--main-jar"); add("daemon-${project.version}.jar")
        add("--main-class"); add("dev.ccpocket.daemon.MainKt")
        add("--dest"); add(out.get().asFile.absolutePath)
        // Windows: jpackage's app-image launcher defaults to the GUI subsystem, so stdout/stderr are
        // not attached to the console — `pair`/`run` print nothing when launched from a terminal.
        // Force a console launcher. --win-console is Windows-only; it errors on macOS/Linux jpackage.
        if (isWindows) add("--win-console")
    }
    commandLine(jpackageArgs)
}
