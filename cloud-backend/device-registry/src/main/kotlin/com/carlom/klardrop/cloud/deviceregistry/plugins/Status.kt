package com.carlom.klardrop.cloud.deviceregistry.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureLogging() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid request")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to (cause.message ?: "unauthorized")))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "unknown")))
        }
    }
}
