# MQTT Cloud Transfer: Authentication, Trust Sync, and Production Delivery Plan

## 1) Final Decisions (based on product feedback)

## Delivery Status

- ✅ Stage 1 backend implemented with Auth0 session exchange, Redis-backed pairing codes, and DB-backed device repository (with safe in-memory fallback).
- ✅ Stage 2 backend support implemented: trusted-device approval challenge flow integrated in pairing API (client UX still pending).
- ✅ Stage 3 backend support implemented: route decision API (local/cloud) and service path ready for client integration.

---


1. **Identity provider strategy (v1):** support email magic link only (Google/Apple can be added later).
2. **2FA:** required from day one for sensitive actions (new device enrollment, credential reset, and account recovery).
3. **Device-to-broker authentication:** start with broker JWT credentials (short-lived) + application-layer message signatures; keep mTLS as phase-2 hardening.
4. **Group model (v1):** only one user's own devices (no family/team groups).
5. **Revocation SLA target:** revoke and disconnect compromised/removed device in **<= 30 seconds**.
6. **Offline behavior (v1):** live-session messaging only for transfers; no long-term offline queue.

---

## 2) What these decisions mean

### 2.1 Why Auth0 email magic link first
- Fastest path to production with minimal client auth complexity across platforms.
- Still supports account continuity across Android, iOS, desktop, and web.
- Avoids password management complexity while retaining email-based access.

### 2.2 Why 2FA from day one
- Enrollment and recovery are high-risk moments.
- 2FA can be made seamless by trusting already-approved devices:
  - If request comes from a trusted signed-in device, use in-app approval challenge.
  - Otherwise require step-up (TOTP/WebAuthn/OTP) before issuing pairing code.

### 2.3 JWT broker auth vs mTLS (plain-language)
- **JWT broker auth**: easier to operate and rotate quickly; good default for v1.
- **mTLS**: stronger mutual cryptographic identity, but heavier cert lifecycle operations.
- **Chosen approach**: JWT now + strict TTL/rotation/revocation; add mTLS when operations mature.

### 2.4 Revocation SLA (what it means)
Revocation SLA is the maximum allowed time between “user removes device” and “device can no longer send/receive.”

Target: **<= 30 seconds** end-to-end:
1. mark credential revoked in DB,
2. push revocation to broker auth backend,
3. force disconnect active MQTT sessions,
4. reject new reconnect attempts,
5. publish signed revocation notice to remaining trusted devices.

### 2.5 Offline queueing (what it means)
Offline queueing means storing and delivering old transfer messages when a device reconnects later.

For v1 we **do not** support durable offline transfer queues. If recipient is offline, sender gets explicit status and retries when device is online.

---

## 3) Authentication Architecture (Backend + Client)

### 3.1 Identity layers
1. **User identity**: human account via Auth0 email magic link.
2. **Device identity**: per-device keypair + registered public key + short-lived broker JWT.
3. **Trust identity**: app payload signatures validated using existing trusted-device mechanism.

### 3.2 Third-party provider implementation approach
- Use Auth0 as the identity provider (magic-link only for v1).
- Backend never trusts client profile data directly; only validated issuer/audience/signature tokens.
- Persist stable `provider_user_id` mapped to internal `user_id`.

---

## 4) Device Enrollment and Trust Sync

### 4.1 Enrollment flow (v1)
1. User signs in with Auth0 email magic link.
2. User has implicit private group `user/{user_id}`.
3. Existing trusted device requests pairing code (5 min TTL, one-time).
4. New device generates local keypair and submits pairing code + pubkey.
5. Backend requires 2FA step-up or trusted-device approval.
6. Backend registers device and issues broker JWT + ACL claims.
7. Device connects to MQTT and publishes signed presence.

### 4.2 Trust sync
- Trusted keys are synced through signed trust-update events on user-scoped topics.
- Receiver accepts transfer/control message only if:
  - broker identity is valid for same user scope,
  - device key is in trusted set,
  - signature and anti-replay checks pass.

### 4.3 Revocation flow
- Revoke device -> remove ACL entitlement -> disconnect MQTT session -> broadcast signed revocation event.

---

## 5) MQTT Topic and ACL Model (v1)

- `klardrop/v1/users/{user_id}/presence/{device_id}`
- `klardrop/v1/users/{user_id}/trust/events`
- `klardrop/v1/users/{user_id}/transfer/{transfer_id}/request`
- `klardrop/v1/users/{user_id}/transfer/{transfer_id}/chunks/{chunk_index}`
- `klardrop/v1/users/{user_id}/transfer/{transfer_id}/control`

ACL rules:
- Device can publish only to its own presence, its transfer sender path, and trust ack paths.
- Device can subscribe only to topics under its `user_id`.
- Any cross-user topic access denied by broker policy.

---

## 6) Production Stages

## Stage 0: Contracts + threat model (1 week)
- Freeze auth provider integration and token claims contract.
- Freeze pairing, enrollment, revocation API contracts.
- Freeze broker ACL policy and revocation propagation design.

## Stage 1: Backend auth + enrollment APIs (1–2 weeks)
- Implement session exchange and user bootstrap.
- Implement pairing code issue/consume with 2FA guard.
- Implement device registration + broker JWT issuance.
- Implement revoke device endpoint + forced broker disconnect integration.

## Stage 2: Client auth flows (1–2 weeks)
_Status: backend APIs complete; client UX integration pending._
- Add Auth0 magic-link login UX.
- Add trusted-device approval UX for new device enrollment.
- Add secure credential storage and refresh handling.

## Stage 3: MQTT transfer integration (2 weeks)
_Status: backend route-decision API complete; signed transfer pipeline still pending._
- Keep local-first routing.
- Add cloud fallback with signed request/response/chunk/control.
- Add explicit offline recipient handling (no durable queue).

## Stage 4: Hardening and launch (2 weeks)
- Metrics, alerts, audit logs, chaos tests, staged rollout, kill switch.

---

## 7) Stage 1 API Endpoints

- `POST /v1/auth/session/exchange`
- `POST /v1/users/bootstrap`
- `POST /v1/devices/pairing-codes`
- `POST /v1/devices/enroll`
- `POST /v1/devices/{deviceId}/revoke`
- `GET /v1/users/{userId}/devices`

---

## 8) Stage 1 Acceptance Criteria

- Valid Auth0 magic-link token can create backend session.
- Pairing code issuance requires authenticated user and 2FA condition.
- Enrolled device receives scoped broker JWT and can connect only to own user topics.
- Revoked device is disconnected and denied reconnect within 30 seconds.
