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
  for EMQX OSS + Keycloak + shared-secret broker webhook.

---

## At a glance

| Milestone | Owner area | Status | Notes |
|---|---|---|---|
| **M0** Lock contracts | docs | done | topic tree, JWT claims, ACL matrix all pinned in code + OpenAPI. |
| **M1** Harden device-registry | `cloud-backend/device-registry` | done | session/broker key split, audience-binding, mosquitto-go-auth webhook, audit, prod fail-fast. |
| **M2** Stand up the broker | ops + compose | partial | self-host compose ready (Mosquitto + go-auth); TLS certs + prod k8s manifest pending. |
| **M3** Client `common/` MQTT | `common/` KMP | partial | trust-set + sig + replay primitives done; platform MQTT client + DI wiring pending. |
| **M4** Trust + auto-accept | `common/` + `device-registry` | partial | receiver gate done; trust-event publisher + MessageReceiver branch pending. |
| **M5** Smart routing | `common/` | todo | needs M3 platform client first. |
| **M6** Hardening + launch | cross-cutting | todo | feature flag, kill switch, conformance tests, metrics, alerts. |

Source-of-truth commits on `claude/review-mqtt-server-UPVot`:
- `50e9a3b` self-host stack + EMQX webhook + OIDC + hardening
- `10790db` common/mqtt trust + auto-accept primitives

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
- **`ReplayProtector`** — TTL+cap LRU keyed by `(senderDeviceId, nonce)`.
- **`TrustedDeviceCache`** — interface + `InMemoryTrustedDeviceCache`.
- **`DeviceRegistryClient`** — interface + `InMemoryDeviceRegistryClient`.
- **`MqttCredentials` / `MqttTopics` / `MqttConnectionState`** — single source
  of truth for client-side MQTT routing.
- **`MqttIncomingMessageHandler`** — the auto-accept invariant. Returns
  `Outcome.Deliver` only when sender ∈ trust set ∧ signature valid ∧ fresh ∧
  receiver matches. `Outcome.Drop` is total and never throws.
- **`MqttOutgoingMessageEncoder`** — sender-side complement using the same
  canonical layout.

### Tests

- `:device-registry:test` — **21 green** (12 BrokerAuthService [auth + ACL
  matrix] + 2 nonce + 7 DeviceService).
- `:klardrop-common:desktopJvmTest` (mqtt subset) — **19 green** (9 incoming
  handler + 4 replay + 4 trust cache + 2 canonical).

---

## In progress / next up

Listed in the order I plan to tackle them. Dependencies marked `→`.

1. `[wip]` **Ed25519 crypto actuals** for `EnvelopeSigner`/`EnvelopeVerifier`.
   - JVM (`desktopJvmMain` and `androidMain`): use `java.security` Ed25519
     (Java 15+; on Android needs API 33+ or BouncyCastle fallback).
   - iOS: CryptoKit Curve25519 — stub for now, real impl when we tackle iOS.
   - Test: round-trip sign/verify + tampered-bytes negative test.

2. `[todo]` **`SecureKeyStore` expect/actual** for the device's private signing key.
   - `expect class SecureKeyStore` in commonMain; actuals on Android (Keystore-
     backed cipher), JVM (PKCS#12 keystore in `~/.config/klardrop/`), iOS
     (Keychain, deferred).
   - Key generated at first launch; never leaves the device.

3. `[todo]` **`DeviceRegistryClientHttp`** (ktor-client multiplatform) +
   MockEngine tests.
   - Endpoints: `POST /v1/auth/session/exchange`, `GET /v1/users/{userId}/devices`,
     `POST /v1/devices/me/broker-token`.
   - Adds `ktor-client-core`/`-content-negotiation`/`-serialization-json` to
     `commonMain`, OkHttp engine to JVM/Android, Darwin engine to iOS.

4. `[todo]` **`MqttCredentialsStore`** (DataStore-backed persistence) so the
   broker JWT survives restarts and the refresh loop knows when to ask.

5. `[todo]` **Trust event publisher in device-registry** — when a device is
   enrolled or revoked, publish a `MqttPayload.TrustEvent` to
   `klardrop/v1/users/{userId}/trust/events` so all online devices update their
   `TrustedDeviceCache` within seconds (without polling the HTTP API).
   - Use Eclipse Paho Java client (already on the classpath via `transfer-service`).
   - Hook into `DeviceService.enrollDevice` / `revokeDevice`.
   - Sign the event with a **server signing key** (added to `BrokerJwtConfig`)
     so clients verify it came from the registry, not a peer.

6. `[todo]` **`MqttModule` (DI) — interfaces only.**
   - Add `mqttConnectionManager()`, `mqttDiscoveryService()`,
     `mqttTransferTransport()`, `trustedDeviceCache()`, `deviceRegistryClient()`,
     `mqttIncomingMessageHandler()`, `mqttCredentialsStore()`, `secureKeyStore()`
     to `CommonComponent`.
   - Provide everything *except* the platform MQTT client (that's M3-deferred).
   - Put the `enableMqttCloud` flag on `ApplicationInfo`.

7. `[todo]` **Platform MQTT client actuals** (M3 finisher; needs a broker to
   verify end-to-end).
   - `MqttConnectionManager` expect class.
   - JVM/Android actual: HiveMQ MQTT 5 client (or Paho fallback).
   - iOS actual: stub throwing `NotImplementedError("M3-iOS")` — track in §Backlog.
   - Auto-reconnect with exp backoff, JWT refresh at `expiresAtEpochMs - 60s`,
     retained presence + LWT.

8. `[todo]` **MessageReceiver branch for MQTT inbound** — when
   `MqttIncomingMessageHandler.Outcome.Deliver` arrives, push directly to
   `ReceiveMessageStatus.Progress` (skip `PendingAuthorization`). Mirror of
   `NearbyReceiverConnectionHandler.kt:103-111` minus the prompt.

9. `[todo]` **`SmartTransferManager`** — picks LOCAL_TCP if reachable in
   `VisibleDevices`, else MQTT. Single sender entrypoint for the UI.

10. `[todo]` **Conformance tests against live EMQX** (testcontainers).
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
# Server
cd cloud-backend/device-registry && gradle --no-daemon test

# Client (mqtt subset)
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
