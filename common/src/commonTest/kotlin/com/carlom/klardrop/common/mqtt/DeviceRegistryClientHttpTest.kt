package com.carlom.klardrop.common.mqtt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DeviceRegistryClientHttpTest {

    @Test
    fun exchange_session_posts_id_token_and_parses_response() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/auth/session/exchange", request.url.encodedPath)
            assertTrue("the-id-token" in request.bodyText())
            jsonOk(
                """{"userId":"usr_abc","accessToken":"sess_token","authProvider":"keycloak"}"""
            )
        }
        val client = clientWith(engine)

        val res = client.exchangeSession("the-id-token")

        assertEquals("usr_abc", res.userId)
        assertEquals("sess_token", res.sessionToken)
    }

    @Test
    fun list_devices_includes_bearer_auth_and_decodes_devices() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/users/usr_abc/devices", request.url.encodedPath)
            assertEquals("Bearer sess_token", request.headers[HttpHeaders.Authorization])
            jsonOk(LIST_DEVICES_BODY)
        }
        val client = clientWith(engine, sessionToken = "sess_token")

        val devices = client.listDevices("usr_abc")

        assertEquals(1, devices.size)
        assertEquals("dev-uuid-1", devices[0].deviceId)
        assertEquals("Alice's iPhone", devices[0].deviceName)
        assertTrue(devices[0].publicKey.contentEquals(byteArrayOf(1, 2, 3))) // base64 "AQID"
    }

    @Test
    fun refresh_credentials_assembles_full_mqtt_credentials() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/devices/me/broker-token", request.url.encodedPath)
            assertEquals("Bearer sess_token", request.headers[HttpHeaders.Authorization])
            jsonOk(
                """{"deviceId":"dev-uuid-1","brokerToken":"broker-jwt","brokerTokenExpiresAt":1735000000000,"brokerTokenTtlSeconds":900}"""
            )
        }
        val client = clientWith(
            engine,
            sessionToken = "sess_token",
            brokerUrl = "ssl://broker.example.com:8883",
            topicRoot = "klardrop/v1"
        )

        val creds = client.refreshCredentials(userId = "usr_abc", deviceId = "dev-uuid-1")

        assertEquals("ssl://broker.example.com:8883", creds.brokerUrl)
        assertEquals("broker-jwt", creds.brokerToken)
        assertEquals("usr_abc", creds.userId)
        assertEquals("dev-uuid-1", creds.deviceId)
        assertEquals("klardrop/v1/users/usr_abc", creds.topicScope)
        assertEquals("klardrop_usr_abc_dev-uuid-1", creds.mqttClientId)
        assertEquals(1735000000000L, creds.expiresAtEpochMs)
        assertEquals(900L, creds.ttlSeconds)
    }

    @Test
    fun non_2xx_response_raises_DeviceRegistryException() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":"invalid request"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = clientWith(engine, sessionToken = "x")

        val ex = assertFailsWith<DeviceRegistryException> {
            client.refreshCredentials("usr_abc", "dev-x")
        }
        assertEquals(HttpStatusCode.BadRequest, ex.status)
    }

    @Test
    fun missing_session_token_throws_for_authed_calls() = runTest {
        val engine = MockEngine { jsonOk("[]") }
        val client = clientWith(engine, sessionToken = null)

        assertFailsWith<IllegalStateException> {
            client.listDevices("usr_abc")
        }
    }

    @Test
    fun base64_decode_handles_padding_and_non_padded() {
        assertTrue(decodeBase64("").isEmpty())
        assertTrue(decodeBase64("AQID").contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(decodeBase64("AQID==").contentEquals(byteArrayOf(1, 2, 3)))
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun clientWith(
        engine: MockEngine,
        sessionToken: String? = "sess_token",
        brokerUrl: String = "ssl://broker.example.com:8883",
        topicRoot: String = "klardrop/v1"
    ): DeviceRegistryClientHttp = DeviceRegistryClientHttp(
        baseUrl = "https://api.test",
        brokerUrl = brokerUrl,
        topicRoot = topicRoot,
        sessionToken = { sessionToken },
        httpClient = createHttpClient(engine = engine)
    )

    private fun MockRequestHandleScope.jsonOk(body: String): HttpResponseData =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

    /** Best-effort body inspection — we only set TextContent in this test. */
    private fun HttpRequestData.bodyText(): String =
        (body as? TextContent)?.text.orEmpty()

    companion object {
        private val LIST_DEVICES_BODY = """
            [
              {
                "id":"row-1",
                "deviceId":"dev-uuid-1",
                "deviceName":"Alice's iPhone",
                "platform":"IOS",
                "publicKey":"AQID",
                "mqttClientId":"klardrop_usr_abc_dev-uuid-1",
                "createdAt":"2026-04-26T12:00:00Z",
                "updatedAt":"2026-04-26T12:00:00Z",
                "lastSeen":"2026-04-26T12:00:00Z"
              }
            ]
        """.trimIndent()
    }
}
