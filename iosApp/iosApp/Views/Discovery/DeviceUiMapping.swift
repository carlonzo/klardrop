import presentation

// ---------------------------------------------------------------------------
// DeviceUiMapping — Extends DeviceUi with presentation-layer mappers.
// Owned by the device-list cluster; placed in Views/Discovery/ per convention.
//
// Uses onEnum(of:) for sealed Kotlin types; DeviceType is a real Swift enum.
// ---------------------------------------------------------------------------

extension DeviceUi {

    // MARK: - KdDeviceKind

    var deviceKind: KdDeviceKind {
        switch deviceType {
        case .mobile:  return .iphone
        case .desktop: return .mac
        case .unknown: return .unknown
        default:       return .unknown
        }
    }

    // MARK: - KdRowState

    var rowState: KdRowState {
        switch onEnum(of: trustStatus) {
        case .pairing:
            return .pairing
        case .trusted:
            switch onEnum(of: reachability) {
            case .unreachable:
                return .unreachable
            default:
                switch onEnum(of: activityState) {
                case .sending:
                    return .active
                default:
                    return .idle
                }
            }
        default:
            return .pairPrompt
        }
    }

    // MARK: - Sub-text

    /// Caption shown under the device name. Returns nil when no text is needed
    /// (the status dot conveys reachability visually; writing "Online" is noise).
    var subText: String? {
        switch onEnum(of: trustStatus) {
        case .trusted:
            switch onEnum(of: activityState) {
            case .sending:
                return "Sending\u{2026}"
            case .sentCompleted(let c):
                return c.error ? "Failed" : nil
            case .idle:
                switch onEnum(of: reachability) {
                case .unreachable:
                    return "Offline"
                case .probing:
                    return "Connecting\u{2026}"
                default:
                    return nil
                }
            }
        case .pairing:
            return "Pairing\u{2026}"
        default:
            return nil
        }
    }

    // MARK: - Reachability status dot

    var reachabilityStatus: KdStatus? {
        switch onEnum(of: reachability) {
        case .reachable:
            return .ok
        case .unreachable:
            return .err
        default:
            return nil
        }
    }
}
