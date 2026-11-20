import Foundation
import CoreBluetooth

// CBCentralManager driver: scans for Klardrop peripherals, connects, opens GATT, and
// pumps chunks bidirectionally for any session created in the central role. Prefers an
// L2CAP CoC channel (PSM read from GATT) and falls back to GATT notify/write.
// All state is confined to the helper queue (CB delegate queue); L2capSession hops to main itself.
final class CentralController: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

  private let writer: StdoutWriter
  private let queue: DispatchQueue
  private let registry: SessionRegistry
  private var manager: CBCentralManager!

  // Discovered peripherals keyed by their identifier UUID string. Kept so connect can
  // retrieve a peer that was previously seen by scan_start.
  private var discovered: [String: CBPeripheral] = [:]

  // In-flight connect requests: peerId → (requestId, sessionId) so we can reply ok
  // once the GATT handshake (services + characteristics + notify enabled) is done.
  private var connectInFlight: [String: PendingConnect] = [:]

  // Active central-role sessions keyed by peer identifier UUID string.
  private var sessions: [String: CentralSession] = [:]

  // Active central-role L2CAP sessions keyed by peer identifier UUID string.
  private var l2capSessions: [String: L2capSession] = [:]

  private var scanning = false

  init(writer: StdoutWriter, queue: DispatchQueue, registry: SessionRegistry) {
    self.writer = writer
    self.queue = queue
    self.registry = registry
    super.init()
    self.manager = CBCentralManager(delegate: self, queue: queue, options: [
      CBCentralManagerOptionShowPowerAlertKey: false
    ])
  }

  var isPoweredOn: Bool { manager.state == .poweredOn }

  func startScan(requestId: String) {
    guard manager.state == .poweredOn else {
      writer.sendError(id: requestId, code: "not_powered_on", message: "Bluetooth not powered on")
      return
    }
    let serviceUUID = CBUUID(string: BleConstants.serviceUUID)
    if scanning { manager.stopScan() }
    manager.scanForPeripherals(withServices: [serviceUUID], options: [
      CBCentralManagerScanOptionAllowDuplicatesKey: false
    ])
    scanning = true
    writer.sendOk(id: requestId)
  }

  func stopScan(requestId: String) {
    if scanning {
      manager.stopScan()
      scanning = false
    }
    writer.sendOk(id: requestId)
  }

  func connect(requestId: String, peerId: String) {
    guard manager.state == .poweredOn else {
      writer.sendError(id: requestId, code: "not_powered_on", message: "Bluetooth not powered on")
      return
    }
    let peripheral: CBPeripheral
    if let existing = discovered[peerId] {
      peripheral = existing
    } else if let uuid = UUID(uuidString: peerId), let retrieved = manager.retrievePeripherals(withIdentifiers: [uuid]).first {
      peripheral = retrieved
      discovered[peerId] = retrieved
    } else {
      writer.sendError(id: requestId, code: "unknown_peer", message: "Peer \(peerId) not in cache; scan first")
      return
    }
    peripheral.delegate = self
    let sessionId = UUID().uuidString
    connectInFlight[peerId] = PendingConnect(requestId: requestId, sessionId: sessionId)
    manager.connect(peripheral, options: nil)
  }

  func sendChunk(requestId: String, sessionId: String, data: Data) {
    guard let session = registry.session(for: sessionId), let central = session.asCentral() else {
      writer.sendError(id: requestId, code: "unknown_session", message: "No session \(sessionId)")
      return
    }
    central.enqueueWrite(requestId: requestId, data: data)
  }

  func closeSession(requestId: String, sessionId: String) {
    if let session = registry.session(for: sessionId), let central = session.asCentral() {
      manager.cancelPeripheralConnection(central.peripheral)
      sessions.removeValue(forKey: central.peripheral.identifier.uuidString)
      registry.remove(sessionId: sessionId)
      writer.sendEvent("session_closed", ["sessionId": sessionId, "reason": "closed_by_app"])
    }
    writer.sendOk(id: requestId)
  }

  func shutdown() {
    if scanning { manager.stopScan() }
    for (_, session) in sessions {
      manager.cancelPeripheralConnection(session.peripheral)
    }
    sessions.removeAll()
    for (_, s) in l2capSessions { s.teardown(reason: "shutdown") }
  }

  // MARK: CBCentralManagerDelegate

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    let state: String
    switch central.state {
    case .poweredOn: state = "poweredOn"
    case .poweredOff: state = "poweredOff"
    case .resetting: state = "resetting"
    case .unauthorized: state = "unauthorized"
    case .unsupported: state = "unsupported"
    case .unknown: fallthrough
    @unknown default: state = "unknown"
    }
    writer.sendEvent("state", ["state": state])
  }

  func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
    let peerId = peripheral.identifier.uuidString
    discovered[peerId] = peripheral
    // Skip packets that don't carry a Klardrop-shaped short device id. CoreBluetooth
    // delivers many didDiscover callbacks per peer with varying advertisementData;
    // if we accept the BT peripheral name as a fallback we end up emitting two
    // distinct "peers" (e.g. "unknown" then "Pixel 9 Pro XL") for the same device,
    // which the discovery layer keys separately and surfaces as duplicate rows.
    guard let shortId = decodeShortDeviceId(advertisementData: advertisementData) else { return }
    var fields: [String: Any] = [
      "peerId": peerId,
      "shortDeviceId": shortId,
      "rssi": RSSI.intValue,
    ]
    if let name = peripheral.name { fields["localName"] = name }
    writer.sendEvent("peer_found", fields)
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    peripheral.discoverServices([CBUUID(string: BleConstants.serviceUUID)])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    if let pending = dropPending(peerId) {
      writer.sendError(id: pending.requestId, code: "connect_failed", message: error?.localizedDescription ?? "unknown")
    }
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    if let pending = dropPending(peerId) {
      writer.sendError(id: pending.requestId, code: "disconnected", message: error?.localizedDescription ?? "disconnected during handshake")
    }
    l2capSessions[peerId]?.teardown(reason: error?.localizedDescription ?? "disconnected")
    if let session = sessions.removeValue(forKey: peerId) {
      registry.remove(sessionId: session.sessionId)
      writer.sendEvent("session_closed", ["sessionId": session.sessionId, "reason": error?.localizedDescription ?? "disconnected"])
    }
  }

  // MARK: CBPeripheralDelegate

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: BleConstants.serviceUUID) }) else {
      failConnect(peripheral: peripheral, code: "service_missing", message: "Service not exposed")
      return
    }
    peripheral.discoverCharacteristics([
      CBUUID(string: BleConstants.txCharacteristicUUID),
      CBUUID(string: BleConstants.rxCharacteristicUUID),
      CBUUID(string: BleConstants.psmCharacteristicUUID),
    ], for: service)
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    let txUuid = CBUUID(string: BleConstants.txCharacteristicUUID)
    let rxUuid = CBUUID(string: BleConstants.rxCharacteristicUUID)
    let chars = service.characteristics ?? []
    guard let tx = chars.first(where: { $0.uuid == txUuid }), let rx = chars.first(where: { $0.uuid == rxUuid }) else {
      failConnect(peripheral: peripheral, code: "characteristic_missing", message: "TX/RX missing")
      return
    }
    let peerId = peripheral.identifier.uuidString
    guard let pending = connectInFlight[peerId], pending.gattSession == nil else { return }
    let mtu = peripheral.maximumWriteValueLength(for: .withResponse)
    let session = CentralSession(
      sessionId: pending.sessionId,
      peripheral: peripheral,
      tx: tx,
      rx: rx,
      mtu: mtu,
      writer: writer,
      queue: queue
    )
    guard let psmChar = chars.first(where: { $0.uuid == CBUUID(string: BleConstants.psmCharacteristicUUID) }) else {
      registerGatt(session)
      return
    }
    // Try L2CAP first. The GATT session stays unregistered (never subscribed) until a
    // fallback actually chooses GATT.
    pending.gattSession = session
    pending.l2capPending = true
    let timer = DispatchWorkItem { [weak self] in self?.fallbackToGatt(peerId: peerId, reason: "timeout") }
    pending.timer = timer
    queue.asyncAfter(deadline: .now() + BleConstants.l2capOpenTimeout, execute: timer)
    peripheral.readValue(for: psmChar)
  }

  func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    guard let pending = connectInFlight[peerId], pending.l2capPending else {
      // Late channel after timeout / fallback / failure.
      if let channel = channel { L2capSession.discard(channel) }
      return
    }
    guard error == nil, let channel = channel else {
      fallbackToGatt(peerId: peerId, reason: error?.localizedDescription ?? "open_failed")
      return
    }
    pending.timer?.cancel()
    pending.l2capPending = false
    pending.gattSession = nil // never subscribed; discard
    connectInFlight.removeValue(forKey: peerId)
    let session = L2capSession(
      channel: channel, sessionId: pending.sessionId, role: "central", peripheral: peripheral,
      writer: writer, queue: queue
    ) { [weak self] session, reason in self?.l2capClosed(peerId: peerId, session: session, reason: reason) }
    l2capSessions[peerId] = session
    registry.add(sessionId: session.sessionId, session: .l2cap(session))
    writer.sendOk(id: pending.requestId, extra: [
      "sessionId": pending.sessionId,
      "mtu": BleConstants.l2capChunkSize,
      "transport": "l2cap",
    ])
    session.start() // after the connect ok so no chunk event can precede it
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    guard let pending = connectInFlight[peerId] else { return }
    if let error = error {
      failConnect(peripheral: peripheral, code: "notify_failed", message: error.localizedDescription)
      return
    }
    if characteristic.uuid != CBUUID(string: BleConstants.rxCharacteristicUUID) { return }
    connectInFlight.removeValue(forKey: peerId)
    let mtu = peripheral.maximumWriteValueLength(for: .withResponse)
    writer.sendOk(id: pending.requestId, extra: ["sessionId": pending.sessionId, "mtu": mtu])
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    if characteristic.uuid == CBUUID(string: BleConstants.psmCharacteristicUUID) {
      guard let pending = connectInFlight[peerId], pending.l2capPending else { return }
      guard error == nil, let v = characteristic.value, v.count == 2 else {
        fallbackToGatt(peerId: peerId, reason: error?.localizedDescription ?? "invalid_psm")
        return
      }
      let psm = CBL2CAPPSM(v[v.startIndex]) | (CBL2CAPPSM(v[v.startIndex + 1]) << 8)
      if psm == 0 {
        fallbackToGatt(peerId: peerId, reason: "invalid_psm")
        return
      }
      peripheral.openL2CAPChannel(psm)
      return
    }
    guard let session = sessions[peerId] else { return }
    if let value = characteristic.value, !value.isEmpty {
      writer.sendEvent("chunk", [
        "sessionId": session.sessionId,
        "data": value.base64EncodedString(),
      ])
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    guard let session = sessions[peerId] else { return }
    session.onWriteAck(error: error)
  }

  // MARK: Helpers

  private func failConnect(peripheral: CBPeripheral, code: String, message: String) {
    let peerId = peripheral.identifier.uuidString
    if let pending = dropPending(peerId) {
      writer.sendError(id: pending.requestId, code: code, message: message)
    }
    manager.cancelPeripheralConnection(peripheral)
  }

  // Removes the in-flight connect and cancels its L2CAP timer.
  private func dropPending(_ peerId: String) -> PendingConnect? {
    guard let pending = connectInFlight.removeValue(forKey: peerId) else { return nil }
    pending.timer?.cancel()
    pending.l2capPending = false
    return pending
  }

  // Idempotent per attempt (guarded by l2capPending).
  private func fallbackToGatt(peerId: String, reason: String) {
    guard let pending = connectInFlight[peerId], pending.l2capPending, let session = pending.gattSession else { return }
    pending.timer?.cancel()
    pending.l2capPending = false
    writer.sendLog("info", "L2CAP to \(peerId) failed (\(reason)), using GATT")
    registerGatt(session)
  }

  // ok is sent later from didUpdateNotificationStateFor.
  private func registerGatt(_ session: CentralSession) {
    let peerId = session.peripheral.identifier.uuidString
    sessions[peerId] = session
    registry.add(sessionId: session.sessionId, session: .central(session))
    writer.sendLog("info", "GATT session \(session.sessionId) role=central mtu=\(session.mtu)")
    session.peripheral.setNotifyValue(true, for: session.rx)
  }

  func cancelConnection(_ p: CBPeripheral) { manager.cancelPeripheralConnection(p) }

  private func l2capClosed(peerId: String, session: L2capSession, reason: String) {
    if l2capSessions[peerId] === session { l2capSessions.removeValue(forKey: peerId) }
    registry.remove(sessionId: session.sessionId)
    // Remote-initiated close: cancel here. App-initiated close_session already cancelled synchronously
    // (main.swift); this deferred repeat must not kill a newer connect to the same peer, so cancel
    // only when nothing newer (connect in flight, GATT or L2CAP session) is using it.
    if let p = session.peripheral, connectInFlight[peerId] == nil, sessions[peerId] == nil, l2capSessions[peerId] == nil {
      manager.cancelPeripheralConnection(p)
    }
    writer.sendEvent("session_closed", ["sessionId": session.sessionId, "reason": reason])
  }

  // Pull the short device id from advertisement data. Android peers carry it as
  // service-data under our service UUID. Apple peers may also advertise the same
  // service-data when running through this helper or the iOS app.
  private func decodeShortDeviceId(advertisementData: [String: Any]) -> String? {
    let serviceUUID = CBUUID(string: BleConstants.serviceUUID)
    if let data = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
       let bytes = data[serviceUUID],
       let s = String(data: bytes, encoding: .utf8),
       isKlardropShortDeviceId(s) {
      return s
    }
    // macOS peripherals advertise the short id as the local name (Apple's CB
    // does not expose the service-data slot to apps). Only accept it when it
    // matches the Klardrop short-id shape, otherwise BT names from non-Klardrop
    // peers leak in as fake peer identities.
    if let local = advertisementData[CBAdvertisementDataLocalNameKey] as? String,
       isKlardropShortDeviceId(local) {
      return local
    }
    return nil
  }

  private func isKlardropShortDeviceId(_ s: String) -> Bool {
    guard s.count == 8 else { return false }
    return s.allSatisfy { $0.isHexDigit }
  }
}

