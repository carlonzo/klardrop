# AirDrop interop in Klardrop — feasibility study

Status: exploration. No production code proposed in this document.
Author: Claude (research pass, May 2026).
Branch: `claude/explore-airdrop-kmp-9zrTe`.

## TL;DR

Building an AirDrop-compatible transport inside Klardrop is **not feasible today
as a normal third-party Android app**. The application layer is well documented
and easy to reimplement in `commonMain`; the blocker is the link layer. AirDrop
still rides on AWDL, and AWDL requires firmware/driver privileges on the Wi-Fi
NIC that the Android SDK does not expose to user-installed apps. Google ships
AirDrop interop on the Pixel 10 family by reverse-engineering AWDL **inside
privileged system code**, not as something an app can call into.

Two paths could realistically unblock this in the next 6–12 months:

1. **EU DMA route (iOS side).** Apple is obligated by the EU Digital Markets
   Act to expose a peer-file-transfer surface that third parties can use as an
   AirDrop equivalent by **1 June 2026** — i.e. a few weeks from the date of
   this report. The shape of that API is not finalised in public docs. If it
   ships as expected, the realistic Klardrop play is: implement it on
   `iosMain` so that *Klardrop on iPhone* can present AirDrop-discovered Apple
   devices to other Klardrop peers, rather than re-implementing AirDrop on
   Android.

