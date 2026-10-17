# Paid-Tier Backend Design (Captionly Cloud Speech)

Date: 2026-10-17
Status: Final blueprint, revised with the owner's binding decisions. Nothing is built yet. Vendor prices, language lists and endpoint details were checked against Deepgram's public pages on 2026-10-17. They are marked "verify" where the build sequence re-checks them (Phase 0 and the staging soak).

Scope: the cloud **speech recognition** backend for the paid tier, and the seam where the Android app (`/home/user/Voice-caption/app`) plugs into it. Translation stays on the phone with ML Kit.

---

## 0. Decisions recorded

| # | Decision | As decided by the owner |
|---|---|---|
| 1 | Pricing model | Monthly Google Play subscription. No consumable packs |
| 2 | Cloud scope | Cloud speech recognition only. Translation stays on-device with ML Kit. No translation proxy, no translation vendor, no translated-character metering |
| 3 | ASR vendor | Deepgram only (Nova-3 streaming), behind an adapter interface. Languages Deepgram cannot stream fall back to the on-device engine |
| 4 | Price and cap | $4.99/month, a cap of 10 h/month of audio forwarded to the vendor (speech only). Cap and price are server config. Cost planning range is $0.35–0.55 per audio hour |
| 5 | Trial | 10 minutes of cloud audio per device, once in a lifetime, only with a passing Play Integrity / App Check verdict |
| 6 | Multi-device | One active stream per account, newest wins. The older stream closes with 4002 and that client falls back to on-device |
| 7 | Purchase token seen under a new anonymous account | Transfer it to the new account, at most 3 transfers per token per 30 days. The old account loses entitlement. Over the limit returns 409 |
| 8 | Month definition | Calendar month in UTC |
| 9 | Region | Global from day one. Stateless regional proxies behind a global load balancer, one primary Postgres (Neon), session JWTs carry tier and allowance, 10 s usage flush that fails closed after 60 s unflushed, Deepgram regional endpoints, RTDN handled in one place, leases in the primary DB |

### 0.1 Consequences of these decisions

None of them makes a decision unworkable. Each consequence below changes the design.

| # | Consequence | How the design handles it |
|---|---|---|
| 2 | Cloud partials arrive in the source language. Only finals are translated, on the phone | Partials are shown as source text (the stabilizer already does this for non-final captions). ML Kit translates the final, adding about 50–200 ms |
| 3 | 7 of the app's 57 languages are missing from Deepgram's Nova-3 list (preliminary, §1.9): cy, gl, ht, is, mt, sq, sw | The server lists supported languages in the session response. The client uses on-device recognition for the rest. The paywall names the covered languages |
| 4a | At Deepgram's regular (non-promotional) pay-as-you-go rate of $0.462/h, a user who uses the full cap **loses money** (−$0.38/month). It is worse in markets where VAT is inside the price (§6) | The cap is server config, so it can be cut without an app release. Margin is monitored per user-month, and the Growth plan is evaluated after the soak |
| 4b | "Price as server config" cannot set the price that is actually charged. That price lives in the Play Console base plan, and Play policy requires showing the localized price from `ProductDetails` | Server config holds the cap, the trial length, warning thresholds and an `expected_net_price_micros` value used only for margin metrics. The app always shows Play's price |
| 5 | "Once per device in its lifetime" can only be approximated. An `ANDROID_ID`-based hash resets on factory reset | The trial key is an HMAC of a client hash of `ANDROID_ID`. It is kept after account deletion, which the privacy policy discloses. Play Integrity "device recall" is the upgrade path (§13) |
| 6 | A rotation or reconnect from the **same** device must not count as a newer device, or a client would kick itself | The lease records `device_hash`. A same-device takeover closes the old stream with 4008, not 4002 |
| 7 | A transfer to a fresh anonymous uid would reset the monthly cap (the cap is per uid), which is a loophole worth up to 3 extra caps a month | The transfer transaction carries the old uid's paid `used_ms` for the current month over to the new uid |
| 9a | With no documented Deepgram endpoint in Asia and the primary DB in the US, an Asia proxy adds almost no latency gain. The load balancer already ends TLS at the edge near the user | Asia is deployed for availability but is "on probation". It is the first region cut if the soak shows less than 100 ms p95 gain (§1.4) |
| 9b | EU data residency cannot depend on which region serves the request, because failover would move EU audio to the US endpoint | The Deepgram endpoint is chosen from the **client's country** (load-balancer header) at session issue and fixed in the JWT. Any region honours it |

---

## 1. Context

**Component type:** a real-time streaming proxy (a long-lived WebSocket per captioning session) plus a small set of HTTP endpoints. Next to it is a single-region billing service: Play purchase verification, the Real-time Developer Notification (RTDN) Pub/Sub push consumer, and a daily reconcile/retention cron.

**Latency model:** the audio path is the only part that is latency-critical: phone, then nearest region, then Deepgram, then back. Target p95 is about 1.2 s from speech to partial, US/EU. Session issue is interactive but rare (once per capture start or reconnect). It may pay one cross-region database round trip. Usage flushes are off the audio path.

**Failure model:** the phone always has a working on-device pipeline (WhisperLocal + ML Kit). So every backend component **fails closed** and the client falls back to on-device per segment, without losing captions.

**Concurrency model:** one WebSocket = one Deepgram stream = one lease row per account. There is no per-region state. The primary Postgres is the single source of truth.

### 1.1 Platform decisions

| Choice | Pick | Alternative (one line) |
|---|---|---|
| Runtime | Node 22 + TypeScript, Fastify (HTTP) + `ws` (WebSocket), `pg` driver, `jose` (JWT), Firebase Admin SDK, `googleapis` (Android Publisher, Play Integrity) | FastAPI + uvicorn |
| Realtime service | Cloud Run gen2 service `captions-rt` in 3 regions. Timeout 3600 s, concurrency 250, CPU always allocated, min-instances 1 per region, max-instances 20 per region, startup CPU boost | GKE Autopilot (more ops) |
| Billing service | Cloud Run `captions-billing` in **us-east4 only**. Min-instances 0, concurrency 80, timeout 60 s | Cloud Functions |
| Front door | Global external Application Load Balancer: serverless NEGs per region, outlier detection, backend-service timeout 3600 s (WebSocket lifetime), custom header `X-Client-Region: {client_region}`, Cloud Armor per-IP throttle | Per-region hostnames with client-side selection |
| Database | Neon Postgres, **aws-us-east-1**, pooled endpoint (transaction mode), autosuspend off in prod, PITR 7 days | Cloud SQL us-east4 (lower RTT from the US region, more ops) |
| Hot-path queries | Every hot-path DB interaction is **one SQL function call = one round trip** (`issue_session`, `acquire_lease`, `flush_usage`) | Multi-statement transactions (2–4 cross-region RTTs) |
| Secrets | Secret Manager (global replication): Deepgram key, JWT HMAC keys, device-hash pepper | — |
| Consequences | No session-level advisory locks (pooled transaction mode), so row locks only inside one function call. Streams rotate at 55 ± 3 min with 4008 because of the Cloud Run 60-minute cap | |

Regions: **us-east4** (Ashburn, about 2 ms to Neon us-east-1), **europe-west4** (Netherlands; switch to europe-west3 if the soak shows the Deepgram EU host is closer), **asia-southeast1** (Singapore, on probation).

### 1.2 Global topology

| Path (URL map on the global load balancer) | Backend | Why |
|---|---|---|
| `/v1/session`, `/v1/me`, `/v1/consent`, `/v1/stream` | `captions-rt`, nearest healthy region | Interactive and audio paths |
| `/v1/purchases/*`, `DELETE /v1/me` | `captions-billing` (us-east4) | One writer for subscription rows; rare calls |
| `/v1/webhooks/play-rtdn`, `/v1/internal/*` | `captions-billing` (us-east4) | RTDN processed in exactly one place (decision 9) |

Health-based failover: serverless NEGs have no classic health checks, so **outlier detection** ejects a region that returns consecutive 5xx (5 in 10 s, ejected for 30 s). The service therefore returns 5xx **only for real faults**. A database that is unreachable from a region gives 503, which ejects that region. The kill switch, cap reached and "not entitled" give 200 with `stream_allowed:false`, so they never eject a region. Per-region Cloud Monitoring uptime checks hit `/readyz` on the regional `run.app` URLs, for alerting only.

Deepgram endpoint selection (data residency):

