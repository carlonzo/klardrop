package com.carlom.klardrop.cloud.deviceregistry.routes

import com.carlom.klardrop.cloud.deviceregistry.config.InternalAuthConfig
import com.carlom.klardrop.cloud.deviceregistry.models.*
import com.carlom.klardrop.cloud.deviceregistry.services.BrokerAclDecision
import com.carlom.klardrop.cloud.deviceregistry.services.BrokerAuthDecision
import com.carlom.klardrop.cloud.deviceregistry.services.BrokerAuthService
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

        post("/devices/me/broker-token") {
            val request = call.receive<BrokerTokenRefreshRequest>()
            val response = try {
                val rotated = deviceService.rotateDeviceCredential(call.userId(), request.deviceId)
                BrokerTokenRefreshResponse(
                    deviceId = rotated.deviceId,
                    brokerToken = rotated.brokerToken,
                    brokerTokenExpiresAt = rotated.brokerTokenExpiresAt,
                    brokerTokenTtlSeconds = rotated.brokerTokenTtlSeconds
                )
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

/**
 * Endpoints called by Mosquitto (via mosquitto-go-auth) on the internal
 * listener. **Never expose to the public internet.**
 *
 * mosquitto-go-auth's HTTP backend uses three endpoints by default:
 *   - getuser    -> validate username/password (here: clientId/JWT)
 *   - aclcheck   -> validate (clientId, topic, access)
 *   - superuser  -> we always say no
 *
 * Bodies are `application/x-www-form-urlencoded`. With
 * `auth_opt_http_response_mode = status`, mosquitto-go-auth treats:
 *   - HTTP 200 → allow
 *   - any other status → deny
 *
 * The shared-secret header (`Authorization: Bearer …`) gates all three.
 */
fun Route.internalRoutes(
    brokerAuthService: BrokerAuthService,
    internalAuthConfig: InternalAuthConfig
) {
    post("/internal/broker/auth/user") {
        if (!authorizeInternal(call, internalAuthConfig)) return@post
        val params = call.receiveParameters()
        val password = params["password"].orEmpty()
        val clientId = params["clientid"]
        when (val decision = brokerAuthService.authenticateUser(password, clientId)) {
            is BrokerAuthDecision.Allow -> call.respond(HttpStatusCode.OK)
            is BrokerAuthDecision.Deny -> call.respond(HttpStatusCode.Forbidden, decision.reason)
        }
    }

    post("/internal/broker/auth/acl") {
        if (!authorizeInternal(call, internalAuthConfig)) return@post
        val params = call.receiveParameters()
        val clientId = params["clientid"].orEmpty()
        val topic = params["topic"].orEmpty()
        val accCode = params["acc"]?.toIntOrNull()
        val access = accCode?.let { BrokerAclAccess.fromMosquittoCode(it) }
        if (clientId.isBlank() || topic.isBlank() || access == null) {
            call.respond(HttpStatusCode.BadRequest, "missing clientid/topic/acc")
            return@post
        }
        when (val decision = brokerAuthService.checkAcl(clientId, topic, access)) {
            is BrokerAclDecision.Allow -> call.respond(HttpStatusCode.OK)
            is BrokerAclDecision.Deny -> call.respond(HttpStatusCode.Forbidden, decision.reason)
        }
    }

    post("/internal/broker/auth/superuser") {
        if (!authorizeInternal(call, internalAuthConfig)) return@post
        // No superusers in the Klardrop model.
        call.respond(HttpStatusCode.Forbidden, "no superusers")
    }
}

/** Returns true if the call carries the expected internal shared-secret. */
private suspend fun authorizeInternal(
    call: io.ktor.server.application.ApplicationCall,
    cfg: InternalAuthConfig
): Boolean {
    if (!cfg.isConfigured) {
        call.respond(HttpStatusCode.ServiceUnavailable, "internal auth not configured")
        return false
    }
    val expected = "Bearer ${cfg.sharedSecret}"
    val provided = call.request.headers[HttpHeaders.Authorization].orEmpty()
    if (!constantTimeEquals(provided, expected)) {
        call.respond(HttpStatusCode.Unauthorized, "unauthorized")
        return false
    }
    return true
}

@kotlinx.serialization.Serializable
data class BrokerTokenRefreshRequest(val deviceId: String)

private fun io.ktor.server.application.ApplicationCall.userId(): String {
    val principal = principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("user_id")?.asString()
    if (userId.isNullOrBlank()) {
        throw IllegalStateException("Missing user_id claim")
    }
    return userId
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].code xor b[i].code)
    return result == 0
}
