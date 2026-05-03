package com.carlom.klardrop.common.ble.mac

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MacBleHelperProtocolTest {

  @Test
  fun encodesRequestWithIdAndCmd() {
    val line = HelperProtocol.encodeRequest(id = "abc", cmd = HelperCommands.INIT)
    val parsed = HelperProtocol.parseLine(line)
    // Encoded shape isn't a response, but it should parse-back as a JSON object
    // — null because encodeRequest produces a command, not a response/event.
    // Validate the wire bytes directly instead.
    assertEquals("""{"id":"abc","cmd":"init"}""", line)
    assertNull(parsed) // commands are not in the parse target space (those are server→client)
  }

  @Test
  fun encodesRequestWithExtraFields() {
    val line = HelperProtocol.encodeRequest(
      id = "id-1",
      cmd = HelperCommands.SEND_CHUNK,
      fields = mapOf(
        "sessionId" to JsonPrimitive("sess-1"),
        "data" to JsonPrimitive("AAEC"),
      ),
    )
    assertEquals(
      """{"id":"id-1","cmd":"send_chunk","sessionId":"sess-1","data":"AAEC"}""",
      line,
    )
  }

  @Test
  fun parsesOkResponse() {
    val parsed = HelperProtocol.parseLine("""{"id":"r-1","ok":true,"sessionId":"sess","mtu":244}""")
    assertNotNull(parsed)
    val ok = assertIs<HelperLine.Ok>(parsed)
    assertEquals("r-1", ok.id)
    assertEquals("sess", ok.obj.string("sessionId"))
    assertEquals(244, ok.obj.int("mtu"))
  }

  @Test
  fun parsesErrorResponse() {
    val parsed = HelperProtocol.parseLine(
      """{"id":"r-2","ok":false,"error":"connect_failed","message":"no device"}"""
    )
    val err = assertIs<HelperLine.Error>(parsed)
    assertEquals("r-2", err.id)
    assertEquals("connect_failed", err.code)
    assertEquals("no device", err.message)
  }

  @Test
  fun parsesStateEvent() {
    val parsed = HelperProtocol.parseLine("""{"event":"state","state":"poweredOn"}""")
    val ev = assertIs<HelperLine.Event>(parsed)
    assertEquals(HelperEvents.STATE, ev.name)
    assertEquals("poweredOn", ev.obj.string("state"))
  }

  @Test
  fun parsesPeerFoundEvent() {
    val parsed = HelperProtocol.parseLine(
      """{"event":"peer_found","peerId":"abc","shortDeviceId":"xyz12345","localName":"Phone","rssi":-60}"""
    )
    val ev = assertIs<HelperLine.Event>(parsed)
    assertEquals(HelperEvents.PEER_FOUND, ev.name)
    assertEquals("abc", ev.obj.string("peerId"))
    assertEquals("xyz12345", ev.obj.string("shortDeviceId"))
    assertEquals("Phone", ev.obj.string("localName"))
    assertEquals(-60, ev.obj.int("rssi"))
  }

  @Test
  fun parsesChunkEvent() {
    val parsed = HelperProtocol.parseLine(
      """{"event":"chunk","sessionId":"s","data":"SGVsbG8="}"""
    )
    val ev = assertIs<HelperLine.Event>(parsed)
    assertEquals(HelperEvents.CHUNK, ev.name)
    assertEquals("s", ev.obj.string("sessionId"))
    assertEquals("SGVsbG8=", ev.obj.string("data"))
  }

  @Test
  fun returnsNullOnMalformedJson() {
    assertNull(HelperProtocol.parseLine("not json at all"))
    assertNull(HelperProtocol.parseLine(""))
    assertNull(HelperProtocol.parseLine("""[1,2,3]"""))
  }

  @Test
  fun returnsNullWhenNeitherEventNorIdPresent() {
    assertNull(HelperProtocol.parseLine("""{"foo":"bar"}"""))
  }

  @Test
  fun ignoresUnknownFieldsGracefully() {
    val parsed = HelperProtocol.parseLine("""{"id":"x","ok":true,"unknown":"value"}""")
    val ok = assertIs<HelperLine.Ok>(parsed)
    assertEquals("x", ok.id)
    assertEquals("value", ok.obj.string("unknown"))
  }
}