2. **Wi-Fi Aware route (Android side).** iOS 26 ships a Wi-Fi Aware framework
   for third-party apps. AirDrop itself does *not* use it on the wire today,
   but it gives us a standards-based, app-accessible transport for an
   "AirDrop-shaped" Klardrop link between iPhones and Android — provided both
   sides run Klardrop. As of this writing, iPhone↔Android Wi-Fi Aware
   discovery is unreliable in practice (per Apple's own developer forum), so
   this also is not turn-key.

Both of these are **Klardrop talking to Klardrop, dressed in standards Apple
endorses**. Neither delivers what the exploration plan asked for: an Android
device that can pop up in an unmodified iPhone's AirDrop sheet without any
software installed on the iPhone. The path that *does* deliver that — the
Quick Share route — requires shipping privileged code at the OS level, and is
out of scope for an app.

Recommendation: **do not start a Phase-4 spike yet.** Wait for the DMA-mandated
Apple API to land. In the meantime, the small piece that *is* worth doing now
is the application-protocol layer in `commonMain` (plist + cpio + the
`/Discover`/`/Ask`/`/Upload` state machine), because that part is portable, is
useful regardless of which link layer wins, and is the only piece of an
AirDrop client that survives both routes.

---

## 1. Correcting the exploration plan's premise

The plan opens with the framing:

> as of iOS 26, Apple was required by EU regulators to deprecate AWDL in favor
> of standardized Wi-Fi Aware (IEEE 802.11 NAN). This is also how Google's
> Pixel 10 Quick Share talks to AirDrop.

Both halves of this are partially correct but mislead the feasibility calculus.
The accurate picture, as of May 2026:

- **AirDrop on iOS 26 still uses AWDL on the wire.** It has not been
  re-platformed onto Wi-Fi Aware. iOS 26 *adds* a separate Wi-Fi Aware
  framework that third-party apps can use to build their own AirDrop-like
  experiences; that framework is parallel to AirDrop, not AirDrop's new
  transport. Wikipedia, the Apple Developer documentation for the new
  `WiFiAware` framework, and the Apple Developer Forums all describe these as
  two distinct things, and the WWDC 2025 session "Supercharge device
  connectivity with Wi-Fi Aware" is framed as enabling third parties, not as
  re-platforming first-party features.
- **Quick Share on Pixel 10 talks to AirDrop by reverse-engineering AWDL**, not
  by using Wi-Fi Aware. Google's security blog and multiple reporters
  describe an in-house implementation written in Rust that produces the
  AirDrop BLE wake-up advertisement and then runs the AirDrop HTTPS protocol
  over an AWDL-compatible link. The Android Authority and 9to5Google
  write-ups, and Google's own Online Security Blog post (November 2025),
  consistently describe it as a reverse-engineering effort done without
  Apple's cooperation. The fact that the feature is currently Pixel-10-only
  (with limited rollout to Pixel 9 starting Feb 2026) confirms that it
  depends on privileged platform code that Google controls on Pixel
  firmware, not on a public Android API.
- **The EU DMA does require Apple to open an AirDrop-equivalent surface to
  third parties.** The relevant Commission specification proceeding under the
  DMA targets close-range wireless file sharing and lists `1 June 2026` as
  the deadline for "deep system integration" for third-party file-transfer
  apps. The Heise summary and the Commission's own interoperability Q&A page
  describe this as a forced opening of the *AirDrop application surface* to
  third parties, not as a forced replacement of the AWDL link layer.

This distinction matters because the feasibility argument changes depending on
which assertion you accept. The original framing implies "Apple has done the
hard work; we just have to use Wi-Fi Aware." The accurate picture is "Apple is
in the middle of a multi-year transition; nothing in 2026 has obsoleted AWDL
for AirDrop itself."

---

## 2. The current AirDrop protocol stack

```
┌──────────────────────────────────────────────────────────────────┐
│ Application                                                      │
│   Binary plist over HTTPS                                        │
│     POST /Discover  → ReceiverComputerName, model, capabilities  │
│     POST /Ask       → file list, sender name, thumbnails         │
│     POST /Upload    → application/x-cpio (gzipped cpio archive)  │
│   TLS with self-signed cert (mutual auth only in Contacts Only)  │
│   Listens on a fixed AirDrop HTTPS port on IPv6 link-local       │
├──────────────────────────────────────────────────────────────────┤
│ Service publish/discover                                         │
│   mDNS over the AWDL interface                                   │
│     Service type: _airdrop._tcp.local.                           │
├──────────────────────────────────────────────────────────────────┤
│ Wake-up                                                          │
│   BLE manufacturer-data advertisement                            │
│     Apple company ID 0x004C, subtype 0x05 (AirDrop),             │
│     8 bytes of hashed contact identifiers                        │
│   Causes the receiving iPhone to bring up its AWDL interface     │
│   and start its AirDrop HTTPS listener for ~10 minutes.          │
├──────────────────────────────────────────────────────────────────┤
│ Link layer                                                       │
│   AWDL — Apple Wireless Direct Link                              │
│     - 802.11 in IBSS-like ad-hoc mode                            │
│     - Channel hopping between 2.4 GHz and 5 GHz channels         │
│     - Negotiated via action/data frames over the regular Wi-Fi   │
│       chipset, but in monitor + inject mode                      │
│     - Carries link-local IPv6 traffic between peers              │
└──────────────────────────────────────────────────────────────────┘
```

The application and wake-up layers are well documented from the SEEMOO
(TU Darmstadt) work — most concretely OpenDrop's Python source — and the
USENIX '19 paper "A Billion Open Interfaces for Eve and Mallory."

What is *not* publicly documented to the same depth is:

- Whether the BLE advertisement format has any iOS 26 changes.
- The HTTPS server port (OpenDrop and Quick Share clones use it, so it is at
  least stable enough to discover; we did not extract it cleanly during this
  pass — see open questions).
- The full set of plist keys the iPhone tolerates on `/Ask` vs the small set
  OpenDrop sends.

---

## 3. The application protocol — the part that's actually portable

This section is the part of the exploration plan that yields a concrete
Klardrop-shaped deliverable in any feasibility branch.

### 3.1 HTTPS endpoints

All requests are `POST`. All bodies are binary property lists
(`plistlib.FMT_BINARY` in OpenDrop). Headers used by OpenDrop's client:

```
Content-Type: application/octet-stream
User-Agent: AirDrop/1.0
Accept-Encoding: br, gzip, deflate
```

`/Upload` is the one exception: it sends `Content-Type: application/x-cpio` and
uses `Transfer-Encoding: chunked`.

| Endpoint    | Direction       | Purpose                                    |
|-------------|-----------------|--------------------------------------------|
| `/Discover` | sender→receiver | Negotiate capability; receiver returns its name and model. |
| `/Ask`      | sender→receiver | Submit sender identity + file metadata; receiver shows accept/decline UI. |
| `/Upload`   | sender→receiver | Stream a gzipped cpio archive of the actual file content. |

OpenDrop's flow: `send_discover()` → `send_ask(file_path)` → `send_upload(file_path)`, sequentially per file (or per batch of files in `Items`).

### 3.2 Plist payload keys

From OpenDrop's `server.py` and `client.py`:

Discover response (receiver → sender):
- `ReceiverMediaCapabilities` (binary blob)
- `ReceiverComputerName` (string)
- `ReceiverModelName` (string)

Ask request (sender → receiver):
- `SenderComputerName`
- `SenderModelName`
- `SenderID`
- `BundleID`
- `Files` *or* `Items` — array of per-file dictionaries with name, type,
  thumbnail, etc. The exact key set used per item is not fully captured here.

Ask response (receiver → sender):
- `ReceiverComputerName`
- `ReceiverModelName`

### 3.3 Archive format on `/Upload`

`application/x-cpio`, gzip-encoded at the HTTP layer (chunked). Contents are
the files as they should appear after extraction, with cpio metadata
(filename, mode). OpenDrop uses `libarchive` to build/parse.

### 3.4 Thumbnails

JPEG2000 (`.jp2`), typically 540×540, attached to each item in `/Ask`. The
iPhone uses these to render the previews on the accept/decline sheet. JPEG2000
is the **biggest practical thorn in `commonMain`**: there is no widely-used
pure-Kotlin or pure-JVM-friendly JPEG2000 encoder. Likely answers:

- Skip thumbnails. The iPhone *should* still show the accept sheet with no
  preview, just text. **Verify on a real device** — open question.
- Use `expect/actual` and let each platform encode JPEG2000 with its native
  toolchain (CoreImage on iOS/macOS, the Android `BitmapFactory`/codec stack,
  `jai-imageio-jpeg2000` on JVM desktop). This trades portability for
  pragmatism but keeps the protocol state machine common.

### 3.5 TLS

OpenDrop wraps the listening socket with a TLS context, self-signed. In
"Everyone for 10 Minutes" mode the iPhone does not validate the sender's
certificate against contacts (that is the whole point of the mode), so a
self-signed leaf with arbitrary identity is accepted. **Confirm cipher suite
constraints** — modern iOS may have tightened them. Open question.

### 3.6 Implications for Klardrop

This entire section is platform-agnostic and would live in
`common/src/commonMain/`. It would compose as:

- `airdrop/plist/` — binary plist read/write (Kotlin port; OkIO-friendly).
- `airdrop/archive/` — cpio writer (small; OkIO-friendly).
- `airdrop/AirDropProtocol.kt` — the state machine (`Idle → Discovering →
  Asking → Uploading → Done | Cancelled | Error`).
- `airdrop/AirDropClient.kt` — uses an `expect`'d HTTPS client to drive the
  three POSTs.
- `airdrop/AirDropServer.kt` — the symmetric receiver, since on iOS/macOS we
  may want Klardrop to *also* expose itself as an AirDrop peer (see §6).

Estimated effort, ignoring the link layer entirely: roughly 1–2 weeks of
focused work for a complete and tested implementation of these endpoints,
plus the plist + cpio building blocks. The state machine fits comfortably
alongside the existing `communication/` and `discovery/` modules.

---

## 4. The link-layer blocker — why Android can't drive AirDrop today

This is where the feasibility argument breaks. The application protocol is
useless without a transport, and AirDrop's transport is AWDL.

### 4.1 What AWDL requires

AWDL is implemented in user space (OWL, the SEEMOO reference) by:

1. Putting the Wi-Fi NIC into **802.11 monitor mode**, so the host can see
   raw 802.11 frames, not just IP packets.
2. Using **frame injection**, so the host can transmit raw 802.11 action
   frames negotiating the AWDL channel-hopping schedule.
3. Channel-hopping in software between the 2.4 GHz and 5 GHz bands at
   AWDL's sub-100 ms cadence.
4. Bringing up a virtual IPv6 interface that carries the data frames.

On Linux PCs this requires a Broadcom or Atheros chipset with patched
firmware. On macOS this is what the kernel's `IO80211Family` does
internally. OWL works on a PC because Linux + a USB Wi-Fi adapter exposes
all of this.

### 4.2 Why Android phones can't do this

Standard Android Wi-Fi APIs do not expose monitor mode or frame injection
to apps. There is no permission an app can declare to obtain them. The
Wi-Fi HAL ships in vendor firmware, and the few research projects that have
gotten 802.11 monitor mode on a phone (`liber80211`, the QCACLD-based
`qualcomm_android_monitor_mode`) require **rooted devices with specific
chipsets and patched firmware/drivers**. None of this is something you can
ship to users via Play Store.

Even if you accept rooted-Pixel-only as a distribution model, AWDL needs
not just monitor mode but matching channel sequencing, action-frame
parsing, and IPv6 stack integration — what `seemoo-lab/owl` does on Linux
with kernel patches. Port of OWL to Android is mentioned in
`xingrz/android_external_owl` but is dormant and was never demonstrated to
talk to a real Apple peer.

### 4.3 How Google did it on the Pixel 10

Google did not solve the above on a per-app basis. The Pixel 10 AirDrop
support is **system-level code, written in Rust, shipped as part of the
device's privileged Quick Share service**. It runs with capabilities a
normal app does not have. This is also why:

- The feature is exclusive to Pixel 10 at launch.
- Expansion to Pixel 9 (Feb 2026) required a separate firmware/driver
  update, not just an APK.
- The XDA community thread "Enabling AirDrop support on Pixel 10 Quick
  Share" is about unlocking the existing Pixel-only system service, not
  about a portable implementation.

There is no Android API surface a Klardrop user-space app can call to do
the same. There is no plausible path to one in the next 12 months.

### 4.4 Implication

**An Android-side AirDrop client that talks to a stock iPhone is not buildable
inside Klardrop as a regular app.** This is the single most important
finding in this report.

The application-layer work in §3 is still worth doing because:

- It is the iOS-side implementation of a Klardrop AirDrop *receiver*, and
- it is the protocol Klardrop would speak over the EU DMA surface once that
  ships, and
- it has no downside if we change our minds about the link layer.

But the **Phase 4 spike in the exploration plan (Android discovers an
iPhone) cannot succeed on hardware we ship to users.** It can succeed on a
Linux laptop running OWL, which is a different project.

---

## 5. The Wi-Fi Aware path — why it's not the easy fix

The exploration plan treats Wi-Fi Aware as the obvious replacement. Let's
look at it honestly.

### 5.1 What iOS 26 actually exposes

iOS 26 ships a public `WiFiAware` framework that lets a third-party app:

- Publish a service with a name and capability descriptor.
- Subscribe to discover services published by peers.
- Open a Wi-Fi Aware data path to a discovered peer and use it as a
  `Network` (in iOS terms, a `NWConnection`).

The accessory design guidelines require Wi-Fi Aware 4.0; iOS 26 will adopt
Wi-Fi Aware 5.0 within nine months of the 5.0 spec's publication.

### 5.2 What AirDrop publishes there

**Nothing, currently.** AirDrop is not exposed as a public Wi-Fi Aware
service in iOS 26. The Wi-Fi Aware framework is for apps to build their
own peer-to-peer features. The fact that two iPhones can run an iOS 26 Wi-Fi
Aware demo app and discover each other tells us the API works in-platform;
it does not mean any other iPhone process publishes anything Klardrop could
subscribe to.

### 5.3 iPhone ↔ Android Wi-Fi Aware in practice

The Apple Developer Forum thread "Wi-Fi Aware between iOS 26 and Android"
(thread 790195) is consistent in reporting that:

- Two iPhones running iOS 26 discover each other reliably.
- An Android device (Pixel 9, Galaxy S23 Ultra, Xiaomi 14, Galaxy S25)
  running its own NAN publish/subscribe **does not discover the iPhone's
  service**, and the iPhone does not discover Android's.
- Apple's DTS engineer responded that this is "a rapidly evolving standard"
  and recommends working with hardware/platform vendors.

Whether this is firmware-version skew, encryption-suite mismatch, or
cluster-formation incompatibility is unresolved as of the public record we
read. It is not "Wi-Fi Aware works between iPhone and Android in 2026."

### 5.4 Where this leaves Klardrop on Wi-Fi Aware

If we build a Wi-Fi Aware transport into Klardrop, the realistic value in
2026 is **Android↔Android** with possible future iOS support. That has a
place in the project — it is the only LAN-less local-network transport
that works without an access point — but it does not let an iPhone running
stock iOS share with a Klardrop Android device. It only helps if the iPhone
also runs Klardrop.

---

## 6. The EU DMA path — the only realistic route to *stock-iPhone* interop

The DMA proceeding obliges Apple to expose, by **1 June 2026**, an API such
that a third-party app on iOS can offer AirDrop-equivalent functionality.
The public summary in heise/EU Commission docs describes this as covering
"close-range wireless file sharing." There is also analogous obligation
work for AirPlay-equivalent features.

We do not yet know the exact API shape: whether it is a system framework
that an app can call to *initiate* AirDrop transfers (most likely), whether
it lets a third-party app *appear in* the iPhone's AirDrop sheet (less
likely but plausible), or whether it is a lower-level peer discovery
primitive the app composes itself.

