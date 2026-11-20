import Foundation
import CoreBluetooth

// One L2CAP CoC channel. Stream state, the write FIFO and `closed` are confined to the
// main thread (streams run on RunLoop.main); `onClosed` is invoked on helperQueue.
final class L2capSession: NSObject, StreamDelegate {
  let sessionId: String
  let role: String
  // Central role only: lets teardown cancel the connection.
  let peripheral: CBPeripheral?

  // Strong ref: CoreBluetooth closes the channel when it is deallocated.
  private let channel: CBL2CAPChannel
  private let writer: StdoutWriter
  private let queue: DispatchQueue
  private let onClosed: (L2capSession, String) -> Void

  // Main thread only.
  private var writes: [(requestId: String, data: Data, offset: Int)] = []
  private var closed = false
  private var readBuf = [UInt8](repeating: 0, count: BleConstants.l2capChunkSize)

  init(channel: CBL2CAPChannel, sessionId: String, role: String, peripheral: CBPeripheral?,
       writer: StdoutWriter, queue: DispatchQueue, onClosed: @escaping (L2capSession, String) -> Void) {
    self.channel = channel
    self.sessionId = sessionId
    self.role = role
    self.peripheral = peripheral
    self.writer = writer
    self.queue = queue
    self.onClosed = onClosed
  }

  // A channel that arrived too late (timeout/fallback): close its streams and drop it.
  static func discard(_ channel: CBL2CAPChannel) {
    DispatchQueue.main.async {
      channel.inputStream?.close()
      channel.outputStream?.close()
    }
  }

  // Any thread.
  func start() {
    DispatchQueue.main.async {
      guard !self.closed else { return }
      guard let input = self.channel.inputStream, let output = self.channel.outputStream else {
        self.teardownOnMain("no_streams")
        return
      }
      for s in [input as Stream, output as Stream] {
        s.delegate = self
        s.schedule(in: RunLoop.main, forMode: .common)
        s.open()
      }
      self.writer.sendLog("info", "L2CAP session \(self.sessionId) role=\(self.role) psm=\(self.channel.psm)")
    }
  }

  // Any thread.
  func enqueue(requestId: String, data: Data) {
    DispatchQueue.main.async {
      if self.closed {
        self.writer.sendError(id: requestId, code: "write_failed", message: "L2CAP session closed")
        return
      }
      self.writes.append((requestId, data, 0))
      self.pumpWrites()
    }
  }

  // Any thread; idempotent.
  func teardown(reason: String) {
    DispatchQueue.main.async { self.teardownOnMain(reason) }
  }

  // MARK: StreamDelegate (main thread)

  func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
    if closed { return }
    if eventCode.contains(.hasBytesAvailable), let reason = drainInput() {
      teardownOnMain(reason)
      return
    }
    if eventCode.contains(.hasSpaceAvailable) { pumpWrites() }
    if closed { return }
    if eventCode.contains(.errorOccurred) || eventCode.contains(.endEncountered) {
      _ = drainInput() // bytes received before the remote closed are still readable
      teardownOnMain(aStream.streamError?.localizedDescription ?? "remote_closed")
    }
  }

  // MARK: Main-thread internals

  // Emits every readable byte as chunk events. Returns a close reason on EOF/error, else nil.
  private func drainInput() -> String? {
    guard let input = channel.inputStream else { return "remote_closed" }
    while input.hasBytesAvailable {
      let n = input.read(&readBuf, maxLength: readBuf.count)
      if n < 0 { return input.streamError?.localizedDescription ?? "remote_closed" }
      if n == 0 { return "remote_closed" }
      writer.sendEvent("chunk", [
        "sessionId": sessionId,
        "data": Data(readBuf[0..<n]).base64EncodedString(),
      ])
    }
    return nil
  }

  // Handles partial writes: a request is acked once its whole chunk was accepted.
  private func pumpWrites() {
    guard let output = channel.outputStream else { return }
    while !closed, let head = writes.first {
      let remaining = head.data.count - head.offset
      if remaining > 0 {
        guard output.hasSpaceAvailable else { return }
        let n = head.data.withUnsafeBytes { raw -> Int in
          output.write(raw.bindMemory(to: UInt8.self).baseAddress! + head.offset, maxLength: remaining)
        }
        if n <= 0 {
          teardownOnMain("write_failed")
          return
        }
        writes[0].offset += n
        if n < remaining { continue }
      }
      writes.removeFirst()
      writer.sendOk(id: head.requestId)
    }
  }

  private func teardownOnMain(_ reason: String) {
    if closed { return }
    closed = true
    for s in [channel.inputStream, channel.outputStream] {
      guard let s = s else { continue }
      s.delegate = nil
      s.close()
      s.remove(from: RunLoop.main, forMode: .common)
    }
    for w in writes {
      writer.sendError(id: w.requestId, code: "write_failed", message: "L2CAP session closed: \(reason)")
    }
    writes.removeAll()
    queue.async { self.onClosed(self, reason) }
  }
}
