import Foundation
import CoreBluetooth

// CBCentralManager driver: scans for Klardrop peripherals, connects, opens GATT, and
// pumps chunks bidirectionally for any session created in the central role.
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
    // Duplicates ON, matching Android's CALLBACK_TYPE_ALL_MATCHES. De-duplicated scanning
    // gives roughly one didDiscover per peer per scan, which drops a peer whose first
    // packet arrives before its scan-response service-data and never refreshes the
    // liveness timestamp of a peer that is sitting still and advertising.
    manager.scanForPeripherals(withServices: [serviceUUID], options: [
      CBCentralManagerScanOptionAllowDuplicatesKey: true
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
    // Forward the raw advertisement fields and let the Kotlin side decode them with the
    // shared BleAdvertisementCodec. Deciding here what counts as a Klardrop peer is how
    // this helper drifted from the Android and iOS transports in the first place; the
    // rule now lives in commonMain where one test covers every platform.
    //
    // Note we deliberately do NOT send peripheral.name (the GAP device name). Only the
    // advertisement is identity: adopting the BT name produced two rows for one device,
    // "unknown" and then "Pixel 9 Pro XL", keyed separately by the discovery layer.
    var fields: [String: Any] = [
      "peerId": peerId,
      "rssi": RSSI.intValue,
    ]
    let serviceUUID = CBUUID(string: BleConstants.serviceUUID)
    if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
       let bytes = serviceData[serviceUUID] {
      fields["serviceData"] = bytes.base64EncodedString()
    }
    if let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String {
      fields["localName"] = localName
    }
    writer.sendEvent("peer_found", fields)
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    peripheral.discoverServices([CBUUID(string: BleConstants.serviceUUID)])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    if let pending = connectInFlight.removeValue(forKey: peerId) {
      writer.sendError(id: pending.requestId, code: "connect_failed", message: error?.localizedDescription ?? "unknown")
    }
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    if let pending = connectInFlight.removeValue(forKey: peerId) {
      writer.sendError(id: pending.requestId, code: "disconnected", message: error?.localizedDescription ?? "disconnected during handshake")
    }
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
    guard let pending = connectInFlight[peerId] else { return }
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
    sessions[peerId] = session
    registry.add(sessionId: pending.sessionId, session: .central(session))
    peripheral.setNotifyValue(true, for: rx)
    // Defer ok until didUpdateNotificationStateFor confirms subscription.
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
    if let pending = connectInFlight.removeValue(forKey: peerId) {
      writer.sendError(id: pending.requestId, code: code, message: message)
    }
    manager.cancelPeripheralConnection(peripheral)
  }

}

private struct PendingConnect {
  let requestId: String
  let sessionId: String
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
