# Bolt's Journal
## 2024-05-16 - Prevented N+1 disk reads in device list rendering
**Learning:** Checking device trust status (`trustStorage.isTrusted()`) inside a flow combine map was causing N+1 disk reads on Desktop, as `DesktopTrustStorage` reads the properties file from disk for every check. This is triggered on every emission of `visibleDevices`, `unreadCounts`, or `reachabilitySource`.
**Action:** Pre-fetch all trusted devices once per emission using `trustStorage.getAllTrustedDevices()` to perform a single disk read instead of N disk reads. Next time, always avoid calling storage/disk operations inside loops mapping over flow emissions.

## 2024-05-17 - Avoid Sequence Wrapper Allocations for Small Collections
**Learning:** For small collections (like a device's network interfaces), converting to a Sequence using `.asSequence()` can actually be slower than standard list operations because the overhead of sequence wrapping and iterator object allocations outweighs the savings from avoiding intermediate lists. A single pass using `.mapNotNull` provides the best of both worlds by avoiding intermediate list allocations and sequence wrapper overhead.
**Action:** Use a single `mapNotNull` instead of multiple `map` and `filter` chained operations or sequences when processing small collections to eliminate intermediate lists while maintaining performance.
