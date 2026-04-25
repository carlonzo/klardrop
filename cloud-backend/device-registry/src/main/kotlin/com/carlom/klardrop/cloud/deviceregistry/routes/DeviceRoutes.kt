package com.carlom.klardrop.cloud.deviceregistry.routes

import com.carlom.klardrop.cloud.deviceregistry.models.*
import com.carlom.klardrop.cloud.deviceregistry.services.DeviceService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.deviceRoutes(deviceService: DeviceService) {
    post("/auth/session/exchange") {
        val request = call.receive<SessionExchangeRequest>()
        val response = try {
            deviceService.exchangeSession(request)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
            return@post
        }
        call.respond(HttpStatusCode.OK, response)
    }

    authenticate("auth-jwt") {
        post("/users/bootstrap") {
            call.respond(HttpStatusCode.OK, deviceService.bootstrapUser(call.userId()))
        }

        post("/devices/pairing-codes") {
            val request = call.receive<PairingCodeRequest>()
            val response = try {
                deviceService.issuePairingCode(call.userId(), request)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                return@post
            }
            call.respond(HttpStatusCode.Created, response)
        }

        post("/devices/enroll") {
            val request = call.receive<EnrollDeviceRequest>()
            val response = try {
                deviceService.enrollDevice(call.userId(), request)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                return@post
            }
            call.respond(HttpStatusCode.Created, response)
        }

        post("/devices/{deviceId}/revoke") {
            val deviceId = call.parameters["deviceId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("deviceId is required"))
            deviceService.revokeDevice(call.userId(), deviceId)
            call.respond(HttpStatusCode.Accepted)
        }

        post("/devices/{deviceId}/rotate") {
            val deviceId = call.parameters["deviceId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("deviceId is required"))
            val response = try {
                deviceService.rotateDeviceCredential(call.userId(), deviceId)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                return@post
            }
            call.respond(HttpStatusCode.OK, response)
        }

        get("/users/{userId}/devices") {
            val principalUser = call.userId()
            val requestedUser = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("userId is required"))
            if (principalUser != requestedUser) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("cross-user access denied"))
            }
            call.respond(HttpStatusCode.OK, deviceService.listDevices(principalUser))
        }

        post("/devices/approval-challenges") {
            val request = call.receive<CreateApprovalChallengeRequest>()
            val response = try {
                deviceService.createApprovalChallenge(call.userId(), request)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                return@post
            }
            call.respond(HttpStatusCode.Created, response)
        }

        post("/devices/approval-challenges/{challengeId}/approve") {
            val challengeId = call.parameters["challengeId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("challengeId is required"))
            call.respond(HttpStatusCode.OK, deviceService.approveChallenge(call.userId(), challengeId))
        }

        post("/transfers/route-decision") {
            val request = call.receive<RouteDecisionRequest>()
            call.respond(HttpStatusCode.OK, deviceService.decideTransferRoute(request))
        }
    }

    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}

private fun io.ktor.server.application.ApplicationCall.userId(): String {
    val principal = principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("user_id")?.asString()
    if (userId.isNullOrBlank()) {
        throw IllegalStateException("Missing user_id claim")
    }
    return userId
}
