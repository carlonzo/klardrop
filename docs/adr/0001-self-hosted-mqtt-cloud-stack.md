# ADR 0001 — Self-hosted MQTT cloud stack

- Status: Accepted
- Date: 2026-04-26
- Revised: 2026-04-26 (broker switched from EMQX OSS to Mosquitto + mosquitto-go-auth)
- Supersedes: implicit "Auth0 + HiveMQ Cloud" assumption in earlier docs

## Context

Klardrop's cloud transfer feature needs a control plane (identity + device
registry + revocation) and a data plane (MQTT broker) that can run **fully
self-hosted on a single small VM**. Constraints:

- Side-project economics — no per-month vendor fees.
- No vendor lock-in for identity, broker, or storage.
- Single-machine `docker compose up` for dev; Kubernetes-friendly for prod.
- Per-user topic isolation enforced by the broker.
- ≤ 30s revocation SLA from "user removes device" to "device cannot publish".

## Decision

| Concern | Choice | Notes |
|---|---|---|
| Identity provider | **Keycloak 25 OSS** | OIDC magic-link or password; single-realm `klardrop`; PKCE public client `klardrop-app` for mobile/desktop. |
| MQTT broker | **Eclipse Mosquitto 2.x** with **mosquitto-go-auth** plugin (HTTP backend) | EPL/EDL (Mosquitto) + Apache 2.0 (plugin). ~5 MB image, ~10 MB resident. Image: `iegomez/mosquitto-go-auth`. |
| Identity verifier | **Generic OIDC** in `device-registry` | Accepts any RS256/JWKS issuer; Auth0 still works via `AUTH0_DOMAIN`. |
| Auth between broker & registry | Bearer **shared secret** (`INTERNAL_SHARED_SECRET`) on each webhook call, internal listener only | mTLS deferred to Stage 5. |
| Revoked-device propagation | **Redis** (`broker:revoked:<deviceId>`, TTL = broker JWT TTL + 60s) consulted by both the authn webhook (CONNECT) and the ACL webhook (every PUBLISH/SUBSCRIBE) | mosquitto-go-auth caches decisions for `auth_opt_acl_cache_seconds = 20s`, so a revocation propagates to active sessions within ~20s. |
| Object storage (future, optional) | **MinIO** | Already plumbed in compose. |
| TLS termination | **Traefik / cert-manager + Let's Encrypt** | Out of scope for compose; standard k8s ingress in prod. |
| SMTP for magic links | **Postmark/Mailgun/SES** (managed) or **Postal** (self-host) | The only place we recommend a managed dependency in v1 — deliverability is too costly to operate. |

## Why Mosquitto over EMQX OSS

We previously planned to use EMQX OSS (also Apache 2.0 and free). The
trade-off we landed on:

| | Mosquitto + mosquitto-go-auth | EMQX OSS |
|---|---|---|
| Image size | ~5 MB | ~150 MB |
| Memory footprint | ~10 MB resident | ~200 MB+ |
| Config | one `.conf` file | YAML + dashboard + REST |
| Force-disconnect API | none — rely on auth-cache TTL + reconnect-deny | REST `DELETE /clients/{id}` |
| MQTT 5 | yes | yes |
| Clustering | no (single-node only) | yes |
| Maturity / governance | Eclipse Foundation, ~16 yrs | EMQ Inc., commercial OSS |

EMQX shines when you need clustering, a built-in dashboard, or live REST
control — none of which a side-project single-VM deployment requires today.
Mosquitto's smaller resource footprint and one-file config are a better fit,
and the loss of a live kick API is mitigated by the short auth-cache TTL +
Redis revoked-set we already have.

If the project later outgrows single-node Mosquitto, this decision is
reversible: only `BrokerSessionManager` and the webhook response shape change
when swapping brokers.

## Why these other choices

- **Keycloak over Authentik / Ory Kratos** — Keycloak's OIDC discovery
  endpoint works out of the box with `com.auth0:java-jwt` + `jwks-rsa`; realm
  export is reproducible (`docker/keycloak/realm-export/klardrop-realm.json`).
- **Shared-secret on internal listener over mTLS** — simpler to operate in
  v1 and sufficient because the listener is in-cluster only. mTLS is a
  Stage-5 follow-up.

## Trade-offs we accept

- Self-hosting Keycloak adds an admin surface and an upgrade obligation.
- Mosquitto OSS is single-node — no horizontal scaling beyond one broker
  process. Acceptable until ≥ 10k concurrent devices.
- The HTTP webhook is called on every CONNECT and on every PUBLISH/SUBSCRIBE
  not in cache. mosquitto-go-auth caches for 20s, so the registry sees ≤ one
  call per device per 20s in steady state. Run the registry with at least 2
  replicas behind a service so the webhook isn't a SPOF.
- Without a kick API, the worst-case delay between revoke and an active
  session being denied is `auth_opt_acl_cache_seconds` (currently 20s).
  Combined with our budget of 30s SLA, that's comfortable.

## Mapping to deployed components

```
                    ┌────────────────────────────────┐
                    │  Klardrop Mobile / Desktop App │
                    │   (Auth: PKCE → Keycloak)      │
                    └──┬──────────────────────────┬──┘
                       │ HTTPS (session JWT)      │ MQTTS (broker JWT)
                       ▼                          ▼
        ┌──────────────────────────┐    ┌──────────────────────┐
        │   device-registry (Ktor) │    │   Mosquitto + go-auth│
        │   - /v1/auth/...         │    │  - HTTP authn ─────┐ │
        │   - /v1/devices/...      │◄───┼──┘ /broker/auth/   │ │
        │   - /v1/internal/broker  │    │     {user|acl|     │ │
        │     /{user|acl|superuser}│    │      superuser}    │ │
        └──┬───────────┬───────────┘    └──────────────────────┘
           │           │                       (no live REST kick;
           ▼           ▼                        revocation via cache TTL
      Postgres      Redis                       + Redis revoked-set)
      (devices,    (pairing
       audit)       codes,
                    revoked-set,
                    nonces)
                  ▲
                  │
        ┌─────────┴─────────┐
        │     Keycloak      │
        │  realm: klardrop  │
        │  client: device-  │
        │     registry      │
        └───────────────────┘
```

## Migration path

Production teams that started on Auth0 keep using it by setting `AUTH0_DOMAIN`
and `AUTH0_AUDIENCE`. The OIDC verifier treats those as a shorthand for
`OIDC_ISSUER=https://{domain}/`, no code changes required.

If we ever want to swap Mosquitto for EMQX (or the other way), the only
device-registry-side changes are:
- `BrokerSessionManager` implementation.
- The webhook response format (Mosquitto wants HTTP 200/4xx + status mode;
  EMQX expects a JSON body with `result: allow|deny`).

The `BrokerAuthService` decision logic is unchanged.