| Client country (`X-Client-Region`) | `asr_region` in JWT | Deepgram host | Note |
|---|---|---|---|
| EEA, UK, CH | `eu` | `wss://api.eu.deepgram.com/v1/listen` | The EU endpoint is offered by Deepgram for in-EU processing (pricing page, 2026-10-17). Verify Nova-3 streaming availability and price parity in Phase 0 |
| Everyone else | `us` | `wss://api.deepgram.com/v1/listen` | No Asia endpoint is documented. Asia traffic crosses the Pacific once (proxy to Deepgram) |
| Unknown or missing header | `us` | default | Logged as `client_region=unknown` |

Residency implications:
- **EU user on the US endpoint** (not possible in this design, except through a config override): audio content would be processed in the US. That is a restricted transfer under GDPR, needing Deepgram's DPA/SCCs or Data Privacy Framework coverage, and it would have to be stated in the privacy policy.
- **EU endpoint chosen** (default for EU clients): audio is processed in the EU even if the EU region is down and a US proxy relays it. The relay crosses the Atlantic in transit but stores nothing.
- **Metadata** (pseudonymous uid, usage counters, subscription state) lives in the US primary for everyone. That is a transfer covered by Neon's DPA and must be disclosed.
- **EU Deepgram endpoint down:** fail closed with 4503. EU audio is never silently re-routed to the US.

### 1.3 Latency budget per hop (estimates; the soak measures each one)

| Hop | Expected RTT | p95 budget | Notes |
|---|---|---|---|
| Phone to load-balancer edge (Wi-Fi/4G) | 20–60 ms | 150 ms | Radio dominates |
| Edge to Cloud Run region (Google backbone, same continent) | 1–20 ms | 30 ms | |
| us-east4 to Neon us-east-1 | 1–3 ms | 10 ms | |
| europe-west4 to Neon us-east-1 | 80–95 ms | 130 ms | Session issue and flush only |
| asia-southeast1 to Neon us-east-1 | 210–240 ms | 300 ms | Session issue and flush only |
| us-east4 to Deepgram US | 5–30 ms | 50 ms | Deepgram host location unverified |
| europe-west4 to Deepgram EU | 5–25 ms | 50 ms | |
| asia-southeast1 to Deepgram US | 170–240 ms | 300 ms | Same long haul as phone to US directly |
| Deepgram interim result after audio | 200–400 ms | 600 ms | Vendor processing |
| Deepgram `Finalize` to `from_finalize` result | 100–300 ms | 500 ms | |
| Client audio batching (3 × 32 ms frames) | 96 ms | 96 ms | |

| End-to-end target | US / EU p95 | Asia p95 |
|---|---|---|
| `POST /v1/session` (one DB RTT + local token checks) | 300 ms / 400 ms | 600 ms |
| WebSocket open to `ready` (TLS + upgrade + Deepgram connect, lease async) | 500 ms | 1200 ms (Deepgram TLS handshake crosses the Pacific) |
| Speech to partial on screen | 1.0 s | 1.4 s |
| Segment end (after 400 ms VAD hangover) to translated final on screen | 1.0 s | 1.3 s |

### 1.4 What "global from day one" costs, and the cut order

Costs:
1. **Operations:** three Cloud Run deployments plus a billing service, a global load balancer with serverless NEGs, certificates, Cloud Armor, and per-region dashboards.
2. **Session issue latency:** cross-region DB latency of +90 ms in the EU and +240 ms in Asia.
3. **Failure modes that do not exist in a single region:** a network partition between a region and the database, cross-region lease races, and outlier ejection behaviour.
4. **More tests:** region loss, primary DB unreachable from one region, and residency routing.
5. **Fixed cost:** about +$100/month for two extra always-on minimum instances.
6. **Two Deepgram endpoints** whose model and feature availability can differ.

Cut order if the soak measurements disappoint (no client change in either case: `ws_url` is the global hostname and comes from server config):
1. **Cut asia-southeast1 first.** Without an Asia Deepgram endpoint it only adds availability. Keep it only if the soak shows at least 100 ms p95 gain on stream open or partial latency for Asian clients.
2. **Then cut europe-west4.** Keep `asr_region=eu` in the JWT so EU audio still goes to the EU Deepgram endpoint, relayed from us-east4 (adds about 90 ms to the audio path).
3. End state: us-east4 alone behind the same global load balancer hostname.

### 1.5 HTTP endpoints

| Route | Service | Auth | Purpose |
|---|---|---|---|
| `POST /v1/session` | rt | Firebase ID token + App Check | Issues a session JWT and returns the contract in §1.6. Claims the trial on first use (needs `integrity_token`) |
| `GET /v1/me` | rt | ID token + App Check | Same body as the session response without `session_token` (for UI refresh) |
| `POST /v1/consent` | rt | ID token + App Check | `{consent_version}`. Records cloud-audio consent |
| `DELETE /v1/consent` | rt | ID token + App Check | Withdraws consent. Later sessions are refused |
| `POST /v1/purchases/link` | billing | ID token + App Check (limited-use token) | `{purchase_token, product_id}`: verify with Play, acknowledge, link or transfer |
| `DELETE /v1/me` | billing | ID token + App Check | Account deletion (Play data-deletion policy) |
| `GET /v1/stream` | rt | `Authorization: Bearer <session JWT>` on upgrade | WebSocket (§1.8). Never a query-string token |
| `POST /v1/webhooks/play-rtdn` | billing | Pub/Sub push OIDC | RTDN |
| `POST /v1/internal/reconcile` | billing | Cloud Scheduler OIDC | Daily subscription reconcile, acknowledgement retry, retention purge |
| `GET /healthz`, `GET /readyz` | both | none | Liveness (process only). Readiness: config loaded, DB reachable (cached 10 s), Deepgram breaker not open |

Session request body: `{language, app_version, device_hash, integrity_token?}`. Here `device_hash = SHA-256("captionly:" + ANDROID_ID)` computed on the client. The server stores only `HMAC-SHA256(pepper, device_hash)`.

### 1.6 Session response contract

Every authenticated, App-Check-valid request returns **200** with this body. Refusals are expressed in `stream_allowed`/`reason` so the UI always gets numbers. Errors: 401 for a bad or expired ID token, 403 for a missing or invalid App Check token, 429 for the session rate limit, 503 + `retry_after_s` when the database is unreachable.

```json
{ "stream_allowed": true, "reason": "ok", "tier": "paid",
  "session_token": "<JWT>", "expires_at": "2026-10-17T12:05:00Z",
  "ws_url": "wss://rt.captionly.app/v1/stream", "region": "europe-west4", "asr_region": "eu",
  "period": { "month": "2026-10", "resets_at": "2026-11-01T00:00:00Z" },
  "cap_ms": 36000000, "used_ms": 1200000, "remaining_ms": 34800000,
  "trial_cap_ms": 600000, "trial_remaining_ms": 0,
  "supported_languages": ["af","ar","be","..."], "language_supported": true,
  "warn_remaining_ms": [3600000, 600000], "config_version": 12 }
```

| Field | Semantics |
|---|---|
| `tier` | `paid` (entitled subscription), `trial` (no subscription, device trial left), `free` (neither) |
| `stream_allowed` / `reason` | `ok`, `cap_reached`, `trial_exhausted`, `not_entitled`, `language_unsupported`, `integrity_failed`, `consent_required`, `disabled` (kill switch), `rate_limited`. `session_token` and `ws_url` are present only when allowed |
| `entitlement` (extra object) | `{state, expires_at, product_id, auto_renewing}`. `state` is one of `active`, `grace`, `canceled_active`, `on_hold`, `paused`, `expired`, `none`. Drives the paywall and "renews on" text |
| `cap_ms`, `used_ms`, `remaining_ms` | Paid allowance for the UTC month. Cap from config (36,000,000 ms = 10 h) |
| `trial_*` | Device-lifetime trial. Cap from config (600,000 ms) |
| `ws_url` | Global hostname (routes to nearest healthy region). Config can return a regional hostname for pinning or debugging |
| `supported_languages` | App codes the cloud can stream (config, seeded by the Phase 0 language check). The client uses cloud only if the source language is listed |
| `warn_remaining_ms` | Thresholds at which the client shows a "1 h left" / "10 min left" banner. The server also sends `quota` messages when crossing them |
| `config_version` | Active `service_config.version`. The client re-fetches `/v1/me` when the WebSocket `ready` reports a newer one |
| `consent_required_version` (extra) | The consent version the server needs. Present when `reason=consent_required` |

### 1.7 Session JWT (HS256, `kid` rotation, 5-minute TTL)

