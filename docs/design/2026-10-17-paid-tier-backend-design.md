# Paid-Tier Backend Design (Captions Cloud)

Date: 2026-10-17
Status: Draft for owner review. Produced by the backend architect agent from the product spec
(sections 5 and 6 and 7); condensed here. Nothing is built. Prices, vendor rates and limits quoted below are
**estimates to verify** against current vendor pricing before committing.

Feeds the next implementation plan (backend + cloud pipeline). The Android free-tier plan does not depend on it.

## 1. Platform decisions

| Choice | Pick | Alternative |
|---|---|---|
| Runtime | Node 22 + TypeScript, Fastify (HTTP) + `ws` (WebSocket) | FastAPI + uvicorn |
| Host | Cloud Run gen2, one service; request timeout 3600 s, session affinity on, min-instances 1, concurrency 250, capped max-instances, CPU always allocated | Railway (more hand-rolled OIDC) |
| DB | Neon Postgres, pooled endpoint (transaction mode), autosuspend off in prod, region near Cloud Run | direct endpoint (connection limits) |
| Consequences | No session-level advisory locks (row locks in one transaction only). Server rotates every stream at 55 min with close code 4008 (Cloud Run 60 min cap) | |

One deployable, three entry points: (a) HTTP endpoints, (b) long-lived WebSocket ASR + translation proxy,
(c) Google Play Real-time Developer Notification (RTDN) webhook. The client falls back to the on-device pipeline
on any backend failure, so the backend can fail closed.

## 2. Endpoints and WebSocket contract

| Route | Purpose |
|---|---|
| `POST /v1/session` | Firebase ID token in; short-lived session JWT, `ws_url`, `remaining_ms`, `tier` out |
| `POST /v1/consent` | Record `consent_version` |
| `POST /v1/purchases/link` | `{purchaseToken, productId}`: verify, acknowledge, link |
| `GET /v1/me/usage` | tier, period, used_ms, cap_ms, trial_remaining_ms |
| `DELETE /v1/me` | Account deletion (Play data-deletion policy) |
| `GET /v1/stream` | WebSocket upgrade |
| `POST /v1/webhooks/play-rtdn` | Pub/Sub push (OIDC verified) |
| `GET /healthz`, `GET /readyz` | Liveness (process only), readiness (Neon + cached vendor probes) |
| `POST /v1/internal/reconcile` | Daily subscription reconcile + retention purge (Cloud Scheduler, OIDC) |

WebSocket: auth via `Authorization: Bearer <session JWT>` on upgrade (never the query string). First client
frame `{type:"start", src_lang, tgt_lang, sample_rate:16000, encoding:"linear16"}`, then binary PCM frames
(20–100 ms, max 64 KB). Server messages: `ready`, `partial`, `final` (with translation or `tr_error`), `quota`,
`notice{degraded}`.

| Close code | Meaning | Client action |
|---|---|---|
| 4001 | auth invalid/expired | get new session |
| 4002 | superseded by another device | fall back local, show notice |
| 4003 | not entitled | fall back, show paywall |
| 4008 | rotate / max duration / idle | reconnect immediately |
| 4029 | monthly cap reached | fall back, show "cap reached" |
| 4400 | protocol violation / over-rate audio | fall back, no retry |
| 4503 | vendor unavailable or slow | fall back, retry with backoff |
| 1011 | internal error | fall back, retry with backoff |

## 3. Data model

Append-only ledger plus a derived monthly counter, written in one transaction. Metering unit is milliseconds of
audio forwarded to the vendor, computed server-side as `bytes / 32` (16 kHz, 16-bit mono). Client-reported
durations are never trusted.

