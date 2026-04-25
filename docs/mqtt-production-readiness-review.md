# MQTT Cloud Transfer – Production Readiness Review

## Scope

This review covers the proposed MQTT-based cloud transfer addition as a **new connection option** alongside existing local discovery/transfer. It assumes local LAN transfer remains unchanged and available as a preferred path when peers are reachable.

## Executive Assessment

The proposal is directionally strong (hybrid local+cloud, topic hierarchy, chunking, and reuse of the trusted-message model), but it is **not production-ready yet**.

The main gaps are:

1. Identity and trust model is underspecified (user identity vs device identity vs group identity).
2. MQTT topic permissions are too broad for multi-tenant isolation.
3. Cloud server architecture is over-complex for v1 (microservices + Kafka + MQTT + object storage) and lacks end-to-end operational controls.
4. Onboarding/device enrollment flow needs explicit cryptographic and UX states.
5. Security controls (key rotation, revocation, replay prevention, audit trail, cert lifecycle) are not fully defined.

## Recommended Target Architecture (v1)

Start with a deployable modular monolith (single Ktor service + PostgreSQL + Redis + managed MQTT broker) and postpone Kafka/multi-service split until scale requires it.

### Why this shape for v1

- Faster to ship and reason about correctness.
- Easier security posture (single auth policy surface).
- Lower infra burden and fewer failure modes.
- Straight path to extraction of services later.

### Suggested v1 components

- **API Service (Ktor)**:
  - User auth/session APIs.
  - Device enrollment and certificate issuance.
  - Group membership and policy APIs.
  - Transfer control metadata APIs.
- **Managed MQTT broker** (HiveMQ Cloud/EMQX Cloud/MQTT on cloud provider):
  - Per-device credentials.
  - ACL policy enforced by group and device identity.
  - TLS-only listeners.
- **PostgreSQL**:
  - Users, devices, groups, memberships, cert records, revocations, transfer metadata.
- **Redis**:
  - Presence cache, rate limits, nonce/replay tracking, short-lived transfer state.

## Identity, Authentication, and Trust Model

Use layered identities:

1. **User identity** (human account): Auth0 email magic-link auth (v1); owns devices and groups.
2. **Device identity** (machine principal): device keypair + certificate issued by backend CA.
3. **Group identity** (trust boundary): only devices in same group can discover/transfer via cloud topics.

### AuthN/AuthZ primitives

- User signs in via Auth0 email magic-link (v1 scope).
- Device enrollment requires an authenticated user session + short-lived pairing challenge.
- Enrolled device receives:
  - device_id
  - device certificate (mTLS cert or signed JWT credential)
  - broker credentials scoped to that device
  - group-scoped topic policy
- Every MQTT app payload is additionally signed using existing trusted-message mechanism.
- Receiver validates both:
  1) broker/device authentication (transport/session layer),
  2) message signature chain (application layer).

## Onboarding Flow (User + Own Devices)

### 1) User account creation/login

1. User installs app and signs up/signs in.
2. App obtains access token + refresh token.
3. App creates or joins a default private group (e.g., `user/{user_id}/default`).

### 2) Add first device (trusted bootstrap)

1. Authenticated app requests `pairing_code` (TTL 5 minutes, one-time use).
2. New device enters/scans pairing code.
3. New device creates local keypair.
4. Backend verifies pairing code and user ownership.
5. Backend issues device certificate and binds device to group.
6. Device receives MQTT credentials + ACL + bootstrap topic subscriptions.
7. Device publishes signed presence message and becomes reachable over cloud.

### 3) Add additional devices

Same flow, but require one of:
- Existing trusted device approval (in-band prompt), or
- Step-up auth (re-auth/2FA) for high-risk enrollments.

### 4) Device removal/revocation

1. User removes device from group.
2. Backend revokes certificate/credentials immediately.
3. Broker disconnects session and denies re-connect.
4. Remaining devices receive revocation event and reject late messages from removed device.

## Transfer Routing Strategy (Local + Cloud)

Keep current local path as first preference.

1. Sender performs existing local discovery.
2. If receiver is reachable locally and policy allows, use local transfer.
3. Otherwise use MQTT cloud transfer path.
4. Maintain same application-level transfer contract (request/accept/chunk/control), with transport abstraction over local vs cloud.

