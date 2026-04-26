# MQTT cloud transfer — rollout progress

This is the **living tracker** for the MQTT cloud-transfer feature. Update it
each session: move items between `[done]` / `[wip]` / `[todo]` / `[blocked]` /
`[?]`, append a one-line note when something completes, and keep the
file:line references current.

Sister docs (don't duplicate, link):
- `docs/mqtt-cloud-architecture.md` — the original design.
- `docs/mqtt-auth-trust-production-plan.md` — the product decisions (Auth0
  magic-link v1, JWT-broker-auth-then-mTLS, ≤30s revocation SLA, no offline
  queue).
- `docs/mqtt-production-readiness-review.md` — gap analysis driving the
  staged plan.
- `docs/adr/0001-self-hosted-mqtt-cloud-stack.md` — architecture decision
  for Mosquitto + mosquitto-go-auth + Keycloak + shared-secret broker webhook.

---

## At a glance

| Milestone | Owner area | Status | Notes |
|---|---|---|---|
| **M0** Lock contracts | docs | done | topic tree, JWT claims, ACL matrix all pinned in code + OpenAPI. |
| **M1** Harden device-registry | `cloud-backend/device-registry` | done | session/broker key split, audience-binding, mosquitto-go-auth webhook, audit, prod fail-fast. |
| **M2** Stand up the broker | ops + compose | partial | self-host compose ready (Mosquitto + go-auth); TLS certs + prod k8s manifest pending. |
| **M3** Client `common/` MQTT | `common/` KMP | partial | trust + sig + replay primitives, Ed25519 actuals (JVM/Android), SecureKeyStore (file-backed JVM), HTTP DeviceRegistryClient, MqttCredentialsStore + Refresher all done. **Platform MQTT client (Paho/HiveMQ) + DI wiring still pending.** |
| **M4** Trust + auto-accept | `common/` + `device-registry` | partial | receiver gate + trust-event publisher (server-side) done. **MessageReceiver branch still pending — needs M3's MQTT client first.** |
| **M5** Smart routing | `common/` | todo | needs M3 platform client first. |
| **M6** Hardening + launch | cross-cutting | todo | feature flag, kill switch, conformance tests, metrics, alerts. |

Source-of-truth commits on `claude/review-mqtt-server-UPVot`:
- `50e9a3b` self-host stack + EMQX webhook + OIDC + hardening (superseded by `da299e7`)
- `10790db` common/mqtt trust + auto-accept primitives
- `da299e7` broker switch — EMQX → Mosquitto + mosquitto-go-auth
- `<ed25519>` Ed25519 EnvelopeSigner / Verifier actuals (JVM/Android)
- `<keystore>` SecureKeyStore — file-backed JVM impl + InMemory for tests
- `571205d`  ktor-client DeviceRegistryClientHttp + MockEngine tests
- `6a6e204`  TrustEventPublisher in device-registry (Paho)
- `08539d3`  MqttCredentialsStore (DataStore) + Refresher decision logic

---

## Done (with anchors)

### Server — `cloud-backend/device-registry`

- **Generic OIDC verifier** (`OidcConfig`, `OidcIdentityProviderVerifier`).
  - Files: `services/IdentityProviderVerifier.kt`, `config/AppConfig.kt`.
  - Works with Keycloak (`OIDC_ISSUER=https://idp/realms/klardrop`),
    Authentik, Auth0 (`AUTH0_DOMAIN` shorthand kept), Ory Hydra.
- **Audience-bound session JWT verifier** —
  `plugins/Security.kt` now calls `withAudience(jwtConfig.audience)`.
- **Separate signing keys for session vs broker JWTs**, broker TTL 15min,
  `jti` claim on both — `security/TokenService.kt`.
- **`/v1/internal/broker/auth/{user,acl,superuser}`** webhooks called by
  mosquitto-go-auth on every CONNECT and (cached) every PUBLISH/SUBSCRIBE.
  Validates JWT, clientId, revoked-state, and per-user-scoped topic ACL —
  `services/BrokerAuthService.kt`, `routes/DeviceRoutes.kt:internalRoutes`.
- **`/v1/devices/me/broker-token`** refresh endpoint — `routes/DeviceRoutes.kt`.
- **`MosquittoBrokerSessionManager`** (Redis revoked-set + cached-auth-TTL
  enforcement) — `services/BrokerSessionManager.kt`. Mosquitto has no live
  REST kick API; revocations propagate via the broker's auth cache TTL
  (20s) which checks the Redis set on every reauth.
- **`audit_events` table + `AuditLogger`** (Logging + DB + Composite) —
  `services/AuditLogger.kt`, `database/tables/AuditEventsTable.kt`.
- **`RedisService.consumeNonce(...)`** for replay protection on signed envelopes.
- **`enforceProductionInvariants(...)`** — fail-fast in `APP_ENV=production`
  on dev defaults / missing OIDC / missing EMQX / weak broker TTL.
- **OpenAPI v0.2.0** documents the three mosquitto-go-auth webhook endpoints
  and `/v1/devices/me/broker-token` — `contracts/openapi-stage1-auth-enrollment.yaml`.
- **k8s deployment manifest** for the device-registry —
  `cloud-backend/k8s/deployment-device-registry.yaml`.

### Self-host stack — `cloud-backend/docker/`

- **`docker-compose.selfhost.yml`** — Postgres + Redis + Keycloak +
  Mosquitto-with-go-auth + device-registry on a single machine, with the
  broker HTTP authn/authz pre-wired to the registry's internal endpoint.
- **`docker/mosquitto/mosquitto.conf`** — broker config with
  `auth_opt_acl_cache_seconds = 20s` and the three webhook URIs.
- **`keycloak/realm-export/klardrop-realm.json`** — pre-seeded `klardrop`
  realm with the `klardrop-device-registry` audience client and a public
  PKCE `klardrop-app` client.
- **`.env.selfhost.example`** documents every secret.

### Client — `common/src/commonMain/kotlin/com/carlom/klardrop/common/mqtt/`

- **`SignedEnvelope` + `SignedEnvelopeCanonical`** — protobuf wire envelope
  and byte-pinned canonical bytes layout for signature input.
- **`MqttPayload`** — sealed class of every body type (TransferRequest /
  Response / FileChunk / Progress / Control / Complete + Presence + TrustEvent).
- **`EnvelopeSigner`/`EnvelopeVerifier`/`Clock`/`NonceProvider`** SPIs.
- **`Ed25519` actuals** — `generateEd25519KeyPair`, `ed25519Signer(seed)`,
  `ed25519Verifier()` via `java.security` Ed25519 on JVM/Android (Java 15+ /
  Android API 33+). RFC 8032 raw 32-byte encoding for both seed and public
  key. iOS stubbed (CryptoKit impl tracked under M3-iOS).
- **`ReplayProtector`** — TTL+cap LRU keyed by `(senderDeviceId, nonce)`.
- **`TrustedDeviceCache`** — interface + `InMemoryTrustedDeviceCache`.
- **`SecureKeyStore`** — `interface { loadOrGenerate(); clear() }` +
  `InMemorySecureKeyStore` for tests + `FileSystemSecureKeyStore` for
  desktop JVM (atomic 64-byte write, POSIX 0600). Android Keystore-backed
  impl deferred until DI lands.
- **`DeviceRegistryClient`** — interface + `InMemoryDeviceRegistryClient` +
  **`DeviceRegistryClientHttp`** (ktor-client multiplatform; OkHttp on
  JVM/Android, Darwin on iOS). `refreshCredentials(userId, deviceId)`
  assembles a fully-formed `MqttCredentials` from the server's bare-bones
  broker-token response. `DeviceRegistryException` for non-2xx.
- **`MqttCredentials` / `MqttTopics` / `MqttConnectionState`** — single source
  of truth for client-side MQTT routing.
- **`MqttCredentialsStore`** — interface + `InMemoryMqttCredentialsStore` +
  `DataStoreMqttCredentialsStore` (protobuf-encoded under one preferences
  key). Plus **`MqttCredentialsRefresher`** — `loadOrRefresh(userId,
  deviceId)` returns cached or hits the registry; `decideFor(...)` is the
  pure decision logic (UseCached / Refresh / NoCachedNorRefresh) driven by
  TTL window + user/device match.
- **`MqttIncomingMessageHandler`** — the auto-accept invariant. Returns
  `Outcome.Deliver` only when sender ∈ trust set ∧ signature valid ∧ fresh ∧
  receiver matches. `Outcome.Drop` is total and never throws.
- **`MqttOutgoingMessageEncoder`** — sender-side complement using the same
  canonical layout.

### Trust events — server side

- **`TrustEventPublisher`** in `device-registry/services/`:
    * `interface { publishEnrolled(userId, device); publishRevoked(userId, deviceId); close() }`.
    * `LoggingTrustEventPublisher` — wired by default in dev; logs the event
      so enrollment/revocation are observable without a running broker.
    * `PahoTrustEventPublisher` — Eclipse Paho v3 client, lazy connect with
      auto-reconnect, QoS 1, no retain. Wired in production when
      `BrokerServiceConfig.isConfigured` (env: `MQTT_SERVICE_USERNAME` +
      `MQTT_SERVICE_PASSWORD`).
    * Hooked in `DeviceService.enrollDevice` and `revokeDevice` — failures
      logged but never propagated to the transaction.

### Tests

- `:device-registry:test` — **24 green**:
    * 12 BrokerAuthServiceTest — JWT auth + ACL matrix.
    * 7 DeviceServiceTest.
    * 2 RedisServiceNonceTest.
    * 3 TrustEventPublisherTest.
- `:klardrop-common:desktopJvmTest` (mqtt subset) — **37 green**:
    * 9 MqttIncomingMessageHandlerTest.
    * 4 ReplayProtectorTest.
    * 4 TrustedDeviceCacheTest.
    * 2 SignedEnvelopeCanonicalTest.
    * 5 Ed25519Test (round-trip, tamper, key mismatch, garbage, full
      envelope round-trip via encoder + handler).
    * 2 InMemorySecureKeyStoreTest.
    * 3 FileSystemSecureKeyStoreTest.
    * 6 DeviceRegistryClientHttpTest (MockEngine).
    * 2 InMemoryMqttCredentialsStoreTest.
    * 5 MqttCredentialsRefresherDecisionTest.

Total: **61 tests across both modules, all green.**

---

## In progress / next up

Listed in the order to tackle. Anything that needs a running broker is
flagged — that's the boundary where this branch's scope ends until you can
boot the self-host stack.

1. `[todo]` **`MqttModule` (DI wiring)** — extend `CommonComponent` to expose:
   - `secureKeyStore()` (FileSystemSecureKeyStore on desktop / Android),
   - `mqttCredentialsStore()` (DataStoreMqttCredentialsStore),
   - `deviceRegistryClient()` (DeviceRegistryClientHttp with platform engine),
   - `mqttCredentialsRefresher()`, `trustedDeviceCache()`,
     `mqttIncomingMessageHandler()`, `mqttOutgoingMessageEncoder()`.
   Add `enableMqttCloud: Boolean` to `ApplicationInfo`. **No broker needed.**

2. `[todo]` **Platform `MqttConnectionManager` actual** — `expect class`
   in commonMain; `desktopJvmMain` + `androidMain` use Eclipse Paho v3
   (already a backend-side dep, easy to add to client) or HiveMQ v5 client.
   - Auto-reconnect with exp backoff, JWT refresh at `expiresAtEpochMs - 60s`
     (uses `MqttCredentialsRefresher`).
   - Retained presence message + Last Will & Testament.
   - iOS: stub throwing `NotImplementedError("M3-iOS")`.
   **Needs a broker to verify end-to-end.**

3. `[todo]` **`MqttDiscoveryService`** — subscribes to
   `klardrop/v1/users/{userId}/presence/+`, transforms presence events into
   `DiscoveryDevice` with `DeviceConnectionType.MQTT`, feeds into the
   existing `VisibleDevices`. **Needs broker.**

4. `[todo]` **Trust event subscriber on the client** — subscribes to
   `klardrop/v1/users/{userId}/trust/events`, decodes
   `TrustEventPublisher`'s JSON shape, calls
   `TrustedDeviceCache.upsert/remove`. **Needs broker.**

5. `[todo]` **MessageReceiver branch for MQTT inbound** — when
   `MqttIncomingMessageHandler.Outcome.Deliver` arrives, push directly to
   `ReceiveMessageStatus.Progress` (skip `PendingAuthorization`). Mirror of
   `NearbyReceiverConnectionHandler.kt:103-111` minus the prompt.
   **Wireup-only; integration test needs broker.**

6. `[todo]` **`SmartTransferManager`** — picks LOCAL_TCP if reachable in
   `VisibleDevices`, else MQTT. Single sender entrypoint for the UI.

7. `[todo]` **Android Keystore-backed `SecureKeyStore`** — replace
   `FileSystemSecureKeyStore` on Android with an AES-GCM-encrypted blob
   under a Keystore-protected key. Wire from DI.

8. `[todo]` **iOS actuals** — `Ed25519.ios.kt` via CryptoKit Curve25519,
   `SecureKeyStore` via Keychain. Tracked under M3-iOS.

9. `[todo]` **Conformance tests against live Mosquitto** (testcontainers
   running `iegomez/mosquitto-go-auth`).
   - Cross-user isolation (broker rejects cross-user subscribe).
   - Revoke → disconnect within 30s.
   - Replayed signed envelope rejected by client.
   - Local-then-cloud preference.

---

## Backlog (M6 + nice-to-haves)

- **`cloud_mqtt_enabled` feature flag** — stored server-side, exposed to client
  on session exchange. Default false; cohort rollout 10% → 50% → 100%.
- **Server-side kill switch** — endpoint that flips the flag globally and
  disconnects active MQTT sessions.
- **Metrics** — Prometheus counters: `mqtt_auth_failures_total`,
  `mqtt_revoke_to_disconnect_ms`, `mqtt_signature_failures_total`,
  `mqtt_active_sessions`, `mqtt_publish_failures_total`. Micrometer is already
  on the classpath.
- **Audit retention policy** — TTL + per-tenant export. Currently rows live
  forever in `audit_events`.
- **Rate limiting** on `/v1/auth/*` and `/v1/devices/pairing-codes`. Ktor's
  rate-limit plugin or a Redis token-bucket.
- **mTLS phase-2** for broker authn — replace the JWT-only model. Plan doc
  calls this out as a Stage-5 hardening.
- **iOS MQTT client** — CocoaMQTT or NIO-based.
- **Signed trust events from server** — server signs `TrustEvent` with a
  separate trust-events key; clients verify. Avoids a peer being able to
  forge revocations of itself.
- **End-to-end payload encryption** — design doc has it; not in v1 scope.
- **Offline queue** — explicitly out of v1 (plan doc, §2.5).
- **Push notifications** (FCM/APNs) for "transfer arrived while offline" —
  out of v1.

---

## Decisions (don't re-litigate)

| # | Decision | Where |
|---|---|---|
| 1 | Identity provider: **Keycloak** for self-host, Auth0 still works via shorthand | ADR 0001 |
| 2 | MQTT broker: **Mosquitto + mosquitto-go-auth** (HTTP backend) | ADR 0001 |
| 3 | Broker auth: **per-device JWT** signed by registry, separate key from session JWT, 15min TTL | `TokenService.kt` |
| 4 | Topic tree: `klardrop/v1/users/{user_id}/{presence,transfer/{tid}/...,trust/events}` | `BrokerAuthService.kt`, `MqttTopics.kt` |
| 5 | **Auto-accept = trusted set (same user) + signature + replay-fresh** | `MqttIncomingMessageHandler.kt` |
| 6 | Group model v1: **own devices only** (no families/teams) | `mqtt-auth-trust-production-plan.md` §1 |
| 7 | Revocation SLA: **≤ 30s** end to end | plan §2.4 |
| 8 | No offline queue in v1 | plan §2.5 |
| 9 | mTLS deferred to **phase 2** | ADR 0001 |
| 10 | Wire format: **kotlinx-serialization-protobuf**, not Wire/`.proto` files for the new envelope (existing `protos/` for legacy Klardrop/Nearby) | `SignedEnvelope.kt` |

---

## Open questions for the user

1. **Local TCP path trust** — today the local path auto-accepts everyone on
   the LAN with no trust check (`NearbyReceiverConnectionHandler.kt:103-111`).
   Once we have the trusted-set on the client for MQTT, do we also gate the
   local path by it (so a stranger on the same Wi-Fi can't push files), or
   leave local behaviour unchanged in v1? **[unanswered]**

2. **Self-host vs managed broker for production** — compose ships EMQX OSS
   for dev. For production, do we (a) self-host EMQX in your k8s with
   cert-manager + a 2-broker cluster, or (b) use **EMQX Cloud Serverless**
   for v1 and migrate to self-host later? **[unanswered]**

3. **iOS Phase-1 scope** — is iOS in scope for the first launch, or
   Android+desktop only with iOS following? Affects whether we ship the
   iOS MQTT actual now or as a stub. **[unanswered]**

4. **OIDC choice for production** — confirm Keycloak; or is Authentik
   preferred (lighter admin UI)? **[unanswered]**

---

## Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Broker auth webhook becomes a SPOF — every CONNECT (and uncached PUBLISH/SUBSCRIBE) hits registry | medium | mosquitto-go-auth caches decisions for `auth_opt_acl_cache_seconds = 20s`. Run registry with ≥2 replicas. Use a 5s HTTP timeout on the broker side. |
| Revoked device keeps a session alive past SLA | medium | Redis revoked-set checked on every reauth + 20s cache TTL = ~20s worst case. No live REST kick under Mosquitto, but we stay inside the 30s SLA. Test in M6 conformance. |
| Trust-event spoofing via compromised peer | low | Sign trust events with a server-only key (item §Backlog "signed trust events from server"). |
| Forging a clientId by guessing other devices' UUIDs | low | `BrokerAuthService` rejects clientId mismatch (covered by `mismatched clientId is denied` test). |
| Replay-cache memory blowup under attack | low | `ReplayProtector` cap = 4096 entries with oldest-half eviction; rate-limit at API edge. |
| Java 15 Ed25519 not available on older Android | medium | BouncyCastle fallback in androidMain; tracked under §In progress item 1. |
| Clock skew on user devices | medium | 60s tolerance window in `MqttIncomingMessageHandler.maxClockSkewMs`. Surfaces as `DropReason.CLOCK_SKEW` for diagnosability. |
| Pairing-code brute force | low | 8-char alphanumeric, 5min TTL, single-use, requires authenticated user. Add per-IP rate limit (§Backlog). |

---

## Cold-start runbook (for picking this up next session)

1. `git checkout claude/review-mqtt-server-UPVot && git pull`.
2. Read this file's **At a glance** table to find the milestone in flight.
3. Run the existing tests to confirm a green baseline:
   - `cd cloud-backend/device-registry && gradle --no-daemon test` (14 expected)
   - From repo root: `./gradlew :klardrop-common:desktopJvmTest --tests "com.carlom.klardrop.common.mqtt.*"` (19 expected)
4. Boot the self-host stack to exercise integration:
   ```bash
   cp cloud-backend/docker/.env.selfhost.example cloud-backend/docker/.env
   docker compose -f cloud-backend/docker/docker-compose.selfhost.yml up -d
   # Keycloak admin: http://localhost:8083  (kc-admin / kc-admin)
   # Mosquitto:      tcp://localhost:1883   (no dashboard; tail logs for verification)
   ```
5. Pick the next `[wip]` or `[todo]` item from **In progress / next up** and
   work down the list. Each item should be one commit with passing tests.
6. **Update this file** before you commit — change status, add a one-line
   note, and bump the row in the **At a glance** table if a milestone state
   changed.

### Verification commands

```bash
# Server (24 expected)
cd cloud-backend/device-registry && gradle --no-daemon test

# Client mqtt subset (37 expected — Ed25519 + KeyStore + ktor + replay + ...)
./gradlew :klardrop-common:desktopJvmTest \
  --tests "com.carlom.klardrop.common.mqtt.*"

# Full client tests
./gradlew :klardrop-common:desktopJvmTest

# Compile-only check across multiplatform
./gradlew :klardrop-common:compileKotlinDesktopJvm \
          :klardrop-common:compileKotlinAndroidDebug
```

### Useful greps

```bash
# every place that knows the user-scoped topic prefix
grep -rn 'klardrop/v1/users' --include='*.kt' --include='*.yml' --include='*.md'

# every audit event type
grep -rn 'AuditEvent\.' --include='*.kt' cloud-backend

# every actual usage of the broker session manager
grep -rn 'BrokerSessionManager' --include='*.kt' cloud-backend
```

---

## Change log

Update top-down (newest first) when you finish a meaningful chunk.

- **2026-04-26 (08539d3)** MqttCredentialsStore (DataStore + InMemory) +
  MqttCredentialsRefresher decision logic. 7 new tests.
- **2026-04-26 (6a6e204)** TrustEventPublisher in device-registry —
  Logging/Noop/Paho variants; hooked into enroll + revoke. 3 new tests.
- **2026-04-26 (571205d)** ktor-client DeviceRegistryClientHttp using
  MockEngine; OkHttp on JVM/Android, Darwin on iOS. 6 new tests.
- **2026-04-26 (SecureKeyStore)** SecureKeyStore — InMemory + file-based
  JVM impl. 5 new tests.
- **2026-04-26 (Ed25519)** Ed25519 EnvelopeSigner / Verifier actuals on
  JVM/Android via java.security; iOS stubbed. 5 new tests. New
  `jvmAndAndroidMain` source set.
- **2026-04-26 (broker switch)** Replaced EMQX OSS with Eclipse Mosquitto +
  mosquitto-go-auth. Compose / config / ADR 0001 / OpenAPI / progress doc
  updated. `BrokerAuthService` split into `authenticateUser` + `checkAcl`
  to match go-auth's getuser/aclcheck contract. `EmqxBrokerSessionManager`
  deleted, replaced by `MosquittoBrokerSessionManager` (Redis-only; no live
  REST kick — relies on 20s auth-cache TTL). Tests: 21 green (was 14, +7
  ACL matrix tests).
- **2026-04-26 (10790db)** Common/mqtt primitives in `commonMain` (envelope,
  payloads, replay, trust cache, registry client interface, incoming handler,
  outgoing encoder). 19 tests green.
- **2026-04-26 (50e9a3b)** Server hardening + self-host stack: OIDC,
  audience binding, separated session/broker keys, EMQX webhook + revoke
  kick, audit log, prod fail-fast, compose with Keycloak + EMQX, ADR 0001.
  14 tests green.
- **2026-04-25 (d80bf5c)** PR 386 — initial device-registry scaffold (Stage
  1 backend + Stage 2/3 backend hooks).