| Table | Key columns / notes |
|---|---|
| `users` | `uid` (Firebase, pseudonymous), `consent_version`, `consent_at`, `device_hash`, `deleted_at`. No PII |
| `subscriptions` | `purchase_token` PK, `uid` (nullable until linked), `state`, `expiry_time`, `acknowledged`, `linked_purchase_token`, `superseded` |
| `entitlements_v` (view) | derived: entitled if a non-superseded subscription is ACTIVE / IN_GRACE_PERIOD / CANCELED and not expired |
| `usage_month` | PK `(uid, month)`, `used_ms` |
| `usage_events` | PK `(stream_id, seq)`, `ms`, `kind` paid/trial. 90-day retention |
| `trial_claims` | `device_hash` PK, `used_ms`, `cap_ms` (lifetime trial per device) |
| `stream_leases` | `uid` PK: one active stream per account |
| `streams` | per-stream summary (close code, audio_ms, vendors, languages). **No text, no audio** |
| `rtdn_events` | `message_id` PK for dedupe/audit, 30-day retention |
| `service_flags` | kill switch `cloud_enabled`, per-vendor enable, cached 30 s |

Invariants in schema: one owner per purchase token; flush counted once (`ON CONFLICT DO NOTHING`, counter
incremented only from returned rows); `CHECK (ms > 0 AND ms <= 120000)`; `CHECK (used_ms >= 0)`; one trial per
device; subscription state restricted to a known set. Consent before cloud use is an application guard at
session issue.

Flush transaction (every 10 s per stream and at close): insert ledger row, increment `usage_month` (and
`trial_claims` for trial), and return `used_ms`, lease ownership and entitlement in the same transaction. The
connection then continues, warns, or closes (4002 / 4003 / 4029).

## 4. Main failure modes and responses

| Area | Failure | Response |
|---|---|---|
| Session | invalid ID token, missing App Check, consent missing, not entitled, cap reached | 401 / 403 with typed error; client falls back local |
| Session | Neon slow or down, vendor breaker open, kill switch off | 503 with `retry_after`; client stays local |
| Purchase | Play API 5xx | 503; client retries later |
| Purchase | token owned by another uid (reinstall) | transfer to new uid, max 3 transfers per token per 30 days, else 409 |
| Purchase | not acknowledged within 3 days (Play auto-refunds) | acknowledge at link and in RTDN handler; reconcile cron retries; alert if older than 24 h |
| WebSocket | vendor connect fail/slow, backpressure | close 4503; circuit breaker (5 failures in 30 s opens 30 s) |
| WebSocket | vendor drops mid-stream | one silent vendor reconnect; metering counts client bytes so no double count |
| WebSocket | translation fails or over 800 ms | `final` with `tr_error`; client translates that line locally |
| WebSocket | audio faster than 1.5x real time, oversize frame, idle 120 s | close 4400 / 4400 / 4008 |
| Metering | DB flush fails | retry same `seq`; if over 60 s unflushed, close 1011 (fail closed) |
| Metering | instance crash | up to 10 s unbilled (accepted under-count); lease taken over on next open |
| RTDN | redelivery, out-of-order | dedupe by `message_id`; handler re-fetches current state from Play (notification type is only a hint) |
| RTDN | Play API failure | return 500 so Pub/Sub retries; DLQ after 10 attempts; daily reconcile as safety net |
| Cost | runaway usage | flip `cloud_enabled=false` (30 s); Cloud Run max-instances; vendor spend alerts |

## 5. Authorization

| Entry | Principal | Enforcement |
|---|---|---|
| User endpoints | Firebase ID token + App Check (Play Integrity) | `uid` comes only from the token; repository functions require `uid` |
| `/v1/stream` | Session JWT (HS256, `kid` rotation, `aud=captions-ws`, 5-min TTL) | verified before upgrade; lease + entitlement re-checked in DB |
| RTDN webhook | Pub/Sub push service account | OIDC verification (aud = exact URL, expected SA email, package name check) |
| Reconcile | Cloud Scheduler service account | OIDC verification |
| Outbound | Server service accounts / Secret Manager keys | workload identity; keys never sent to the client or logged |

Limits: 1 active stream per uid (newest wins); 55 min max stream; 64 KB frames; 120 s idle; 60 session issues per
hour and 20 stream opens per 10 min per uid; 30 h monthly cap (default, to be tuned); 10-minute lifetime trial per
device; global kill switch. App Check must be **enforced, not just monitored**, before launch.

