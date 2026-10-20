import XCTest
@testable import Klardrop

// Reads the exported permissions map and the connectivity-restriction set
// through the app's linked swift-export framework. On main this aborts in
// the generated getter (KLARDROP-KJ) instead of returning.
final class SwiftExportBridgeTests: XCTestCase {
    func testExportedEnumCollectionsDoNotAbort() {
        let snapshot = SwiftExportBridgeProbe.exerciseStartupCollections()
        XCTAssertEqual(snapshot.educationalNoteCount, 1)
        XCTAssertFalse(snapshot.isComplete)
        XCTAssertEqual(snapshot.capabilityCount, 3)
        XCTAssertTrue(snapshot.bluetoothDenied)
        XCTAssertEqual(snapshot.reasonCount, 2)
    }
}
