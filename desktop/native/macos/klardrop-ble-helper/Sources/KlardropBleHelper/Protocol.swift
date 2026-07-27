import Foundation

// Constants kept in sync with common/src/commonMain/kotlin/com/carlom/klardrop/common/ble/BleConstants.kt.
//
// This is the only Klardrop protocol knowledge the helper holds. What to advertise and
// how to read a peer's identity back out of an advertisement lives in commonMain
// (klardropAdvertisePayload / BleAdvertisementCodec) so the macOS, iOS and Android
// radios can't drift apart; the helper just makes the CoreBluetooth calls and forwards
// raw advertisement fields for the Kotlin side to decode.
enum BleConstants {
  static let serviceUUID = "a5b7c3e1-7f5a-4b62-9a3c-1d8e2f4b6c8a"
  static let txCharacteristicUUID = "a5b7c3e2-7f5a-4b62-9a3c-1d8e2f4b6c8a"
  static let rxCharacteristicUUID = "a5b7c3e3-7f5a-4b62-9a3c-1d8e2f4b6c8a"
}

// Newline-delimited JSON. Commands carry an `id` for response correlation. Events
// have no `id` and are identified by the `event` field.
struct InboundCommand {
  let id: String
  let cmd: String
  let raw: [String: Any]

  static func parse(_ line: String) -> InboundCommand? {
    guard let data = line.data(using: .utf8) else { return nil }
    guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
    guard let id = obj["id"] as? String, let cmd = obj["cmd"] as? String else { return nil }
    return InboundCommand(id: id, cmd: cmd, raw: obj)
  }
}

enum OutboundEnvelope {
  case ok(id: String, extra: [String: Any] = [:])
  case error(id: String?, code: String, message: String)
  case event(name: String, fields: [String: Any])

  func encode() -> String {
    var dict: [String: Any]
    switch self {
    case .ok(let id, let extra):
      dict = ["id": id, "ok": true]
      for (k, v) in extra { dict[k] = v }
    case .error(let id, let code, let message):
      dict = ["ok": false, "error": code, "message": message]
      if let id = id { dict["id"] = id }
    case .event(let name, let fields):
      dict = ["event": name]
      for (k, v) in fields { dict[k] = v }
    }
    let data = (try? JSONSerialization.data(withJSONObject: dict, options: [])) ?? Data()
    return String(data: data, encoding: .utf8) ?? "{}"
  }
}

// Thread-safe writer that prints one JSON object per line to stdout. CoreBluetooth
// callbacks fire on background queues so we serialize writes through a single queue.
final class StdoutWriter {
  private let queue = DispatchQueue(label: "klardrop.ble.helper.stdout")

  init() {
    setbuf(stdout, nil)
  }

  func send(_ envelope: OutboundEnvelope) {
    let line = envelope.encode()
    queue.async {
      print(line)
    }
  }

  func sendOk(id: String, extra: [String: Any] = [:]) { send(.ok(id: id, extra: extra)) }
  func sendError(id: String?, code: String, message: String) { send(.error(id: id, code: code, message: message)) }
  func sendEvent(_ name: String, _ fields: [String: Any] = [:]) { send(.event(name: name, fields: fields)) }

  func sendLog(_ level: String, _ message: String) {
    sendEvent("log", ["level": level, "message": message])
  }
}
