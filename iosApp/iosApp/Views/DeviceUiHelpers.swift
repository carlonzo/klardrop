import presentation

// ---------------------------------------------------------------------------
// DeviceUiHelpers — pure Swift helpers that map DeviceUi sealed types
// (TrustStatus, Reachability, ActivityState) to display-layer values.
//
// Uses onEnum(of:) for exhaustive, future-proof switching on Kotlin sealed
// interfaces. DeviceType is a genuine @frozen Swift enum — switch directly.
// ---------------------------------------------------------------------------

extension DeviceUi {

    /// Derive a KdStatus for the status dot from reachability + trustStatus.
    /// Returns nil when no dot should be shown (e.g. untrusted + unknown).
    var kdStatus: KdStatus? {
        switch onEnum(of: reachability) {
        case .reachable:
            return .ok
        case .unreachable:
            return .err
        case .probing, .unknown:
            // Show warn amber when we're still negotiating trust / pairing.
            switch onEnum(of: trustStatus) {
            case .pairing:
                return .warn
            default:
                return nil
            }
        }
    }
}