What we can assume:

- The API will be **iOS-side**. The DMA forces Apple, not Android.
- It will likely be **EU-geofenced** initially, like the iOS 26.3 proximity
  pairing changes.
- It will be a new framework, not part of `WiFiAware`.

The right Klardrop bet, then, is:

1. Implement the AirDrop application protocol in `commonMain` as in §3.
2. On iOS, plumb the DMA API into Klardrop so that Klardrop on an iPhone
   can use the system to discover and transfer with AirDrop peers, and
   re-publish those as Klardrop devices to other Klardrop nodes via the
   existing transports.
3. **Do not** attempt to drive AWDL from Android.

This collapses the original ambition ("every device finds AirDrop
devices") down to ("iPhones running Klardrop can act as a gateway between
AirDrop and Klardrop's other transports"), but it is the only version of
the ambition that is achievable in 2026.

---

## 7. Proposed KMP architecture (conditional)

Conditional on §6 — i.e. **only if** we decide to build the iOS-gateway
version. Sketched here so the engineering shape is not an unknown.

### 7.1 Module placement

The existing layout under `common/src/` already has the shape we want:

```
common/
  src/
    commonMain/
      .../discovery/        ← KlardropDiscoveryUtils, NearbyShareDiscoveryUtils
                              + new AirDropDiscoveryUtils
      .../communication/    ← Server, Client, UnifiedServer
                              + new AirDropProtocol, AirDropClient
      .../airdrop/          ← (new) plist + cpio + state machine
    iosMain/
      .../airdrop/          ← actuals using the DMA framework
                              + native JPEG2000 (CoreImage)
    androidMain/
      .../airdrop/          ← Wi-Fi Aware actual for the (limited)
                              Android-side Wi-Fi Aware transport
                              No AWDL. No AirDrop interop directly.
    desktopJvmMain/
      .../airdrop/          ← out of scope; document as such
```

We already have one stub call to investigate: `DiscoveryNetwork.discoverAirdrop()`
at `common/src/commonMain/kotlin/com/carlom/klardrop/common/discovery/DiscoveryNetwork.kt:267-279`
tries an mDNS lookup for `_airdrop._tcp.local.`. This will not find AirDrop
peers over regular Wi-Fi because AirDrop publishes that mDNS service *over
the AWDL interface*, not over the home Wi-Fi network. The stub should
either be removed or rewritten to call the DMA API once it exists.

### 7.2 Discovery integration

A new `DeviceConnection.AirDropConnection` slots alongside the existing
`KlardropConnection` / `NearbyConnection` / `BleConnection` types. The
`VisibleDevices` aggregator already handles a device exposing multiple
connection types; AirDrop becomes a fourth.

On iOS, the DMA framework's discovery callbacks feed `VisibleDevices`
exactly the way the mDNS callbacks do today. On Android, we **do not**
populate `AirDropConnection` from anywhere. The `discoverAirdrop()` method
becomes a no-op on Android with a comment explaining why.

### 7.3 Transfer integration

The existing `Server` and `Client` already wrap a multiplexed bidirectional
file transfer over their respective protocols. AirDrop is unidirectional
per session (sender → receiver, one accept/decline gate, one upload).
Modelling it as a `TransferStrategy` parallel to the Klardrop and Nearby
strategies fits cleanly; the `ConnectionsPool` will need an
`AirDropConnection` branch.

### 7.4 Public surface

```kotlin
// commonMain
interface AirDropTransport {
    suspend fun isAvailable(): Boolean        // false on Android, true on iOS only when DMA API present
    fun discover(): Flow<AirDropDiscoveryEvent>
    suspend fun send(peer: AirDropPeer, files: List<TransferFile>): Result<Unit>
}

// expect
internal expect fun defaultAirDropTransport(): AirDropTransport
```

The signatures intentionally mirror `BleTransport` so the DI module changes
are minimal: `InternalPlatformDependencies` gets one more provider.

### 7.5 What `commonMain` does and does not do

Does:
- Plist serialisation.
- Cpio archive building.
- State machine for the application protocol.
- All the protocol unit tests, mocking the network.

Does not:
- Open sockets. The actual network and HTTPS client are in the actuals.
- Encode thumbnails. JPEG2000 is platform-specific.
- Do BLE wake-up. The DMA API on iOS handles wake-up internally; on
  Android we are not doing AirDrop anyway.

---

## 8. Risks and constraints

### 8.1 Legal — DMA / anti-circumvention

We are not circumventing any technical access control. "Everyone for 10
Minutes" is, by design, an open receive mode. EU law (Software Directive
Art. 6 and DMA itself) explicitly protects reverse engineering for
interoperability, and the DMA goes further by *compelling* Apple to expose
the relevant surface. US §1201(f) is the analogous safe harbour for
interoperability work.

