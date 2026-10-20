import XCTest
@testable import Klardrop

// Reads the exported permissions grants, connectivity-restriction reasons,
// and device connection kinds through the app's linked swift-export framework.
// On main the permissions getter aborts (KLARDROP-KJ) instead of returning.
final class SwiftExportBridgeTests: XCTestCase {
    func testExportedEnumCollectionsDoNotAbort() {
        let snapshot = SwiftExportBridgeProbe.exerciseStartupCollections()
        XCTAssertEqual(snapshot.educationalNoteCount, 1)
        XCTAssertFalse(snapshot.isComplete)
        XCTAssertEqual(snapshot.capabilityCount, 3)
        XCTAssertTrue(snapshot.bluetoothDenied)
        XCTAssertEqual(snapshot.reasonCount, 2)
        XCTAssertTrue(snapshot.sawBatterySaver)
        XCTAssertEqual(snapshot.connectionCount, 2)
        XCTAssertTrue(snapshot.sawKlardropConnection)
    }
}