This keeps UX consistent and avoids regressions for same-network speed/performance.

## MQTT Topic and ACL Hardening

Current global topics (`klardrop/presence/+`) are too permissive at scale.

Use tenant/group scoping:

- `klardrop/v1/groups/{group_id}/presence/{device_id}`
- `klardrop/v1/groups/{group_id}/transfer/{transfer_id}/...`
- `klardrop/v1/groups/{group_id}/control/{transfer_id}`

ACL principles:

- Device can publish only to its own presence and transfer sender path.
- Device can subscribe only to topics for groups it belongs to.
- No wildcard subscription outside authorized group prefixes.
- Broker-side message expiry and max payload enforced.

## Security Controls Required for Production

### Must-have

- TLS 1.2+ everywhere (broker and API).
- mTLS or short-lived signed MQTT credentials per device.
- Certificate rotation (e.g., every 30 days) and emergency revocation.
- Replay protection: nonce + timestamp + bounded skew validation.
- Message signing with current trusted mechanism integrated into MQTT payload envelope.
- Server-side rate limiting (per IP, user, device).
- Audit logs: enrollment, login, transfer initiation, revocation, auth failures.
- Secrets management via cloud secret manager (not env defaults in production).

### Should-have

- Device attestation (platform-provided where available).
- Geovelocity/anomaly detection for account/device abuse.
- Optional end-to-end payload encryption keys rotated per transfer.

## Data Model (Minimum)

- `users`
- `devices`
- `groups`
- `group_memberships`
- `device_credentials`
- `device_revocations`
- `transfer_sessions`
- `transfer_events`
- `auth_audit_events`

## Deployment Readiness Checklist (Server)

### Infrastructure

- Managed PostgreSQL with PITR backups.
- Managed Redis with persistence configured.
- Managed MQTT broker cluster with HA zone spread.
- API service across at least 2 zones.

### SRE/Operations

- `/health` and `/ready` endpoints per service.
- Metrics: auth success/failure, device connect/disconnect, transfer latency, chunk retry rate.
- Alerts: broker disconnect surge, auth failure spike, cert issuance errors, transfer timeout rates.
- Centralized structured logging with request/device correlation IDs.

### Release Safety

- Feature flag `cloud_mqtt_enabled` default off.
- Staged rollout by cohort.
- Backward compatibility with local-only clients.
- Kill switch to disable cloud path while preserving local path.

## Gaps in Current Repository Artifacts

1. Cloud backend is described as multi-service, but implementation scaffolding is partial and inconsistent with production hardening needs.
2. MQTT and auth design docs do not specify precise ACL policies and cert lifecycle.
3. Onboarding flow is not represented as explicit API contract/state machine.
4. No explicit migration plan for integrating existing trusted-message verification into MQTT envelopes.

## Concrete Changes to Make PR Production-Ready

1. **Add an ADR**: "Hybrid Local + Cloud MQTT Transport" with identity model and threat model.
2. **Add API spec** for:
   - signup/login/session refresh/logout,
   - create pairing code,
   - enroll device,
   - rotate credential,
   - revoke device,
   - list group devices.
3. **Define MQTT ACL matrix** per topic and role (device/user backend).
4. **Define certificate lifecycle** (issue/renew/revoke) and storage strategy.
5. **Implement transport abstraction** in client with deterministic fallback local→cloud.
6. **Add conformance tests**:
   - cross-group isolation,
   - revoked device denied,
   - replayed signed message rejected,
   - local/cloud fallback behavior.
7. **Add production deployment manifests** for all required services or reduce architecture to deployable monolith for v1.

## Recommended Milestones

1. **M1 (Security + Identity)**: user auth, enrollment, cert issuance, ACL enforcement.
2. **M2 (Cloud Transfer Beta)**: request/accept/chunk/control via MQTT + signing verification.
3. **M3 (Hybrid Reliability)**: local/cloud fallback, retries, resumable transfers, kill switch.
4. **M4 (Production Hardening)**: observability, SLOs, chaos/failure tests, staged rollout.

## Final Recommendation

Proceed with the feature, but gate launch on identity/ACL/cert lifecycle completion and a simplified deployable backend architecture for v1. The product value is high, but trust boundaries must be explicit and enforceable before general availability.
