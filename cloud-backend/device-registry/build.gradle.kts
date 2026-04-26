plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.carlom.klardrop.cloud"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(backendLibs.ktor.server.core)
    implementation(backendLibs.ktor.server.netty)
    implementation(backendLibs.ktor.server.content.negotiation)
    implementation(backendLibs.ktor.serialization.kotlinx.json)
    implementation(backendLibs.ktor.server.auth)
    implementation(backendLibs.ktor.server.auth.jwt)
    implementation(backendLibs.ktor.server.call.logging)
    implementation(backendLibs.ktor.server.metrics.micrometer)
    implementation(backendLibs.ktor.server.status.pages)
    implementation(backendLibs.ktor.server.cors)
    implementation(backendLibs.ktor.server.swagger)

    implementation(backendLibs.exposed.core)
    implementation(backendLibs.exposed.dao)
    implementation(backendLibs.exposed.jdbc)
    implementation(backendLibs.exposed.java.time)
    implementation(backendLibs.hikaricp)
    implementation(backendLibs.postgresql)

    implementation(backendLibs.lettuce.core)
    implementation(backendLibs.kafka.clients)
    implementation(backendLibs.paho.mqtt.client)

    implementation(backendLibs.kotlinx.serialization.json)
    implementation(backendLibs.kotlinx.serialization.protobuf)
    implementation(backendLibs.kotlinx.coroutines.core)

    implementation(backendLibs.micrometer.registry.prometheus)
    implementation(backendLibs.logback.classic)
    implementation(backendLibs.kotlin.logging.jvm)
    implementation(backendLibs.config4k)
    implementation(backendLibs.java.jwt)
    implementation(backendLibs.jwks.rsa)

    testImplementation(kotlin("test"))
    testImplementation(backendLibs.ktor.server.test.host)
    testImplementation(backendLibs.mockk)
    testImplementation(backendLibs.testcontainers)
    testImplementation(backendLibs.testcontainers.postgresql)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.carlom.klardrop.cloud.deviceregistry.ApplicationKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("device-registry")
        archiveClassifier.set("all")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = application.mainClass.get()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