private final class PendingConnect {
  let requestId: String
  let sessionId: String
  // L2CAP attempt state. gattSession is only registered if the GATT path is chosen.
  var gattSession: CentralSession?
  var l2capPending = false
  var timer: DispatchWorkItem?

  init(requestId: String, sessionId: String) {
    self.requestId = requestId
    self.sessionId = sessionId
  }
}

// Holds a single central-role GATT connection. Serializes writes so chunks are
// delivered in order; callers await ack via the requestId pinned on each write.
final class CentralSession {
  let sessionId: String
  let peripheral: CBPeripheral
  let tx: CBCharacteristic
  let rx: CBCharacteristic
  let mtu: Int

  private let writer: StdoutWriter
  private let queue: DispatchQueue

  private var writeQueue: [(requestId: String, data: Data)] = []
  private var inFlightRequestId: String?

  init(sessionId: String, peripheral: CBPeripheral, tx: CBCharacteristic, rx: CBCharacteristic, mtu: Int, writer: StdoutWriter, queue: DispatchQueue) {
    self.sessionId = sessionId
    self.peripheral = peripheral
    self.tx = tx
    self.rx = rx
    self.mtu = mtu
    self.writer = writer
    self.queue = queue
  }

  func enqueueWrite(requestId: String, data: Data) {
    writeQueue.append((requestId, data))
    pumpNextLocked()
  }

  func onWriteAck(error: Error?) {
    guard let requestId = inFlightRequestId else { return }
    inFlightRequestId = nil
    if let error = error {
      writer.sendError(id: requestId, code: "write_failed", message: error.localizedDescription)
    } else {
      writer.sendOk(id: requestId)
    }
    pumpNextLocked()
  }

  private func pumpNextLocked() {
    guard inFlightRequestId == nil, !writeQueue.isEmpty else { return }
    let next = writeQueue.removeFirst()
    inFlightRequestId = next.requestId
    peripheral.writeValue(next.data, for: tx, type: .withResponse)
  }
}
