import Foundation
import CoreBluetooth

// CBPeripheralManager driver: hosts the Klardrop GATT service, advertises with the
// short device id in service data + local name, and emits a session every time a
// remote central subscribes to our RX characteristic, and publishes an L2CAP CoC channel
// (PSM readable via GATT). All state is confined to the helper queue (CB delegate queue).
final class PeripheralController: NSObject, CBPeripheralManagerDelegate {

  private let writer: StdoutWriter
  private let queue: DispatchQueue
  private let registry: SessionRegistry
  private var manager: CBPeripheralManager!

  private var service: CBMutableService?
  private var txCharacteristic: CBMutableCharacteristic?
  private var rxCharacteristic: CBMutableCharacteristic?

  private var pendingShortDeviceId: String?
  private var pendingLocalName: String?
  private var pendingAdvertiseRequestId: String?
  private var serviceAdded = false

  // Active peripheral-role sessions keyed by central identifier UUID string.
  private var sessions: [String: PeripheralSession] = [:]

  // Active peripheral-role L2CAP sessions keyed by sessionId.
  private var l2capSessions: [String: L2capSession] = [:]
  private var psm: CBL2CAPPSM?
  private var published = false

  init(writer: StdoutWriter, queue: DispatchQueue, registry: SessionRegistry) {
    self.writer = writer
    self.queue = queue
    self.registry = registry
    super.init()
    self.manager = CBPeripheralManager(delegate: self, queue: queue, options: [
      CBPeripheralManagerOptionShowPowerAlertKey: false
    ])
  }

  func startAdvertising(requestId: String, shortDeviceId: String, localName: String?) {
    pendingShortDeviceId = shortDeviceId
    pendingLocalName = localName
    pendingAdvertiseRequestId = requestId

    if !serviceAdded {
      installService()
    }

    guard manager.state == .poweredOn else {
      // We will try again when the state flips to powered on.
      return
    }
    actuallyStartAdvertising()
  }

  func stopAdvertising(requestId: String) {
    if manager.isAdvertising {
      manager.stopAdvertising()
    }
    pendingAdvertiseRequestId = nil
    writer.sendOk(id: requestId)
  }

  func sendChunk(requestId: String, sessionId: String, data: Data) {
    guard let session = registry.session(for: sessionId), let peripheral = session.asPeripheral() else {
      writer.sendError(id: requestId, code: "unknown_session", message: "No session \(sessionId)")
      return
    }
    peripheral.enqueueNotify(requestId: requestId, data: data)
    pumpNotifications()
  }

  func closeSession(requestId: String, sessionId: String) {
    if let session = registry.session(for: sessionId), let peripheral = session.asPeripheral() {
      sessions.removeValue(forKey: peripheral.centralId)
      registry.remove(sessionId: sessionId)
      writer.sendEvent("session_closed", ["sessionId": sessionId, "reason": "closed_by_app"])
    }
    writer.sendOk(id: requestId)
  }

  func shutdown() {
    if manager.isAdvertising { manager.stopAdvertising() }
    sessions.removeAll()
    for (_, s) in l2capSessions { s.teardown(reason: "shutdown") }
    if serviceAdded {
      manager.removeAllServices()
      serviceAdded = false
    }
  }

  // MARK: CBPeripheralManagerDelegate

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    if peripheral.state == .poweredOn {
      if !serviceAdded { installService() }
      if !published {
        published = true
        manager.publishL2CAPChannel(withEncryption: false)
      }
      if pendingAdvertiseRequestId != nil { actuallyStartAdvertising() }
    } else {
      psm = nil
      published = false
      for (_, s) in l2capSessions { s.teardown(reason: "manager_off") }
      // Sessions tied to the peripheral manager are now invalid.
      for (_, session) in sessions {
        registry.remove(sessionId: session.sessionId)
        writer.sendEvent("session_closed", ["sessionId": session.sessionId, "reason": "manager_off"])
      }
      sessions.removeAll()
    }
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    guard let requestId = pendingAdvertiseRequestId else { return }
    pendingAdvertiseRequestId = nil
    if let error = error {
      writer.sendError(id: requestId, code: "advertise_failed", message: error.localizedDescription)
    } else {
      writer.sendOk(id: requestId)
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    guard characteristic.uuid == CBUUID(string: BleConstants.rxCharacteristicUUID) else { return }
    let centralId = central.identifier.uuidString
    if sessions[centralId] != nil { return } // duplicate subscribe
    let sessionId = UUID().uuidString
    let mtu = central.maximumUpdateValueLength
    let session = PeripheralSession(
      sessionId: sessionId,
      central: central,
      rx: characteristic,
      manager: manager,
      mtu: mtu,
      writer: writer
    )
    sessions[centralId] = session
    registry.add(sessionId: sessionId, session: .peripheral(session))
    writer.sendEvent("session_opened", [
      "sessionId": sessionId,
      "peerShortDeviceId": centralId, // Apple does not expose remote short id; address is the identifier
      "mtu": mtu,
      "role": "peripheral",
    ])
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
    let centralId = central.identifier.uuidString
    guard let session = sessions.removeValue(forKey: centralId) else { return }
    registry.remove(sessionId: session.sessionId)
    writer.sendEvent("session_closed", ["sessionId": session.sessionId, "reason": "unsubscribed"])
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    for req in requests {
      let centralId = req.central.identifier.uuidString
      if req.characteristic.uuid == CBUUID(string: BleConstants.txCharacteristicUUID),
         let value = req.value,
         let session = sessions[centralId] {
        writer.sendEvent("chunk", [
          "sessionId": session.sessionId,
          "data": value.base64EncodedString(),
        ])
      }
      manager.respond(to: req, withResult: .success)
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
    if let error = error {
      published = false // retry on next poweredOn
      writer.sendLog("warn", "L2CAP publish failed: \(error.localizedDescription)")
      return
    }
    psm = PSM
    writer.sendLog("info", "L2CAP listening psm=\(PSM)")
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    guard request.characteristic.uuid == CBUUID(string: BleConstants.psmCharacteristicUUID), let psm = psm else {
      manager.respond(to: request, withResult: .readNotPermitted)
      return
    }
    let value = Data([UInt8(psm & 0xFF), UInt8(psm >> 8)])
    if request.offset > value.count {
      manager.respond(to: request, withResult: .invalidOffset)
      return
    }
    request.value = value.subdata(in: request.offset..<value.count)
    manager.respond(to: request, withResult: .success)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
    guard error == nil, let channel = channel else {
      writer.sendLog("warn", "L2CAP incoming channel failed: \(error?.localizedDescription ?? "nil channel")")
      return
    }
    let session = L2capSession(
      channel: channel, sessionId: UUID().uuidString, role: "peripheral", peripheral: nil,
      writer: writer, queue: queue
    ) { [weak self] session, reason in
      guard let self = self else { return }
      self.l2capSessions.removeValue(forKey: session.sessionId)
      self.registry.remove(sessionId: session.sessionId)
      self.writer.sendEvent("session_closed", ["sessionId": session.sessionId, "reason": reason])
    }
    l2capSessions[session.sessionId] = session
    registry.add(sessionId: session.sessionId, session: .l2cap(session))
    session.start()
    writer.sendEvent("session_opened", [
      "sessionId": session.sessionId,
      "peerShortDeviceId": channel.peer.identifier.uuidString,
      "mtu": BleConstants.l2capChunkSize,
      "role": "peripheral",
      "transport": "l2cap",
    ])
  }

  func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
    pumpNotifications()
  }

