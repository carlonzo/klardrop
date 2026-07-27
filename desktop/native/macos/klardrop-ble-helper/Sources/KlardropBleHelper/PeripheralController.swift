import Foundation
import CoreBluetooth

// CBPeripheralManager driver: hosts the Klardrop GATT service, advertises with the
// short device id in service data + local name, and emits a session every time a
// remote central subscribes to our RX characteristic.
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
  // Whether the app wants us advertising, independent of `pendingAdvertiseRequestId`
  // (which is consumed by the first didStartAdvertising callback). CoreBluetooth stops
  // advertising and drops published services whenever the radio power-cycles and never
  // restores either, so we need our own record of the intent to re-arm from.
  private var advertisingDesired = false

  // Active peripheral-role sessions keyed by central identifier UUID string.
  private var sessions: [String: PeripheralSession] = [:]

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
    advertisingDesired = true

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
    advertisingDesired = false
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
    advertisingDesired = false
    if manager.isAdvertising { manager.stopAdvertising() }
    sessions.removeAll()
    if serviceAdded {
      manager.removeAllServices()
      serviceAdded = false
    }
  }

  // MARK: CBPeripheralManagerDelegate

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    if peripheral.state == .poweredOn {
      if !serviceAdded { installService() }
      // Re-arm on every power-up, not just the first: `pendingAdvertiseRequestId` is
      // cleared by the didStartAdvertising callback, so keying off it would leave us
      // silently non-discoverable after the radio comes back.
      if advertisingDesired { actuallyStartAdvertising() }
    } else {
      // Sessions tied to the peripheral manager are now invalid.
      for (_, session) in sessions {
        registry.remove(sessionId: session.sessionId)
        writer.sendEvent("session_closed", ["sessionId": session.sessionId, "reason": "manager_off"])
      }
      sessions.removeAll()
      // CoreBluetooth drops published services when the radio goes down; force a
      // re-install on the next power-up rather than trusting the stale flag.
      serviceAdded = false
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
    svc.characteristics = [tx, rx]

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
    //
    // Truncated to the short-id length on purpose: flags (3) + 128-bit service UUID
    // (2 + 16) + local name (2 + n) has to fit the 31-byte legacy advertisement, and
    // n = 8 lands on exactly 31. A longer name pushes the service UUID into Apple's
    // proprietary "overflow area", where only other Apple devices can decode it —
    // Android scanners filtering on the service UUID would stop seeing us entirely.
    let advertisedName = String((pendingLocalName ?? shortId).prefix(BleConstants.maxShortDeviceIdLength))
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
