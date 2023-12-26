import kotlin.io.path.div
import kotlin.io.path.readLines

version = "0.1.8a"

plugins {
    kotlin("jvm") version "1.8.20"
    kotlin("plugin.serialization") version "1.6.10"
    id("app.cash.sqldelight") version "2.0.0-alpha05"
    application
}

repositories {
    mavenCentral()

    maven {
        name = "Sonatype Snapshots"
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

dependencies {
    implementation("dev.kord", "kord-core", "0.9.0")

    implementation("app.cash.sqldelight", "sqlite-driver", "2.0.0-alpha05")
    implementation("app.cash.sqldelight", "primitive-adapters", "2.0.0-alpha05")
    implementation("org.xerial", "sqlite-jdbc", "3.44.1.0")
    implementation("org.slf4j", "slf4j-simple","2.0.7")
    implementation("com.kotlindiscord.kord.extensions", "kord-extensions", "1.5.7-SNAPSHOT")

    implementation("com.twelvemonkeys.imageio", "imageio-jpeg", "3.9.4")
    implementation("com.twelvemonkeys.imageio", "imageio-webp", "3.9.4")

    val ktorVersion = "2.3.0"

    implementation("io.ktor", "ktor-client-core-jvm", ktorVersion)
    implementation("io.ktor", "ktor-client-cio-jvm", ktorVersion)
    implementation("io.ktor", "ktor-serialization-kotlinx-json-jvm", ktorVersion)
    implementation("io.ktor", "ktor-client-content-negotiation-jvm", ktorVersion)
}

application {
    mainClass.set("ar.pelotude.ohhsugoi.AppKt")

    // this won't actually help that much but imma gonna have
    // this running on an old raspberry pi you know?
    applicationDefaultJvmArgs = listOf("-Xms256m", "-Xmx512m")
}

tasks.run.configure {
    doFirst {
        (project.projectDir.toPath() / ".env")
            .readLines()
            .filter { line ->
                line.matches("^.+=.*$".toRegex())
            }.forEach { envLine ->
                val kv = envLine.split('=', limit=2)
                val (key, value) = kv[0] to (kv.getOrNull(1) ?: "")

                environment[key] = value
            }
    }
}

sqldelight {
    databases {
        create("Database") {
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.0.0-alpha05")
            packageName.set("ar.pelotude.db")
        }
    }
}