If we choose path §6 (use Apple's DMA API), there is essentially no
anti-circumvention exposure — we are using Apple's documented API as
intended.

If we choose path §4 (Android-side AWDL reverse engineering, even on rooted
phones), we are walking the same path Google walked but without Google's
privileged-system-code position. The legal exposure is low (interop
exception applies) but the practical exposure to **breakage** is high: any
iOS minor update could change wire details, and any AirDrop point release
could rotate the BLE format. Quick Share already had to ship updates after
iOS 26.2 changed AirDrop behaviour (per 9to5Mac, Jan 2026).

### 8.2 Trademark

"AirDrop" is Apple's. The library and any user surface must avoid:

- Claiming endorsement ("AirDrop-certified" — no).
- Using the AirDrop icon or wordmark in the UI.
- Naming the library `klardrop-airdrop` in a way that implies origin.

Acceptable: "share with Apple devices", "compatible with AirDrop's Everyone
mode", in body text describing the feature, with no use of the wordmark in
product names or icons. Cross-check with EUIPO/USPTO before public
release.

### 8.3 Privacy

The local-transfer model has no data exfiltration risk on our side. Worth
calling out explicitly in the README of any spike or library: no telemetry,
no contact-book access, no network egress beyond the transfer itself. This
also keeps us clean of the privacy issues SEEMOO documented in the BLE
wake-up advertisement (hashed contact identifiers).

### 8.4 Stability

The application protocol is stable across several iOS major versions
(OpenDrop has been broadly compatible since iOS 11). The BLE advertisement
and the AWDL channel logic are less stable. If we depend only on the
application layer (via Apple's DMA API), we inherit Apple's stability
contract on that API.

### 8.5 What Apple might do

Apple has not, as of this report, taken technical countermeasures against
Quick Share. The Pixel 10 interop continues to function. There is some
non-zero chance Apple adds receiver-side fingerprinting that blocks
non-Apple senders, but that would directly contradict the DMA obligation
and is therefore unlikely in the EU.

---

## 9. Recommendation

**Do not start implementation work on AirDrop interop now.** The Phase 4 and
Phase 5 spikes in the exploration plan are blocked on hardware capabilities
Android does not expose and is unlikely to expose.

**Do, in the next sprint:**

1. Remove or correct the misleading `DiscoveryNetwork.discoverAirdrop()`
   mDNS stub at `common/src/commonMain/kotlin/com/carlom/klardrop/common/discovery/DiscoveryNetwork.kt:267-279`.
   It will never fire over normal Wi-Fi, and leaving it suggests AirDrop
   discovery already half-works.
2. Watch for Apple's DMA-mandated AirDrop interop API in iOS 26.3/26.4
   point releases or at WWDC 2026 (mid-June). Re-open this exploration the
   moment that API documentation lands.

**Do, opportunistically:**

3. Start the `commonMain` application-protocol implementation described in
   §3 as a small, isolated module with thorough unit tests against
   captured plist/cpio fixtures from OpenDrop. This is useful regardless
   of which link layer wins, and is portable to a future spike that uses
   a Linux laptop + OWL as a test rig.

**Do not, even opportunistically:**

4. Do not attempt to reverse-engineer AWDL on Android. Even if we got it
   running on a rooted Pixel, it would not be shippable.
5. Do not invest more than a few hours in the iOS-side Wi-Fi Aware
   framework today as an AirDrop path. It is not AirDrop's transport.
   It is worth a separate, parallel exploration for an Android↔iPhone
   Klardrop-over-NAN transport, but that is a different feature.

---

## 10. Open questions (running list)

Append to this list as exploration continues; do not silently resolve.

- **What does the EU DMA-mandated Apple file-sharing API actually look
  like?** Required to confirm the §6 plan is buildable. Should be answered
  by the iOS 26.3/26.4 documentation or WWDC 2026.
- **Will the DMA API be EU-geofenced?** If yes, Klardrop's
  Apple-interop feature is automatically a regional one.
- **What is the AirDrop HTTPS server port in current iOS?** OpenDrop
  discovers it via the AWDL-mDNS service record, but the fixed value would
  be useful to document.
- **Does the iPhone show an accept sheet for an `/Ask` with no thumbnail in
  iOS 26?** If yes, we can ship a useful client without JPEG2000.
- **Has the BLE wake-up advertisement format changed in iOS 26?** SEEMOO
  has not (yet) published a follow-up paper.
- **Does the new `WiFiAware` framework on iOS 26 let a publisher declare
  the AirDrop service name, or only an app-private name?** Determines
  whether a Klardrop-Klardrop NAN link is the only Wi-Fi Aware option.
- **Is there a pure-Kotlin or JVM-only JPEG2000 encoder we'd be happy to
  depend on for `desktopJvmMain`?** `jai-imageio-jpeg2000` exists but is
  legacy and patent-encumbered for some profiles.
- **Can Android's `WifiAwareManager` join a NAN cluster published by an
  iPhone?** Per the Apple dev forum, empirically not yet — but worth
  re-checking quarterly as both stacks mature.
- **What happens on iOS 26.x point releases when AirDrop's wire format
  shifts?** Quick Share had to ship a follow-up — we'd want a strategy
  before, not after.

---

## Reference material consulted

The web research for this report drew on, among other sources:

- Google Online Security Blog, "Android Quick Share Support for AirDrop: A
  Secure Approach to Cross-Platform File Sharing", November 2025.
- Google Blog (blog.google), "Android and iPhone users can now share files,
  starting with the Pixel 10 family", November 2025.
- 9to5Google, "Android Quick Share now works with AirDrop on iPhone,
  starting on Pixel 10", 2025-11-20.
- 9to5Mac, "iOS 26.2 added new AirDrop upgrade, here's how it works",
  2026-01-14.
- MacRumors, "Android-to-iPhone AirDrop Transfers Now Supported on Pixel
  9", 2026-02-17.
- MacRumors, "iOS 26.3 Brings AirPods-Like Pairing to Third-Party Devices
  in EU Under DMA", 2025-12-22.
- heise online, "EU deadline approaching: How iPhones must become more
  compatible".
- Apple Developer Documentation, `WiFiAware` framework (iOS 26).
- Apple Developer Forums, thread 790195, "Wi-Fi Aware between iOS 26 and
  Android".
- WWDC25 session 228, "Supercharge device connectivity with Wi-Fi Aware".
- seemoo-lab/opendrop — `client.py`, `server.py` (Python AirDrop
  implementation; reference for §3).
- seemoo-lab/owl — AWDL implementation (reference for §4).
- Stute et al., "A Billion Open Interfaces for Eve and Mallory" (USENIX
  Security '19).
- Stute et al., "One Billion Apples' Secret Sauce" (MobiCom '18).
- Android Developers, `WifiAwareManager` and Wi-Fi Aware overview.
- AOSP, Wi-Fi Aware page.
- Wikipedia, AirDrop article.
