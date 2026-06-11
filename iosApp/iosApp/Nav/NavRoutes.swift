import Foundation

// ---------------------------------------------------------------------------
// NavRoutes — Navigation value types for KlardropNav.
// ChatRoute is the Hashable type pushed onto NavigationStack.path (compact)
// and set as the sidebar selection (regular).
// ---------------------------------------------------------------------------

struct ChatRoute: Hashable {
    let deviceId: String
    let deviceName: String
}
