import Foundation

// Klardrop BLE helper — reads newline-delimited JSON commands on stdin, drives
// CoreBluetooth, and emits responses + events as newline-delimited JSON on stdout.
// Owned by a single JVM process (one helper per BleTransport instance).

// Sessions live across both controllers; peripheral-role sessions are created when
// a remote central subscribes, central-role sessions are created when we connect.
// SessionRegistry is the routing table for send_chunk/close_session.
enum SessionEntry {
  case central(CentralSession)
  case peripheral(PeripheralSession)

  func asCentral() -> CentralSession? { if case .central(let s) = self { return s } else { return nil } }
  func asPeripheral() -> PeripheralSession? { if case .peripheral(let s) = self { return s } else { return nil } }
}

final class SessionRegistry {
  private var byId: [String: SessionEntry] = [:]
  private let queue: DispatchQueue

  init(queue: DispatchQueue) { self.queue = queue }

  func add(sessionId: String, session: SessionEntry) {
    byId[sessionId] = session
  }

  func session(for id: String) -> SessionEntry? {
    return byId[id]
  }

  func remove(sessionId: String) {
    byId.removeValue(forKey: sessionId)
  }
}

let helperQueue = DispatchQueue(label: "klardrop.ble.helper.cb", qos: .userInitiated)
let writer = StdoutWriter()
let registry = SessionRegistry(queue: helperQueue)
var central: CentralController!
var peripheral: PeripheralController!

helperQueue.sync {
  central = CentralController(writer: writer, queue: helperQueue, registry: registry)
  peripheral = PeripheralController(writer: writer, queue: helperQueue, registry: registry)
}

// stdin reader: blocks on FileHandle.standardInput. We split on newlines and dispatch
// each line onto the helperQueue. SIGPIPE on broken stdout (parent JVM died) exits
// the helper cleanly.
signal(SIGPIPE, SIG_IGN)

let stdin = FileHandle.standardInput
var buffer = Data()

func dispatch(line: String) {
  guard let cmd = InboundCommand.parse(line) else {
    writer.sendError(id: nil, code: "bad_request", message: "Could not parse line as JSON command")
    return
  }
  helperQueue.async {
    handle(cmd)
  }
}

func handle(_ cmd: InboundCommand) {
  switch cmd.cmd {
  case "init":
    // Reply ok; the actual state event already flows from CB delegate.
    writer.sendOk(id: cmd.id)
  case "scan_start":
    central.startScan(requestId: cmd.id)
  case "scan_stop":
    central.stopScan(requestId: cmd.id)
  case "advertise_start":
    let shortId = (cmd.raw["shortDeviceId"] as? String) ?? ""
    let localName = cmd.raw["localName"] as? String
    if shortId.isEmpty {
      writer.sendError(id: cmd.id, code: "bad_request", message: "shortDeviceId required")
    } else {
      peripheral.startAdvertising(requestId: cmd.id, shortDeviceId: shortId, localName: localName)
    }
  case "advertise_stop":
    peripheral.stopAdvertising(requestId: cmd.id)
  case "connect":
    guard let peerId = cmd.raw["peerId"] as? String else {
      writer.sendError(id: cmd.id, code: "bad_request", message: "peerId required")
      return
    }
    central.connect(requestId: cmd.id, peerId: peerId)
  case "send_chunk":
    guard let sessionId = cmd.raw["sessionId"] as? String,
          let dataB64 = cmd.raw["data"] as? String,
          let data = Data(base64Encoded: dataB64) else {
      writer.sendError(id: cmd.id, code: "bad_request", message: "sessionId+data required")
      return
    }
    if central.sessions(contains: sessionId) {
      central.sendChunk(requestId: cmd.id, sessionId: sessionId, data: data)
    } else {
      peripheral.sendChunk(requestId: cmd.id, sessionId: sessionId, data: data)
    }
  case "close_session":
    guard let sessionId = cmd.raw["sessionId"] as? String else {
      writer.sendError(id: cmd.id, code: "bad_request", message: "sessionId required")
      return
    }
    if central.sessions(contains: sessionId) {
      central.closeSession(requestId: cmd.id, sessionId: sessionId)
    } else {
      peripheral.closeSession(requestId: cmd.id, sessionId: sessionId)
    }
  case "shutdown":
    central.shutdown()
    peripheral.shutdown()
    writer.sendOk(id: cmd.id)
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.1) { exit(0) }
  default:
    writer.sendError(id: cmd.id, code: "unknown_cmd", message: "Unknown command \(cmd.cmd)")
  }
}

extension CentralController {
  func sessions(contains sessionId: String) -> Bool {
    if case .some(.central) = registry.session(for: sessionId) { return true }
    return false
  }
}

// Read stdin synchronously on a background thread.
DispatchQueue.global(qos: .utility).async {
  while true {
    let chunk = stdin.availableData
    if chunk.isEmpty {
      // Parent closed stdin → exit.
      exit(0)
    }
    buffer.append(chunk)
    while let nlIndex = buffer.firstIndex(of: 0x0A) {
      let lineData = buffer.subdata(in: 0..<nlIndex)
      buffer.removeSubrange(0...nlIndex)
      if let line = String(data: lineData, encoding: .utf8), !line.isEmpty {
        dispatch(line: line)
      }
    }
  }
}

RunLoop.main.run()