| Claim | Meaning |
|---|---|
| `sub`, `sid` | uid, session id |
| `aud` | `captions-ws` |
| `kind` | `paid` or `trial` |
| `allow_ms` | Allowance at issue: `remaining_ms` or `trial_remaining_ms`. The proxy enforces it locally until the first flush |
| `lang` | Source language the stream may use |
| `dev` | Peppered device hash (lease identity and trial key) |
| `asr` | `us` or `eu` |
| `cfgv`, `iat`, `exp` | Config version and token times |

Stream open does not read the DB on the hot path: the proxy verifies the JWT locally and starts the Deepgram connection. The lease acquire runs **in parallel**: one RTT, not awaited before `ready`.

### 1.8 WebSocket protocol (speech only)

Client to server:

| Message | Content |
|---|---|
| `start` (first frame, JSON) | `{type:"start", lang, sample_rate:16000, encoding:"linear16", channels:1, seg_base, client:"android/x.y.z"}` |
| Binary frames | 16-bit little-endian mono PCM at 16 kHz, 20–200 ms each (≤ 6400 bytes; hard limit 64 KB). Only VAD speech frames (speech + 400 ms hangover); silence is never sent |
| `finalize` | `{type:"finalize", seg}`, sent when the client VAD closes a segment. `seg = seg_base + n` for the n-th segment of this stream. Must follow that segment's last audio frame |
| `stop` | `{type:"stop"}`: graceful end. Server finalizes, flushes and closes with 1000 |

Server to client:

| Message | Content |
|---|---|
| `ready` | `{stream_id, region, asr_region, model, lang, remaining_ms, flush_interval_ms, max_stream_ms, config_version}` |
| `partial` | `{seg, text}`: full current hypothesis for the open segment (the server joins Deepgram `is_final` chunks since the last finalize with the latest interim result) |
| `final` | `{seg, text, audio_ms, complete}`. Exactly **one per `finalize`**, possibly with empty text. `complete:false` if Deepgram's `from_finalize` result did not arrive within 1500 ms |
| `quota` | `{kind, used_ms, cap_ms, remaining_ms, resets_at, level}` where `level` is `info`, `warn` or `last`. Sent in `ready`, on threshold crossing, and every 60 s |
| `notice` | `{code, retry_after_s?}` where `code` is `degraded`, `vendor_reconnecting`, `rotate_soon` or `config_changed` |

Segment assignment: the server records the forwarded-audio offset (bytes/32 ms) at each `finalize`. Each Deepgram result is assigned to the segment whose offset range contains the result's `start`, so a result that arrives late never leaks into the next segment.

Vendor connection behaviour:
- The proxy sends Deepgram `KeepAlive` every 5 s while no audio flows.
- It closes the vendor connection after 60 s with no audio and reopens it lazily on the next frame, buffering up to 2 s of audio meanwhile.
- The client WebSocket stays open for up to 15 minutes of idle time.

Deepgram parameters: `model` (per-language config, default `nova-3`), `language`, `encoding=linear16`, `sample_rate=16000`, `channels=1`, `interim_results=true`, `smart_format=true`, `endpointing=300` (tuned in the soak), `mip_opt_out=true` (§13).

| Close code | Meaning | Client action |
|---|---|---|
| 1000 | Normal close after `stop` | none |
| 1001 / 4008 | Rotation (55 ± 3 min), instance shutdown (SIGTERM after final flush), idle 15 min, same-device takeover | Reconnect immediately with a pre-fetched session; on-device recognition covers the gap |
| 4001 | JWT invalid or expired | Get a new session, reconnect once |
| 4002 | Superseded by another device | On-device for the rest of this run; show "Cloud captions moved to another device" |
| 4003 | Not entitled (expired, revoked, token transferred away) | On-device; refresh `/v1/me`; show paywall entry |
| 4004 | Language not supported by cloud | On-device until the source language changes |
| 4029 | Monthly cap or trial exhausted | On-device; show "cap reached, resets <local time>" |
| 4400 | Protocol violation, oversize frame, audio faster than 1.5× real time (token bucket, 5 s burst) | On-device; no retry this run |
| 4429 | Too many stream opens (20 per 10 min per uid) | On-device; retry after `retry_after_s` |
| 4503 | Deepgram unavailable or slow, breaker open, kill switch | On-device; retry with full-jitter backoff 1–60 s, honouring `retry_after_s` |
| 1011 | Internal error, or **metering fail-closed** (60 s unflushed) | On-device; retry with backoff (a new session gets 503 while the DB is down) |

### 1.9 Deepgram adapter and language support

The adapter interface lives server-side in `src/asr/vendor.ts`. It is the only vendor-specific code, and a second vendor would be another implementation:

```ts
interface AsrVendor {
  open(o: { lang: string; model: string; asrRegion: "us"|"eu"; traceId: string }): Promise<VendorStream>;
}
interface VendorStream {
  sendAudio(pcm: Buffer): void; finalize(): void; keepAlive(): void; close(): Promise<void>;
  on(ev: "result", f: (r: { text: string; startMs: number; endMs: number; isFinal: boolean; fromFinalize: boolean }) => void): void;
  on(ev: "error" | "closed", f: (e?: VendorError) => void): void;
}
```

`VendorError.kind` is one of `connect`, `auth`, `rate_limited`, `protocol`, `timeout`, `dropped`, and it drives the breaker (5 failures in 30 s opens it for 30 s).

**Language coverage check.** This is the Phase 0 build step, and it is repeated weekly:
1. A JVM unit test in the app exports `SupportedLanguages.codes` (57 codes; `/home/user/Voice-caption/app/src/main/java/com/voicecaption/settings/SupportedLanguages.kt:25`) to `app/build/outputs/supported-languages.json`.
2. `backend/tools/check-deepgram-languages` fetches Deepgram's model list (`GET /v1/models`, authenticated, which reports languages and a streaming flag per model; verify the shape in Phase 0; fallback is the docs page). It writes `backend/config/deepgram-languages.json` (fetch date, model per language) and a generated gap table `docs/design/deepgram-language-gaps.md`.
3. Per-PR CI test: config `supported_languages` ⊆ (app codes ∩ snapshot). Every listed code has a model mapping.
4. Weekly scheduled job: refresh the snapshot and open a PR on any diff.

Preliminary result from Deepgram's models/languages page (2026-10-17): Nova-3 covers **50 of 57**.

| Gap (on-device only) | Language |
|---|---|
| `cy` | Welsh |
| `gl` | Galician |
| `ht` | Haitian Creole |
| `is` | Icelandic |
| `mt` | Maltese |
| `sq` | Albanian |
| `sw` | Swahili |

Code mapping notes: the app's `zh` maps to Deepgram `zh` (Mandarin, Simplified); `no`, `he`, `tl` and `ms` map one-to-one.

---

## 2. Data model

Metering unit: **milliseconds of audio forwarded to Deepgram**, computed by the server as `bytes / 32` (16 kHz × 16-bit mono). Client-reported durations are never trusted.

### 2.1 Entities

| Table | Key columns / notes |
|---|---|
| `users` | `uid` PK (Firebase anonymous, pseudonymous), `created_at`, `consent_version` (nullable), `consent_at`, `last_device_hash`, `deleted_at`. No PII |
| `subscriptions` | `purchase_token` PK, `uid` FK nullable, `product_id`, `base_plan_id`, `state`, `expiry_time`, `auto_renewing`, `acknowledged`, `linked_purchase_token`, `superseded`, `last_play_fetch_at`, `updated_at` |
| `subscription_transfers` | PK `(purchase_token, transferred_at)`, `from_uid`, `to_uid`, `carried_used_ms`. Kept 90 days |
| `entitlements_v` (view) | Per uid: entitled when a non-superseded subscription has `state IN ('ACTIVE','IN_GRACE_PERIOD')`, or `state='CANCELED' AND expiry_time > now()` |
| `usage_month` | PK `(uid, month)`, where `month` is a `date`, first day UTC. Columns `used_ms` |
| `usage_events` | PK `(stream_id, seq)`, `uid`, `kind` (`paid`/`trial`), `ms`, `region`, `created_at`. Kept 90 days |
| `trial_claims` | `device_hash` PK, `first_uid` (nullable, ON DELETE SET NULL), `used_ms`, `cap_ms`, `integrity_ok_at`, `created_at`. **Never deleted** (disclosed) |
| `stream_leases` | `uid` PK, `stream_id`, `device_hash`, `epoch bigint`, `region`, `acquired_at`, `last_flush_at` |
| `streams` | `stream_id` PK, `uid`, `kind`, `region`, `asr_region`, `lang`, `model`, `opened_at`, `closed_at`, `close_code`, `audio_ms`, `dg_request_id`. **No text, no audio.** Kept 90 days |
| `rate_buckets` | PK `(uid, bucket)` where `bucket` is `session` or `open`. Columns `window_start`, `count` |
| `rtdn_events` | `message_id` PK, `received_at`, `purchase_token_hash`, `notification_type`, `outcome`. Kept 30 days |
| `service_config` | `version` PK, `config jsonb`, `active bool`, `created_at`, `created_by`. Cached 30 s per instance |

