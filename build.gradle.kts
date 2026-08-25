plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    application
}

group = "aikanban"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // SQLite JDBC
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // Kotlinx Serialization & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // CLI & Terminal UI
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")

    // Ktor Server & SSE
    val ktorVersion = "3.1.1"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sse-jvm:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("aikanban.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