## 6. Idempotence and concurrency

- Flush retried with same `(stream_id, seq)` is a no-op. Link and RTDN both converge to the state Play returns.
- Two devices, same account: lease takeover on open; old stream closes with 4002 at its next flush (at most 10 s overlap).
- RTDN vs link vs reconcile on the same token: `SELECT ... FOR UPDATE` on the subscription row, then fetch, then write, in one transaction.
- Reconnect storms: client full-jitter backoff (1 s to 60 s, honours `retry_after`); server accept bucket; rotation
  randomized +/- 3 min; SIGTERM closes with 4008 after a final flush.
- Acknowledge uses the database row as durable intent and the reconcile cron as the relay.

## 7. Observability

- Structured JSON logs with a **field allowlist** enforced by a CI test: audio, transcripts, translations, tokens,
  full purchase tokens and vendor keys can never be logged. Purchase tokens appear only as a hashed prefix.
- Metrics: HTTP RED; WebSocket (active streams, opens, closes by code, duration); vendor latency and errors;
  metering (audio_ms, flush results); **cost per audio hour** and daily cost by tier; RTDN outcomes and DLQ depth.
- Alerts: 5xx rate over 2%; 4503 share over 10%; ASR final lag p95 over 2 s; daily cost over budget; cost per hour
  over 1.3x baseline; DLQ depth above 0; unacknowledged purchases older than 24 h; flush failures above 0.5%.
- `/healthz` has no dependency checks (avoid restart loops); `/readyz` checks Neon and cached, non-billable vendor probes.
- Trace propagation via W3C `traceparent`; stream is one long span keyed by `stream_id`.

## 8. Build sequence

1. Skeleton, allowlisted logger, health, deploy. 2. Schema and uid-scoped repositories (tests on a per-run Neon branch).
3. Auth, consent, session JWT. 4. Play link, verification, acknowledge. 5. RTDN webhook and reconcile.
6. Vendor adapters plus a fake vendor server for CI. 7. WebSocket proxy without metering.
8. Metering, leases, caps, trial. 9. Rate limits, kill switch, cost controls. 10. Observability and alerts.
11. Load and chaos tests (500 concurrent streams, reconnect storm, Neon pause). 12. Privacy: account deletion,
retention jobs. 13. Staging soak with real vendors to set the real cap and price.

Each phase ends with its tests green; the architect's per-phase test list is retained in the agent's report and
should be carried into the plan.

## 9. Open decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | ASR vendor | Deepgram streaming behind an adapter, Google STT v2 as second adapter. Verify price and language coverage first |
| 2 | Translation vendor | Google Cloud Translation v3 by default; DeepL per language pair later |
| 3 | Translating partials | Finals always; partials throttled (max 1 per segment per second, only after 3 new words) |
| 4 | Cap and price | 30 h cap, about $5.99/month as a start; finalize after the soak. Cost of a cap-maxing user may exceed net price, so cut to 20 h if usage is heavy |
| 5 | Token owned by another uid | Allow transfer, rate-limited; consider Google sign-in linking in v1.1 |
| 6 | Trial | 10 min lifetime per device, gated on Play Integrity verdict |
| 7 | Multi-device | One active stream per account, newest wins |
| 8 | Month definition | Calendar month in UTC |
| 9 | Region | Single region near launch market |

## 10. Technical risks

- Neon is not on GCP, so Cloud Run to Neon is cross-cloud (a few ms in the same metro); session issue pays 1–2 extra round trips.
- Cloud Run 60-minute limit is handled by the 55-minute rotation; the client must pre-fetch a token and reconnect in about 300 ms. Validate on device.
- Up to 10 s of unbilled audio per crashed stream is an accepted under-count.
- Firebase ID token replay: enforce App Check.
- Vendor data handling must be confirmed (no retention, no training use); it underpins the privacy promise and the Play data-safety form.
- RTDN needs correct Play Console and Pub/Sub setup per environment; verify with a test notification.
- If measured end-to-end latency exceeds about 1.2 s, show the source-language partial first on the client rather than translating more partials on the server.