`service_config.config` keys:
- `cloud_enabled`, `paid_cap_ms`=36000000, `trial_ms`=600000, `warn_remaining_ms`
- `flush_interval_ms`=10000, `max_unflushed_ms`=60000, `max_stream_ms`=3300000, `vendor_idle_ms`=60000, `client_idle_ms`=900000
- `supported_languages`, `model_by_lang`, `ws_url`, `eu_countries`, `rate_limits`
- `expected_net_price_micros`=4240000, `vendor_rate_usd_per_hour` (for cost metrics)

### 2.2 Invariants enforced in the schema

| Invariant | Constraint |
|---|---|
| One owner per purchase token | `subscriptions.purchase_token` PK; `uid` is a single column |
| Known subscription states only | `CHECK (state IN ('ACTIVE','IN_GRACE_PERIOD','ON_HOLD','PAUSED','CANCELED','EXPIRED','PENDING','PENDING_PURCHASE_CANCELED'))` |
| Each flush counted once | `usage_events` PK `(stream_id, seq)`. `flush_usage` inserts with `ON CONFLICT DO NOTHING` and increments counters **only from the returned rows** |
| Flush sizes are sane | `CHECK (ms > 0 AND ms <= 120000)` |
| Counters never negative | `CHECK (used_ms >= 0)` on `usage_month` and `trial_claims`; `CHECK (cap_ms > 0)` |
| One trial per device | `trial_claims.device_hash` PK |
| One active stream per account | `stream_leases.uid` PK; `epoch` strictly increases (set by `acquire_lease` as `epoch + 1`) |
| One active config | Partial unique index `ON service_config ((true)) WHERE active` |
| Month is a month start | `CHECK (month = date_trunc('month', month))` |
| Deleting a user cascades | FKs from `usage_month`, `usage_events`, `streams`, `stream_leases`, `rate_buckets` use `ON DELETE CASCADE`. `subscriptions.uid` uses `ON DELETE SET NULL`; `trial_claims.first_uid` uses `ON DELETE SET NULL` |

Application guards (not in the schema): consent before cloud use, enforced in `issue_session`. Integrity verdict before a trial is created, enforced in the session handler before `issue_session`.

### 2.3 Schema change from the draft

- Dropped: all translation columns and metering.
- Added: `subscription_transfers`, `rate_buckets`, and `service_config` (replaces `service_flags`).
- Changed: `stream_leases` gains `device_hash` and `epoch`; `streams` gains `asr_region`, `model`, `dg_request_id`.

### 2.4 Queries the design implies (all single round trip)

| Function / query | Caller | Access path |
|---|---|---|
| `issue_session(uid, device_hash, lang, now)`. Reads the user (consent), `entitlements_v`, `usage_month`, `trial_claims`; bumps `rate_buckets`; claims the trial if absent; returns all §1.6 numbers | rt, per session | PK lookups on `users`, `usage_month`, `trial_claims`, `rate_buckets`; `subscriptions` by index `(uid) WHERE NOT superseded` |
| `acquire_lease(uid, stream_id, device_hash, region)`. Upserts the lease with `epoch+1`, bumps the `open` bucket, returns `prev_device_hash`, `entitled`, `rate_ok` | rt, per stream open | PK upsert on `stream_leases`, `rate_buckets` |
| `flush_usage(jsonb[])`. Per instance, every 10 s: insert events, increment `usage_month` (or `trial_claims`), update `streams.audio_ms`; returns per stream `remaining_ms`, `lease_owner`, `entitled`, `cloud_enabled` | rt | PK inserts and updates; `unnest` over the batch |
| `link_purchase(uid, token, play_state)` / RTDN apply | billing | `SELECT … FOR UPDATE` on the `subscriptions` PK, then a transfer count on `subscription_transfers (purchase_token, transferred_at)` |
| Reconcile | billing, daily | `subscriptions WHERE state IN (...) AND (NOT acknowledged OR expiry_time < now() + 1 day)`, index `(state, expiry_time)` |
| Retention purge | billing, daily | Range delete on `created_at` (index) for `usage_events`, `streams`, `rtdn_events`, `subscription_transfers` |

---

## 3. Failure modes

| Area | Failure | Detect | Surface | Recover |
|---|---|---|---|---|
| Session | Invalid or expired ID token | Firebase Admin verify | 401 | Client refreshes the ID token and retries once |
| Session | App Check missing or invalid | App Check verify | 403 `app_check` | Client stays on-device; metric `app_check_reject` |
| Session | Trial integrity verdict fails | Play Integrity decode | 200 `reason=integrity_failed` | No trial; paywall still offered |
| Session | Consent missing or outdated, cap reached, not entitled, kill switch, unsupported language | `issue_session` result | 200 `stream_allowed:false` + reason | UI state (§8.6); no retry loop |
| Session | Primary DB unreachable or slow (over 1.5 s) from this region | `pg` timeout / connect error | 503 `retry_after_s=30` | Outlier detection ejects the region if persistent; client stays on-device |
| Stream open | JWT bad or expired | Local verify | Close 4001 | New session |
| Stream open | Deepgram connect fails or exceeds 2 s; breaker open | Adapter error, breaker state | Close 4503 + `retry_after_s` | Client backoff; breaker half-opens after 30 s |
| Stream open | Lease call fails (DB unreachable) | `acquire_lease` error | Stream continues; unflushed clock starts | Retried with each flush; fail closed at 60 s (below) |
| Stream open | Lease result: not entitled or over rate | `acquire_lease` result | Close 4003 / 4429 | Per close-code table |
| Stream | Deepgram drops mid-stream | `closed` / `error` event | `notice{vendor_reconnecting}` | One silent reconnect (buffer ≤ 2 s); the open segment may return `complete:false`; second failure gives 4503 |
| Stream | `from_finalize` result missing within 1500 ms | Timer | `final{complete:false}` | Client keeps that text, or uses on-device for the segment if empty |
| Stream | Over-rate audio, oversize frame, bad JSON | Token bucket, size check, schema | Close 4400 | No retry this run |
| Stream | Superseded by another device | `flush_usage` returns `lease_owner=false`, other device | Close 4002 | On-device for this run |
| Stream | Same device opened a new stream (rotation or reconnect) | `lease_owner=false`, same `device_hash` | Close 4008 | Client already on the new stream |
| Stream | Allowance reaches 0 between flushes | Local counter (min of JWT `allow_ms` and last flush) | Final flush, then close 4029 | Client shows cap; resets on the 1st, UTC |
| Stream | Cloud Run 60-minute cap | Rotation timer at 55 ± 3 min | `notice{rotate_soon}` at T−10 s, then 4008 | Make-before-break reconnect (same-device takeover) |
| Stream | SIGTERM (deploy, scale-in) | Signal handler | Final flush, close 1001 | Client reconnects immediately |
| Metering | Flush fails (DB error or partition) | `flush_usage` error | Metric `flush_failures`; streams continue | Retry each 10 s with the **same seq** batch; per-stream unflushed ms is bounded at 60 s, then close 1011 (fail closed) |
| Metering | Instance crash | Missing flushes | none | ≤ 10 s unbilled per stream (accepted under-count); lease taken over on reconnect |
| Region | Whole region lost | Load-balancer outlier ejection, uptime check | Existing WebSockets drop | Clients reconnect (new session if JWT expired) to the next region; same-device lease takeover; ≤ 10 s unbilled per stream |
| Region | Primary DB unreachable from **one** region | 503 rate on that region, `primary_rtt` gauge | Session 503 ejects the region; existing streams fail closed at 60 s | Traffic shifts to healthy regions; region returns after a 30 s ejection probe |
| Database | Neon down globally | All regions 503 | Cloud tier off; every client on-device | Neon recovery; PITR if data loss; no client action |
| Purchase | Play API 5xx or timeout | googleapis error | 503 | Client retries link on next app start (`queryPurchasesAsync`) |
| Purchase | Token owned by another uid, ≤ 3 transfers in 30 days | Owner differs | 200 with entitlement | Transfer + usage carry-over in one transaction; old uid's streams get 4003 at next flush |
| Purchase | Same, over the transfer limit | Count ≥ 3 in 30 days | 409 `transfer_limit` | UI: "Subscription active on another install; contact support"; manual support path |
| Purchase | Not acknowledged within 3 days (Play auto-refunds) | `acknowledged=false` | Alert if older than 24 h | Acknowledge at link and in the RTDN handler; daily reconcile retries |
| RTDN | Redelivery, out-of-order | `message_id` PK conflict | 200 (dedupe) | Handler always re-fetches the current state from Play; the notification type is only a hint |
| RTDN | Play API failure | Error | Return 500 | Pub/Sub retries with backoff; DLQ after 10 attempts; daily reconcile is the safety net |
| RTDN | Billing region (us-east4) down | Pub/Sub delivery failures | DLQ age alert | Pub/Sub retains 7 days; processed on recovery; entitlement changes are delayed, never lost |
| Cost | Runaway usage or vendor price change | Daily cost alert, cost per hour over 1.3× baseline | Ops alert | Set `cloud_enabled=false` (applies in ≤ 30 s; open streams close 4503); max-instances cap; lower `paid_cap_ms` |
| Config | Bad config version activated | Schema validation on load | Instance keeps the last valid config; alert | Re-activate the previous version |