  // MARK: Helpers

  private func installService() {
    let serviceUUID = CBUUID(string: BleConstants.serviceUUID)
    let txUuid = CBUUID(string: BleConstants.txCharacteristicUUID)
    let rxUuid = CBUUID(string: BleConstants.rxCharacteristicUUID)

    let tx = CBMutableCharacteristic(type: txUuid, properties: [.write], value: nil, permissions: [.writeable])
    let rx = CBMutableCharacteristic(type: rxUuid, properties: [.notify], value: nil, permissions: [.readable])
    let svc = CBMutableService(type: serviceUUID, primary: true)
    // nil value so reads reach didReceiveRead: the PSM is only known after publish.
    let psmChar = CBMutableCharacteristic(
      type: CBUUID(string: BleConstants.psmCharacteristicUUID), properties: [.read], value: nil, permissions: [.readable])
    svc.characteristics = [tx, rx, psmChar]

    self.txCharacteristic = tx
    self.rxCharacteristic = rx
    self.service = svc

    if manager.state == .poweredOn {
      manager.add(svc)
      serviceAdded = true
    }
  }

  private func actuallyStartAdvertising() {
    guard let shortId = pendingShortDeviceId else { return }
    if !serviceAdded, let svc = service {
      manager.add(svc)
      serviceAdded = true
    }
    let serviceUUID = CBUUID(string: BleConstants.serviceUUID)
    var data: [String: Any] = [
      CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
    ]
    // Carry short device id as the local name so iOS/macOS centrals can read it
    // straight from the scan callback. Apple's CB does not let us add custom
    // service-data to advertisements, so this is the lowest-friction channel.
    let advertisedName = pendingLocalName ?? shortId
    data[CBAdvertisementDataLocalNameKey] = advertisedName
    if manager.isAdvertising { manager.stopAdvertising() }
    manager.startAdvertising(data)
  }

  private func pumpNotifications() {
    for (_, session) in sessions {
      session.pumpNotifications()
    }
  }
}

// Single subscribed central. Notifications can fail with "not ready"; we hold the
// queue and retry from peripheralManagerIsReady(toUpdateSubscribers:).
final class PeripheralSession {
  let sessionId: String
  let centralId: String
  let central: CBCentral
  let rx: CBMutableCharacteristic
  let manager: CBPeripheralManager
  let mtu: Int

  private let writer: StdoutWriter
  private var notifyQueue: [(requestId: String, data: Data)] = []
  private var inFlightRequestId: String?

  init(sessionId: String, central: CBCentral, rx: CBCharacteristic, manager: CBPeripheralManager, mtu: Int, writer: StdoutWriter) {
    self.sessionId = sessionId
    self.centralId = central.identifier.uuidString
    self.central = central
    // The CB API requires the mutable variant for updateValue, which is what we registered above.
    self.rx = rx as! CBMutableCharacteristic
    self.manager = manager
    self.mtu = mtu
    self.writer = writer
  }

  func enqueueNotify(requestId: String, data: Data) {
    notifyQueue.append((requestId, data))
  }

  func pumpNotifications() {
    while let next = notifyQueue.first {
      let ok = manager.updateValue(next.data, for: rx, onSubscribedCentrals: [central])
      if !ok {
        // Buffer full; resume on peripheralManagerIsReady.
        return
      }
      notifyQueue.removeFirst()
      writer.sendOk(id: next.requestId)
    }
  }
}
