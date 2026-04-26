# ADR 0001 — Self-hosted MQTT cloud stack

- Status: Accepted
- Date: 2026-04-26
- Supersedes: implicit "Auth0 + HiveMQ Cloud" assumption in earlier docs

## Context

The Klardrop cloud transfer feature needs a control plane (identity + device
registry + revocation) and a data plane (MQTT broker) that can run **fully
self-hosted**. Constraints:

- No vendor lock-in for identity, broker, or storage.
- Single-machine `docker compose up` for dev; Kubernetes-friendly for prod.
- Per-user topic isolation enforced by the broker, not by the client.
- ≤ 30s revocation SLA from "user removes device" to "device cannot publish".

## Decision

Use this stack:

| Concern | Choice | Notes |
|---|---|---|
| Identity provider | **Keycloak 25 OSS** | OIDC magic-link or password; single-realm `klardrop`; PKCE public client `klardrop-app` for mobile/desktop. |
| MQTT broker | **EMQX 5 OSS** | MQTT 5, TLS-only listener in prod, HTTP authn + HTTP authz pointed at the device-registry. |
| Identity verifier | **Generic OIDC** in `device-registry` | Accepts any RS256/JWKS issuer; Auth0 still works via `AUTH0_DOMAIN`. |
| Auth between broker & registry | **Bearer shared secret** + per-call HTTPS | Internal listener, never exposed to the public internet. |
| Revoked-device propagation | **Redis** (`broker:revoked:<deviceId>`, TTL = broker JWT TTL + 60s) | Authz webhook reads it on each CONNECT; live sessions kicked via EMQX REST `DELETE /api/v5/clients/{clientId}`. |
| Object storage (future, optional) | **MinIO** | Already in compose. |
| TLS termination | **Traefik / cert-manager + Let's Encrypt** | Out of scope of compose; standard k8s ingress. |
| SMTP for magic-link delivery | **Postmark/Mailgun/SES** (managed) or **Postal** (self-host) | The only place we recommend a managed dependency in v1 — deliverability is too costly to operate. |

## Why these choices

- **EMQX OSS over Mosquitto**: Mosquitto needs `mosquitto-go-auth` to do JWT
  + HTTP authz and that combination is not officially supported. EMQX has both
  built-in and a REST API for live kick-out. License (Apache 2.0 OSS edition)
  is compatible with our use.
- **Keycloak over Authentik / Ory Kratos**: Keycloak's OIDC discovery endpoint
  works out of the box with `com.auth0:java-jwt` + `jwks-rsa`. Realm export is
  reproducible (see `cloud-backend/docker/keycloak/realm-export/`).
- **Redis-backed revoked set + EMQX kick**: meets the 30s SLA in a multi-replica
  device-registry deployment because the next CONNECT is denied without any
  per-replica state, and the live session is dropped within EMQX's HTTP timeout
  (~5s default).
- **Shared secret on internal listener over mTLS**: simpler to operate in v1
  and is sufficient because the listener is an in-cluster service. mTLS is a
  Stage-5 follow-up.

## Trade-offs

- Self-hosting Keycloak adds an admin surface and an upgrade obligation. We
  accept this in exchange for vendor independence.
- EMQX OSS lacks native clustering features that the Enterprise edition has
  (rule engine, multi-cluster federation). For v1 single-node is enough; if
  scale requires it we'll re-evaluate Enterprise vs. an EMQX cluster.
- The HTTP authn webhook is called on every CONNECT. EMQX caches the decision
  for the JWT lifetime so per-publish authz is cheap, but a thundering-herd
  reconnect after a broker bounce will hit the device-registry briefly. Run
  the registry with at least 2 replicas behind a service.

## Mapping to deployed components

```
                    ┌────────────────────────────────┐
                    │  Klardrop Mobile / Desktop App │
                    │   (Auth: PKCE → Keycloak)      │
                    └──┬──────────────────────────┬──┘
                       │ HTTPS (session JWT)      │ MQTTS (broker JWT)
                       ▼                          ▼
        ┌──────────────────────────┐    ┌──────────────────────┐
        │   device-registry (Ktor) │    │      EMQX OSS        │
        │   - /v1/auth/...         │    │  - HTTP authn ─────┐ │
        │   - /v1/devices/...      │◄───┼──┘  /internal/      │ │
        │   - /v1/internal/broker  │    │     broker/auth     │ │
        └──┬───────────┬───────────┘    │  - HTTP authz ──────┘ │
           │           │                │  - DELETE /clients/.. │
           ▼           ▼                └──────────────────────┘
      Postgres      Redis
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