---

## 4. Authorization

| Entry | Principal | Scope | Enforcement point |
|---|---|---|---|
| User HTTP endpoints | Firebase anonymous user: ID token + App Check token (Play Integrity provider) | Only the caller's uid. `uid` comes only from the verified token; every repository function and SQL function takes `uid` as a required argument | Fastify `preHandler` hook (ID token + App Check), then uid-scoped repository functions |
| Trial claim | Same, plus a **Play Integrity standard request** with `requestHash = SHA-256(uid ‖ device_hash ‖ server nonce)` | Required verdict: `appRecognitionVerdict=PLAY_RECOGNIZED`, `MEETS_DEVICE_INTEGRITY`, `appLicensingVerdict=LICENSED` | Session handler decodes the token via the Play Integrity API once per device, then sets `trial_claims.integrity_ok_at` |
| Purchase link | Same, App Check **limited-use** token (replay protected) | Only tokens whose Play `obfuscatedExternalAccountId` equals SHA-256(uid); a mismatch goes down the transfer path | Billing handler |
| `/v1/stream` | Session JWT (HS256, `kid`, `aud=captions-ws`, 5-minute TTL) | One uid, one language, one `asr_region`, `allow_ms` | Verified before the upgrade; lease and entitlement re-checked at each flush |
| RTDN webhook | Pub/Sub push service account | Subscription rows only | OIDC verify: exact audience URL, expected SA email, `packageName` check |
| Reconcile / retention | Cloud Scheduler service account | Batch jobs | OIDC verify |
| Outbound to Deepgram / Play / Firebase | Runtime service accounts; Deepgram key from Secret Manager | — | Workload identity; keys never sent to clients or logged |
| Config changes | Operators via a migration-style SQL script, reviewed in a PR | `service_config` | Database role `config_writer`; the app role cannot insert into `service_config` |

Limits:

| Limit | Value |
|---|---|
| Active streams per uid | 1, newest wins |
| Stream duration | 55 ± 3 min |
| Frame size | ≤ 64 KB |
| Audio rate | ≤ 1.5× real time |
| Client idle | 15 min |
| Session issues | 60 per hour per uid |
| Stream opens | 20 per 10 min per uid |
| Per-IP throttle | Cloud Armor, 120 req/min |
| Paid cap | 10 h per UTC month (config) |
| Trial | 10 min lifetime per device (config) |
| Kill switch | Global |

**App Check must be enforced, not only monitored, before launch** (Firebase console enforcement plus server-side verify).

---

## 5. Idempotence and concurrency

- **Replay safety.** A flush retried with the same `(stream_id, seq)` is a no-op. A purchase link and RTDN processing for the same token converge on the state Play returns. Session issue is safe to repeat: trial claim is `INSERT … ON CONFLICT DO NOTHING`.
- **Concurrency model:**

| Contention | Mechanism |
|---|---|
| Stream ownership | `stream_leases` row per uid, upserted by `acquire_lease` with `epoch = epoch + 1`. The last commit wins. Owners check `stream_id` at every flush |
| Subscription row (link vs RTDN vs reconcile) | `SELECT … FOR UPDATE` on the `purchase_token` row inside one function call, then write the state fetched from Play. The Play fetch happens **before** the lock, and the write is guarded by `updated_at` (compare-and-set) to avoid holding a lock across HTTP |
| Monthly counter | `UPDATE usage_month SET used_ms = used_ms + $ms` (row lock implicit, single statement) |
| Config | Immutable versions; flipping `active` is one transaction |

- **Idempotency key contract:** `(stream_id, seq)` for flushes is required and server-generated. Purchase link is keyed by the purchase token. RTDN is keyed by the Pub/Sub `message_id`. No client-supplied idempotency keys are needed.
- **Cross-store writes:** no outbox. Postgres is the only store. Acknowledgement to Play uses the DB row (`acknowledged=false`) as durable intent, and the reconcile cron as the relay.

### 5.1 Cross-region lease race (bounded double usage)

| Scenario | What happens | Bound |
|---|---|---|
| Device A (EU) streaming; device B (US) opens | B's `acquire_lease` commits with epoch n+1. A learns at its next flush (EU→US RTT ~90 ms) and closes with 4002 | Overlap ≤ flush interval + RTT ≈ **10.1 s** (EU) / 10.25 s (Asia) |
| A and B open within milliseconds in different regions | Both lease upserts serialize on the uid row; the later commit wins; the loser closes at its first flush | Same bound |
| Ping-pong abuse (two devices alternately reopening) | Each takeover gives ≤ 10 s overlap; opens are capped at 20 per 10 min | ≤ ~200 s of overlap per 10 min |
| Lease call cannot reach the DB | Both may stream until the fail-closed timer | ≤ **60 s** per stream |
| Overshoot past the cap | Each stream spends locally from `allow_ms` until a flush returns the real remaining | ≤ 10 s × concurrent streams of that uid (normally 1) |

All overlapping audio is **metered to the same uid counter**. So a race can cost at most the overlap above the cap, never unbounded vendor spend. Shortening the bound would need a cross-region push channel. That is rejected: a 5 s flush halves it at twice the DB writes, and remains a config option.

### 5.2 Primary DB unreachable from a region

1. New sessions get 503 (`retry_after_s=30`). The client stays on-device.
2. A stream opened with a still-valid JWT is accepted. Its lease and flushes retry every 10 s.
3. Existing streams continue on their local allowance. Unflushed ms per stream is held in memory and capped at `max_unflushed_ms` = 60 s. At 60 s the stream closes with 1011 and the client falls back.
4. If the database returns within 60 s, every buffered flush lands, because sequence numbers are idempotent. Nothing is lost.
5. If the instance dies while unflushed, ≤ 60 s per stream is unbilled. This is an accepted under-count, alerted via `unflushed_ms_max`.

### 5.3 Reconnect storms and rotation

- Client: full-jitter backoff (1 s to 60 s) that honours `retry_after_s`.
- Server: per-instance accept bucket (50 upgrades per second).
- Rotation is randomized ±3 min. SIGTERM does a final flush, then 1001.
- Make-before-break rotation: the client opens the new stream with a pre-fetched JWT, and the server closes the old one with 4008 (same device).

---

## 6. Price, cap and margin

Inputs:
- Price $4.99. Google Play takes 15% on subscriptions, so net is **$4.24** where price is tax-exclusive (e.g. US).
- With 20% VAT included in the price: net **$3.53**.
- Deepgram Nova-3 **monolingual** streaming (we always pass an explicit language), from deepgram.com/pricing on 2026-10-17:
  - $0.0048/min pay-as-you-go **promotional** ($0.288/h), with no end date published
  - $0.0077/min regular pay-as-you-go ($0.462/h)
  - $0.0065/min regular Growth ($0.39/h)
  - Multilingual (not used) is $0.0058 promo / $0.0092 regular
- The owner's planning range of $0.35–0.55/h is bracketed below.

