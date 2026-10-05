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
val appVersion = (findProperty("appVersion") as String?) ?: "2.4.0"

tasks.processResources {
    inputs.property("appVersion", appVersion)
    filesMatching("cc-pocket-version.properties") { expand("appVersion" to appVersion) }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":observability"))
    implementation(project(":observability-sentry"))

    // Windows-only external-process cwd read (issue #302). Already on the runtime classpath transitively
    // via mordant; declared explicitly so it's visible at compile time. Pinned to the transitive version
    // to avoid a second JNA on the classpath.
    implementation("net.java.dev.jna:jna:5.14.0")

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.cryptography.core)          // E2ECrypto / E2ESession (protocol's e2e API)
    runtimeOnly(libs.cryptography.provider.jdk)      // registers the JDK crypto provider at runtime
    implementation(libs.nayuki.qrcodegen)            // terminal QR for `pair`
    implementation(libs.lark.oapi.sdk)               // built-in Feishu bridge: event long-connection + reply API

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    implementation(libs.clikt)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    implementation(libs.sqlite.jdbc)             // OpenCodeTranscriptScanner/Replay read opencode.db
    implementation(libs.zstd.jni)                // DshTranscript decodes ~/.dsh multi-frame session.jsonl.zstd

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.network.tls.certificates) // builds test X.509 chains for RelayTrust
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// A throwaway home for the test JVM. Every daemon store resolves its default path from `user.home`
// (`~/.cc-pocket/…`, directly or through Identity.defaultPath()), so a test that leans on a default —
// `DaemonCore(emptyMap())` alone loads prefs/presets/schedules/approval history and sweeps the spawned-
// session journal — would otherwise read and rewrite the developer's REAL daemon state. Pointing
// `user.home` here redirects all of them at once; RealHomeGuard (src/test) fails any test JVM that
// still resolves into the real home. Wiped before each run so no state leaks between runs.
val testHome = layout.buildDirectory.dir("test-home")

tasks.test {
    useJUnitPlatform()
    val home = testHome.get().asFile
    systemProperty("user.home", home.absolutePath)
    // CC_POCKET_IDENTITY repoints Identity.defaultPath() — and with it every store beside identity.json —
    // past user.home; a developer shell that exports it must not drag the tests back to the real files.
    environment.remove("CC_POCKET_IDENTITY")
    doFirst {
        home.deleteRecursively()
        home.mkdirs()
    }
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
