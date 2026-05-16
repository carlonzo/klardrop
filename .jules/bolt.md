# Bolt's Journal
## 2024-05-16 - Prevented N+1 disk reads in device list rendering
**Learning:** Checking device trust status (`trustStorage.isTrusted()`) inside a flow combine map was causing N+1 disk reads on Desktop, as `DesktopTrustStorage` reads the properties file from disk for every check. This is triggered on every emission of `visibleDevices`, `unreadCounts`, or `reachabilitySource`.
**Action:** Pre-fetch all trusted devices once per emission using `trustStorage.getAllTrustedDevices()` to perform a single disk read instead of N disk reads. Next time, always avoid calling storage/disk operations inside loops mapping over flow emissions.