| Audio hours used per month | Vendor cost @ $0.288/h (promo) | @ $0.35/h | @ $0.462/h (regular PAYG) | @ $0.55/h |
|---|---|---|---|---|
| 3 h | $0.86, margin $3.38 | $1.05, $3.19 | $1.39, $2.85 | $1.65, $2.59 |
| 6 h | $1.73, $2.51 | $2.10, $2.14 | $2.77, $1.47 | $3.30, $0.94 |
| **10 h (cap)** | **$2.88, $1.36 (32%)** | **$3.50, $0.74 (17%)** | **$4.62, −$0.38** | **$5.50, −$1.26** |
| Break-even hours (net $4.24) | 14.7 h | 12.1 h | 9.2 h | 7.7 h |
| Break-even hours (net $3.53, VAT-inclusive) | 12.3 h | 10.1 h | 7.6 h | 6.4 h |

Fixed cost (estimate): 3 regions × min-instance 1 always-on (about $50 each) + load balancer about $20 + Neon about $20–70. That is **about $200–250/month**, covered at about 60–100 subscribers with typical (3–6 h) use.

Margin notes:
- Metered audio includes the 400 ms VAD hangover per segment. This is real vendor cost, and the cap counts it.
- The margin risk sits entirely in heavy users at regular rates. Levers, in order: Growth plan pricing, lowering `paid_cap_ms` (config), raising the Play price.
- The soak (Phase 13) measures real cost per audio hour by comparing Deepgram's usage API with our metered ms. Alert if the ratio exceeds 1.05.

---

## 7. Observability

**Logs** (structured JSON, one line per boundary event, **field allowlist enforced by a CI test**):

| Boundary | Level | Fields |
|---|---|---|
| HTTP request end | info (warn ≥ 4xx, error 5xx) | `ts, trace_id, span_id, service, region, route, status, latency_ms, uid_h, reason, config_version` |
| Stream open / ready / close | info | `stream_id, uid_h, region, asr_region, lang, model, kind, close_code, audio_ms, duration_ms, dg_request_id, ready_ms` |
| Flush | debug (warn on failure) | `region, batch_size, flush_ms, result, unflushed_ms_max` |
| Vendor error | warn | `stream_id, vendor_error_kind, dg_request_id, breaker_state` |
| Billing / RTDN | info | `purchase_token_h, rtdn_type, play_state, outcome, transfer_count` |

`uid_h` and `purchase_token_h` are the first 12 hex digits of SHA-256. Never logged: audio, partial/final text, JWTs, ID/App Check/Integrity tokens, full purchase tokens, Deepgram key, device hash. Cloud Run request logs contain client IPs; their bucket retention is set to 30 days, as disclosed in the privacy policy.

**Metrics** (OpenTelemetry to Cloud Monitoring; every series labelled `region`, and `asr_region` where relevant):

| Group | Metrics |
|---|---|
| HTTP RED | Rate, errors, duration per route |
| WebSocket | `active_streams`, `stream_opens`, `stream_closes{code}`, `stream_duration`, `ready_latency` |
| Vendor | `dg_connect_latency`, `dg_first_partial_latency`, `dg_finalize_latency`, `dg_errors{kind}`, `breaker_open`, `dg_concurrency` vs plan limit |
| Metering | `audio_ms_forwarded{kind}`, `flush_latency`, `flush_failures`, `unflushed_ms_max` (gauge), `fail_closed_closes` |
| Database | `primary_rtt_ms` (per-region probe every 10 s), pool waiters |
| Leases | `lease_takeovers{same_device, other_device, cross_region}` |
| Cost | `est_vendor_cost_usd` = hours × config rate; daily `dg_billed_vs_metered_ratio` |
| Billing | RTDN outcomes, DLQ depth and age, unacknowledged purchases older than 24 h, transfers, 409s |
| Product | Sessions with `language_unsupported` by language; `reason` distribution |

**Alerts:**
- 5xx over 2% in any region
- 4503 share over 10%
- `dg_finalize_latency` p95 over 1 s, or end-to-end final lag p95 over 2 s
- `primary_rtt_ms` p95 over 2× baseline
- `unflushed_ms_max` over 30 s
- Any `fail_closed_closes` spike
- Daily cost over budget; cost per hour over 1.3× baseline; billed/metered ratio over 1.05
- `dg_concurrency` over 80% of the plan limit
- DLQ depth above 0
- Unacknowledged purchases older than 24 h

**Healthcheck:**
- `/healthz` is process-only (no dependencies, so no restart loops).
- `/readyz` checks config, DB (cached 10 s) and breaker state. It is used by uptime checks, not by the load balancer.

**Trace propagation:**
- Inbound: W3C `traceparent` from the client's OkHttp interceptor on HTTP and on the WebSocket upgrade.
- The stream is one span keyed by `stream_id`.
- Outbound: child spans for Neon statements, the Play API and Firebase. Deepgram's `dg-request-id` is recorded on the stream span for vendor support.

---

## 8. Android client integration seam

All references are to `/home/user/Voice-caption/app/src/main/java/com/voicecaption/`.

### 8.1 The interface problem

`SpeechRecognizer.transcribe(segment, language): String` (`asr/SpeechRecognizer.kt:8`) is segment-based. `CaptionPipeline` calls it once per VAD segment, in order, on one worker (`pipeline/CaptionPipeline.kt:143-146`). A streaming recognizer must receive audio **while** the segment is still being spoken, or partials (the reason to pay) are impossible.

**Chosen: an additive sub-interface.** The segment contract is kept as the "await the final" call. Alternative rejected: replacing the interface with a frame-level `Flow` API, which would rewrite the pipeline, its tests and WhisperLocal.

```kotlin
interface StreamingSpeechRecognizer : SpeechRecognizer {
    fun onSpeechFrame(frame: PcmFrame)           // audio thread, non-blocking (bounded FIFO)
    fun onSegmentEnd(segment: SpeechSegment)     // audio thread, non-blocking: queues `finalize`
    val partials: SharedFlow<Partial>            // Partial(segmentIndex: Long, text: String)
}
// transcribe(segment, lang) = await the `final` for that segment; segment.samples are used only by the fallback.
```

Changes:

| File | Change | Size |
|---|---|---|
| `vad/VadSegmenter.kt` | Add a read-only `lastFrameIncluded: Boolean`: true when the last `push` appended the frame to a segment buffer | ~5 lines |
| `pipeline/CaptionPipeline.kt` `onFrame` (lines 112-117) | If the recognizer is streaming: after `push`, call `onSpeechFrame(frame)` when `lastFrameIncluded`, then `onSegmentEnd(seg)` for each emitted segment, **then** `enqueue`. Same in `stop()` for the flushed tail. All under the existing `lock`, so frame, finalize and enqueue order is preserved | ~10 lines |
| `CaptionPipeline` | A collector of `partials`. `segmentIndex` equals the caption id, because ids are assigned 1:1 in `enqueue` order and `onSegmentEnd` is called 1:1 just before. Apply `onSourceText(id, text)` only when `id == nextId` (the still-open segment); late partials for emitted segments are dropped, so they cannot overwrite a final | ~15 lines |
| `caption/CaptionStabilizer.kt` | **No change.** Partials go through `onSourceText` (refresh in place). The final goes through the existing worker path: `onSourceText`, then the ML Kit translation, then `onTranslated` (marks final). `CaptionFormatter` already shows source text until a translation exists | 0 |
| `translate/*` | No change. ML Kit translates finals only; partials are shown in the source language | 0 |

Channel drops (`DROP_OLDEST`, capacity 4) still work. A dropped job's segment was already finalized on the server; its final is ignored, and later ids stay aligned.

### 8.2 New client components

