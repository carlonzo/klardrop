package com.carlom.klardrop.cloud.deviceregistry

import com.carlom.klardrop.cloud.deviceregistry.config.AppConfig
import com.carlom.klardrop.cloud.deviceregistry.database.DatabaseFactory
import com.carlom.klardrop.cloud.deviceregistry.plugins.*
import com.carlom.klardrop.cloud.deviceregistry.routes.deviceRoutes
import com.carlom.klardrop.cloud.deviceregistry.services.DeviceService
import com.carlom.klardrop.cloud.deviceregistry.services.RedisService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val config = AppConfig.load()
    
    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = { module(config) }
    ).start(wait = true)
}

fun Application.module(config: AppConfig) {
    // Initialize database
    DatabaseFactory.init(config.database)
    
    // Initialize services
    val redisService = RedisService(config.redis)
    val deviceService = DeviceService(redisService)
    
    // Configure plugins
    configureSerialization()
    configureMonitoring()
    configureSecurity(config.jwt)
    configureHTTP()
    configureLogging()
    
    // Configure routes
    routing {
        route("/api/v1") {
            deviceRoutes(deviceService)
        }
    }
    
    logger.info { "Device Registry Service started on ${config.server.host}:${config.server.port}" }
}