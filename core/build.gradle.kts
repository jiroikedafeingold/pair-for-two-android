// Pure Kotlin/JVM. No Android dependencies — deliberately.
//
// The cribbage rules, the game state machine, and the cross-platform wire protocol all live
// here, so they can be unit-tested on the JVM in milliseconds with no emulator. That matters:
// the scorer is differential-tested against the iOS implementation over thousands of hands
// (see PLAN.md §3), which would be unbearably slow as an instrumented test.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The LAN interop harness: the real LanTransport as a command-line process, so it can be paired
// with the real iOS LANTransport over actual Bonjour. It lives in the test source set because it
// is dev tooling and must not be reachable from production code. Driven by
// tools/run-lan-interop.sh in the iOS repo, or directly:
//
//   ./gradlew :core:interop --args="--role guest --mode drive --peer Swift"
tasks.register<JavaExec>("interop") {
    group = "verification"
    description = "Runs the LAN interop harness against the iOS transport."
    mainClass.set("com.jirofeingold.pairfortwo.core.harness.InteropHarnessKt")
    classpath = sourceSets["test"].runtimeClasspath
}