| Component | Responsibility |
|---|---|
| `asr/cloud/CloudSpeechRecognizer : StreamingSpeechRecognizer` | OkHttp WebSocket. Converts float frames to int16 LE and batches 3 frames (96 ms). Single outbound FIFO for audio and `finalize`. Maps `final{seg}` to pending `transcribe` awaits (timeout 2.5 s). `seg_base` = next segment index, so ids survive reconnects. Fetches a fresh session JWT itself and pre-fetches one 60 s before rotation. On a language change it reopens the stream with the new `lang` |
| `asr/FallbackSpeechRecognizer : StreamingSpeechRecognizer` | Wraps cloud + `WhisperLocal`. State machine: `CLOUD` → `LOCAL_RETRY(backoff)` → `CLOUD`, or `LOCAL_FINAL(reason)` for 4002/4003/4004/4029/4400. **Any** cloud failure on a segment (timeout, close, empty `complete:false`) is re-done with `WhisperLocal.transcribe(segment.samples)`, so no caption is lost. Exposes `cloudState: StateFlow<CloudRunState>` for the overlay and UI. Cloud errors are absorbed, so `RecognizerFailureTracker` (`service/RecognizerFailureTracker.kt`) still counts only on-device failures |
| `cloud/CloudApi` | OkHttp + kotlinx.serialization client for §1.5, with ID token + App Check headers and a `traceparent` interceptor |
| `cloud/CloudAuth` | Firebase anonymous auth; App Check with `PlayIntegrityAppCheckProviderFactory` (debug provider in debug builds); limited-use tokens for trial claim and purchase link; Play Integrity standard request for the trial |
| `cloud/DeviceId` | `SHA-256("captionly:" + ANDROID_ID)` |
| `cloud/CloudStatusRepository` | Caches the last `/v1/me` or session body as `StateFlow<CloudStatus>`. Refreshes on app foreground, after a purchase, and on `config_changed` |
| `cloud/CloudPreferences` (separate DataStore) | `cloud_enabled_by_user`, `cloud_consent_version`, `cloud_consent_at`. `CaptionSettings` stays untouched |
| `billing/BillingRepository` | Play Billing Library (current major version; verify v8): product `captionly_cloud_monthly`, `enablePendingPurchases`, `launchBillingFlow` with `setObfuscatedAccountId(SHA-256(uid))`. `PurchasesUpdatedListener` → `POST /v1/purchases/link`. `queryPurchasesAsync(SUBS)` on every app start relinks (the reinstall/transfer path). **No client-side acknowledge** (the server acknowledges). "Manage subscription" deep-links to the Play subscription center |

### 8.3 Wiring in `CaptureService`

- `service/ServiceDependencies.kt` gets a hook `cloud: () -> CloudRunConfig?`, following the existing `settings` hook pattern. It returns non-null only when all of these hold: the user enabled cloud, consent version is current, the cached status (≤ 10 minutes old) says `stream_allowed`, and the source language is in `supported_languages`.
- `begin()` (`service/CaptureService.kt:158-226`) stays synchronous. When the hook is non-null it builds `VadSegmenter(vad, VadConfig(maxSegmentMs = 15_000, overlapMs = 0))` and `FallbackSpeechRecognizer(CloudSpeechRecognizer(...), whisper)`. Overlap is useless when frames are streamed once; longer segments reduce mid-sentence finalizes. Otherwise today's path is used unchanged.
- `StartupCheck` keeps requiring an installed Whisper model, because it is the fallback.
- The cloud recognizer connects lazily on the first speech frame. Until `ready`, segments go to Whisper.
- Teardown: `closeNativeOffMain` also closes the cloud recognizer (sends `stop`, closes 1000; non-blocking).
- Overlay: a small "Cloud" / "On-device" badge driven by `cloudState`. Notices (cap reached, moved to another device, cloud unavailable) use the existing `showOneTimeMessage` / `OverlayRenderer.showMessage`.

### 8.4 Language decision on the client

| Situation | Behaviour |
|---|---|
| Source language not in `supported_languages` | On-device for this run. Home card: "Cloud captions are not available for <language>; using on-device" |
| Language changed mid-run to a supported language | Reopen the stream with the new `lang`. Segments recorded under the old stream use on-device |
| Language changed mid-run to an unsupported one | `LOCAL_FINAL(language)` until the language changes back |
| Server says 4004 (config changed) | Same as unsupported; refresh status |

### 8.5 Consent, storage and privacy copy

Consent screen, shown before the first cloud use (from the trial button or the paywall):
- What is sent: the speech portions of audio played by other apps, the chosen language, a pseudonymous account id.
- To whom: Captionly servers (US/EU) and Deepgram as processor. EU users' audio is processed in the EU.
- What is not stored: audio and transcripts are not stored by Captionly; vendor retention is per contract.
- Translation stays on the phone.
- Withdraw anytime in Settings; account deletion is available.

Accepting writes `CloudPreferences` and `POST /v1/consent {version}`. Withdrawing writes both and calls `DELETE /v1/consent`. The consent version is bumped whenever this copy changes.

README "Privacy notes":
- Keep the free-tier bullets.
- Replace "A cloud tier is planned…" with a **Cloud tier (optional, paid or trial)** subsection: audio leaves the device only after explicit consent and only while cloud captions are on; only VAD speech is sent; no audio or text is stored server-side; Deepgram is the processor; EU audio goes to Deepgram's EU endpoint; usage counters and a pseudonymous id are stored in the US; on-device fallback whenever cloud is off or unavailable.

### 8.6 Entitlement and paywall states in the UI

| `CloudStatus` | Home card | Action |
|---|---|---|
| `NotConsented` | "Faster, more accurate captions with Cloud" | Consent screen |
| `TrialAvailable(remaining)` | "Try Cloud free: 10 min" | Enable |
| `TrialExhausted` / `NotEntitled` | Paywall teaser with Play's localized price | Paywall → `launchBillingFlow` |
| `Active(remaining, cap, resetsAt, renewsAt)` | "Cloud: 7 h 40 m left this month" + warning at thresholds | Manage subscription |
| `CapReached(resetsAt)` | "Monthly cloud limit reached; resets <local date/time>" | none |
| `OnHold` / `Grace` | "Payment issue; fix in Google Play" | Deep link |
| `LanguageUnsupported(lang)` | "Not available for <language>" | none |
| `Unavailable` (503, offline, disabled) | "Cloud unavailable; using on-device" | Auto-retry |
| `TransferBlocked` (409) | "Subscription in use on another install" | Support link |

---

## 9. Build sequence

Each phase ends with its tests green in CI and its exit criterion met.

CI infrastructure:
- A **fake Deepgram WebSocket server** (`backend/test/fake-deepgram`): scripted interim/final/`from_finalize` results, KeepAlive handling, 10 s no-audio timeout, mid-stream drops, slow responses, auth errors.
- A **per-run Neon branch** created from `main` via the Neon API at job start and deleted at job end.

| # | Phase | Tests | Exit criterion |
|---|---|---|---|
| 0 | **Language check + vendor spike tooling.** `check-deepgram-languages`, app export test, replay harness (JVM CLI that streams film clips in real time over the §1.8 protocol and records latency per hop) | Snapshot diff test; harness self-test against the fake vendor | Gap table generated; `supported_languages` seed committed |
| 1 | **Skeleton:** Fastify + ws, allowlisted logger, `/healthz` `/readyz`, config loader with schema validation, deploy `captions-rt` to us-east4 | Logger allowlist test (forbidden fields fail CI); config schema tests | Deployed; CI green |
| 2 | **Schema + SQL functions** (`issue_session`, `acquire_lease`, `flush_usage`, `link_purchase`), migrations, uid-scoped repositories | Constraint tests for every §2.2 invariant; flush retry idempotence; month rollover; lease epoch ordering; transfer count window; usage carry-over; cascade delete; trial kept after delete | Migrations apply on a fresh branch and on a prod-snapshot branch |
| 3 | **Auth, consent, session:** Firebase ID token, App Check, Play Integrity trial claim (fakes in CI), JWT issue/verify, `kid` rotation, rate buckets, kill switch | JSON-schema contract test of §1.6; tier × reason matrix test; JWT claim tests; 401/403/429/503 paths | Session p95 < 50 ms server time in us-east4 against Neon |
| 4 | **Deepgram adapter + fake server** | Adapter contract suite run against the fake (per PR) and against real Deepgram (nightly, 1 min of audio) | Same suite passes on both |
| 5 | **WebSocket proxy (no metering):** protocol, segment assignment by offset, finalize/final 1:1, vendor KeepAlive and lazy reopen, rotation, idle, over-rate, breaker | One test per close-code row; out-of-order and late Deepgram result tests; exactly one `final` per `finalize` property test | Every close code exercised in CI |
| 6 | **Metering, leases, caps, trial, fail-closed** | Batched flush; retry same seq; fault injection (toxiproxy between proxy and Neon branch) gives 1011 at 60 ± 5 s; cap crossing gives 4029 within 100 ms of local allowance; same-device takeover gives 4008 vs other device 4002; month rollover mid-stream | Metered ms within ±0.5% of bytes/32; no double count under retries |
| 7 | **Billing service:** link, verify, acknowledge, transfer + 409, RTDN, reconcile, deletion | Play API fake state matrix; RTDN dedupe and out-of-order; ack retry; transfer limit; staging test with a license-tester purchase and a Play Console test notification | Tester purchase entitled in < 10 s; test notification processed exactly once |
| 8 | **Rate limits, Cloud Armor, cost controls** | Limit tests; kill switch closes open streams ≤ 30 s | Kill-switch drill passes in staging |
| 9 | **Multi-region:** global load balancer, serverless NEGs in 3 regions, outlier detection, `X-Client-Region`, `asr_region` selection, billing path routing, dashboards and alerts | Synthetic probes from 3 continents; residency test (EU country served by us-east4 still uses the EU host); alert drills | Per-region dashboards live; every alert fired once in a drill |
| 10 | **Load + chaos** (staging, fake vendor) | 500 concurrent streams; reconnect storm (500 within 10 s); **region loss** (remove a region's NEG mid-run); **primary DB unreachable from one region** (egress firewall deny from that region's Direct VPC egress to Neon); Neon compute restart; vendor outage | No 5xx outside the faulted region; fail-closed at 60 ± 5 s; lease overlap ≤ 11 s; unbilled ≤ 10 s per stream on region loss; clients reconnect in < 5 s p95 |
| 11 | **Privacy + retention:** deletion end-to-end, retention jobs, log audit | Deletion test (rows gone, trial kept, Firebase user deleted); retention job tests; one-week log sample scan for forbidden fields | Zero forbidden fields found |
| 12 | **Android integration** (Plan 5, §10) | See Plan 5 | See Plan 5 |
| 13 | **Staging soak with real Deepgram** (2 weeks, ≥ 200 audio hours of film from clients in US, EU, Asia; includes the asia-region probe and the EU endpoint) | Cost per audio hour (Deepgram usage API vs metered); latency per hop (§1.3); WER cloud vs Whisper on the bench set (`bench/WordErrorRate.kt`); finalize quality with `endpointing` variants | Cap/price config set; keep/cut decision for asia recorded; latency targets met or the cut order applied |

