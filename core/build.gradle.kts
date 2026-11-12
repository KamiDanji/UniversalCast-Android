import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // WebSocket transport (server for the receiver, client for the sender SDK / tests).
    api("org.java-websocket:Java-WebSocket:1.6.0")
    api("org.slf4j:slf4j-api:2.0.13")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    // Test-only: generates the self-signed receiver certificate used by JVM integration tests.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

tasks.test {
    useJUnit()
    // The JDK's HttpURLConnection silently drops "restricted" headers such as Origin; Android's does not. StreamProbe
    // runs on Android, so let the JVM tests see the same behaviour.
    systemProperty("sun.net.http.allowRestrictedHeaders", "true")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
