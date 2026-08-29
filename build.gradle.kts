plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("com.gradleup.shadow") version "8.3.6"
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
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")

    // Ktor Server, Client & SSE
    val ktorVersion = "3.1.1"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sse-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
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

val buildExecutable by tasks.registering {
    dependsOn(tasks.named("shadowJar"))
    group = "distribution"
    description = "Builds a standalone self-executable CLI binary into build/bin/aikanban"

    val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val outputFile = layout.buildDirectory.file("bin/aikanban")

    inputs.file(shadowJarTask.flatMap { it.archiveFile })
    outputs.file(outputFile)

    doLast {
        val jarFile = shadowJarTask.get().archiveFile.get().asFile
        val destFile = outputFile.get().asFile
        destFile.parentFile.mkdirs()

        val stub = "#!/bin/sh\nexec java -jar \"$0\" \"$@\"\n".toByteArray(Charsets.UTF_8)
        destFile.outputStream().use { out ->
            out.write(stub)
            jarFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        destFile.setExecutable(true, false)
        println("Generated standalone binary at: ${destFile.absolutePath}")
    }
}

tasks.register("installCli") {
    dependsOn(buildExecutable)
    group = "distribution"
    description = "Installs the standalone CLI executable to ~/.local/bin (or custom directory via -PinstallDir=...)"

    doLast {
        val binDir =
            if (project.hasProperty("installDir")) {
                file(project.property("installDir") as String)
            } else {
                file("${System.getProperty("user.home")}/.local/bin")
            }
        binDir.mkdirs()

        val source = layout.buildDirectory.file("bin/aikanban").get().asFile
        val target = File(binDir, "aikanban")

        source.copyTo(target, overwrite = true)
        target.setExecutable(true, false)

        println("✅ Successfully installed aikanban to: ${target.absolutePath}")
    }
}