---

## 10. Plan for implementation

| Plan | Contents (phases) | Depends on | Independently shippable result |
|---|---|---|---|
| **Slice 0: vendor and latency proof** (smallest first slice, about 1 week) | Phase 0, plus a minimal Phase 4/5 subset: Deepgram adapter + proxy with a static dev JWT, no DB, deployed to us-east4, europe-west4 and asia-southeast1 behind the global load balancer; replay harness run from 3 continents | none | Measured partial/final latency per region, real cost per audio hour (Deepgram usage vs bytes/32), WebSocket-through-load-balancer validated, verified language gaps. **Gate:** final p95 ≤ 1.3 s US/EU and cost ≤ $0.46/h; otherwise revisit cap/price before building more |
| **Plan 1: Backend core + DB** | Phases 1, 2, 3, 11 (deletion endpoint stub until Plan 2) | Slice 0 | Deployed session service with consent, trial, config, JWT; `/v1/me` usable by the app |
| **Plan 2: Realtime proxy + metering + Deepgram adapter** | Phases 4, 5, 6, 8 | Plan 1 | Metered, leased, capped streaming in one region |
| **Plan 3: Play billing service** | Phase 7 | Plan 1 (runs in parallel with Plan 2) | Subscriptions entitle accounts; RTDN and reconcile live |
| **Plan 4: Regional deployment + observability + chaos + soak** | Phases 9, 10, 13 | Plans 2 and 3 | Global deployment, alerts, chaos results, final cap/price |
| **Plan 5: Android client integration** | E1: `StreamingSpeechRecognizer`, `VadSegmenter.lastFrameIncluded`, pipeline seam, with JVM tests using a fake streaming recognizer (ordering, late-partial drop, channel-drop alignment). E2: `CloudApi`, `CloudAuth`, App Check, `DeviceId`, consent screen + `CloudPreferences`. E3: `CloudSpeechRecognizer` + `FallbackSpeechRecognizer`, tested against an OkHttp MockWebServer WebSocket implementing §1.8 (every close code → expected state). E4: `BillingRepository`, paywall, entitlement card, with Play Billing test cards on internal track. E5: README / privacy copy / data-safety form; instrumented test on a mid-range device (fallback switch within one segment, no caption loss) | E1 none; E2 Plan 1; E3 Plan 2 contract (can start against the mock); E4 Plan 3 | E1 ships with no user-visible change; E3+ behind a remote flag |

Order: **Slice 0** → Plan 1 → (Plan 2 ∥ Plan 3 ∥ Plan 5 E1–E2) → Plan 5 E3–E4 → Plan 4 (soak includes real clients) → Plan 5 E5 → launch, with App Check enforced.

---

## 11. What this does NOT include

- Translation in the cloud: no translation proxy, vendor, character caps or partial-translation policy. ML Kit on-device only.
- A second ASR vendor. Only the adapter interface exists.
- Consumable packs or one-time purchases.
- Per-region data stores, counters or read replicas. One primary Neon.
- A Deepgram endpoint in Asia (none documented). Pre-warmed vendor connection pools.
- Account linking (Google sign-in), web client, iOS client.
- Source-language auto-detection and Deepgram `multi`.
- Server-side storage of audio or transcripts for any purpose, including debugging.

---

## 12. Risks

- **Promotional Deepgram pricing ends** (no end date published). Regular pay-as-you-go makes cap-maxing users unprofitable (§6). Mitigated by the cap in config and the Growth plan.
- **Deepgram billing semantics:** whether streaming is billed by audio sent or by connection time, whether KeepAlive is free, and any per-connection minimum. All are measured in Slice 0. If wall-clock billing applies, the 60 s vendor-idle close becomes essential and may need to be shorter.
- **Finalize quality:** client-driven `Finalize` at VAD boundaries can cut words when forced 15 s cuts occur. Tuned in the soak (`endpointing`, `maxSegmentMs`).
- **Neon is a single point of failure** for the cloud tier. Acceptable because the client falls back on-device; PITR protects the data.
- **Cloud Run 60-minute cap:** relies on make-before-break rotation working on real devices.
- **Global load balancer behaviour for WebSockets** (backend timeout, outlier ejection with serverless NEGs) must be validated in Slice 0 and Phase 10.
- **Asia latency:** may not meet targets. Cut order in §1.4.
- **ANDROID_ID-based trial key** is defeated by factory reset. Bounded by the Play Integrity requirement.
- **Untranslated partials** may confuse users. Partials are rendered dimmed; translating throttled partials on-device is a later option.
- **Play policy review** for audio capture plus a cloud upload path. Allow review time; consent and data-safety wording must match behaviour exactly.
- **Deepgram concurrency limits** on our plan could cap launch traffic. Alerted at 80%.

---

## 13. Residual open items for the owner

| # | Item | Recommendation |
|---|---|---|
| 1 | Verify Deepgram streaming prices (promo vs regular, Growth terms) and billing semantics | Do it in Slice 0 with real usage-API numbers; start on pay-as-you-go; move to Growth once monthly vendor spend justifies the prepayment |
| 2 | Verify the language list and streaming support per language (preliminary gaps: cy, gl, ht, is, mt, sq, sw) | Accept the Phase 0 output as the source of truth; name supported languages on the paywall |
| 3 | Deepgram EU endpoint: Nova-3 streaming availability, price parity, host location; whether an Asia endpoint exists | Confirm with Deepgram sales before Plan 4; choose the europe-west region nearest the EU host |
| 4 | Deepgram data retention and Model Improvement Program | Send `mip_opt_out=true` on every request, confirm any price impact, and get no-retention / no-training terms in the DPA before launch. The privacy promise depends on it |
| 5 | Play Console: subscription `captionly_cloud_monthly` with a monthly base plan at $4.99, grace period, account hold, RTDN topic + push subscription per environment, service-account permissions, license testers | Set up in Plan 3, week 1; verify with a test notification |
| 6 | Privacy policy and Play data-safety wording for cloud audio | Declare "Audio: other audio files / sound recordings" as collected, **processed ephemerally**, not shared (Deepgram is a service provider), optional (consent-gated), with deletion available. Also declare the pseudonymous id and usage data. Get a legal review |
| 7 | App Check enforcement | Monitor for 1 week in staging/internal track, then **enforce before public launch**; never launch in monitor-only mode |
| 8 | Neon primary region and plan | aws-us-east-1, a paid plan with autosuspend off and 7-day PITR. Revisit only if most users turn out to be in the EU |
| 9 | Trial device identity | Launch with the hashed ANDROID_ID + Integrity verdict; evaluate Play Integrity device recall once it is generally available |
| 10 | VAT-inclusive markets reduce net to about $3.53 | Keep one global price at launch; review regional pricing in Play after 60 days of margin data |
| 11 | Account recovery beyond token transfer | Offer Google sign-in linking in v1.1 to reduce transfers and 409s |
