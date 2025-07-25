plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.carlom.klardrop.cloud"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:3.2.1")
    implementation("io.ktor:ktor-server-netty:3.2.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.1")
    implementation("io.ktor:ktor-server-auth:3.2.1")
    implementation("io.ktor:ktor-server-auth-jwt:3.2.1")
    implementation("io.ktor:ktor-server-call-logging:3.2.1")
    implementation("io.ktor:ktor-server-metrics-micrometer:3.2.1")
    implementation("io.ktor:ktor-server-status-pages:3.2.1")
    implementation("io.ktor:ktor-server-cors:3.2.1")
    implementation("io.ktor:ktor-server-swagger:3.2.1")
    
    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.60.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.60.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.60.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.60.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.8.0")
    
    // Redis
    implementation("io.lettuce:lettuce-core:6.5.1.RELEASE")
    
    // Kafka
    implementation("org.apache.kafka:kafka-clients:3.10.0")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.9.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    
    // Monitoring
    implementation("io.micrometer:micrometer-registry-prometheus:1.15.2")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.6.2")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    
    // Configuration
    implementation("io.github.config4k:config4k:0.9.0")
    
    // JWT
    implementation("com.auth0:java-jwt:4.4.0")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-tests:3.2.1")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
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