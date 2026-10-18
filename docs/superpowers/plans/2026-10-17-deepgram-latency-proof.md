# Deepgram Latency and Cost Proof (Slice 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Tasks 1-9 are code tasks agents can complete and test locally. Task 10 is **OPERATOR-RUN**: it needs the owner's Google Cloud project, Deepgram key and machines on three continents; agents write the scripts and runbook only and must not try to run them.

**Goal:** Prove, with real measurements, that Deepgram streaming behind a thin WebSocket proxy meets the latency and cost gate before any further paid-tier backend is built.

**Architecture:** A small Node/TypeScript service (`backend/`) implements only the speech-only WebSocket protocol of the blueprint: a Deepgram adapter behind an `AsrVendor` interface, a proxy that assigns vendor results to client segments, a static development JWT, no database and no metering enforcement. A replay harness streams clips in real time over the same protocol and records per-hop latency. A fake Deepgram server makes everything testable in CI; the real runs happen in Task 10.

**Tech Stack:** Node 22, TypeScript (strict), Fastify, `ws`, `jose`, Vitest, ESLint, `tsx`; Docker; Google Cloud Run, global external Application Load Balancer, Secret Manager (scripts only).

**Spec:** `docs/design/2026-10-17-paid-tier-backend-design.md` (sections 1.1-1.3, 1.7-1.9, 6, 9 phases 0/4/5, 10 "Slice 0"). Read it before starting; this plan only restates the values tasks need.

## Global Constraints

- Runtime: Node 22 + TypeScript, Fastify (HTTP) + `ws` (WebSocket), `jose` (JWT). Code lives under `backend/`; Android code is not touched except Task 7's export test.
- Dev session token: HS256 JWT with `aud` = `captions-ws`, claims `sub`, `sid`, `kind` (`paid`|`trial`), `allow_ms`, `lang`, `dev`, `asr` (`us`|`eu`), `cfgv`, `iat`, `exp`. Verified locally at stream open; no DB.
- Deepgram connection parameters: `model` default `nova-3`, `language`, `encoding=linear16`, `sample_rate=16000`, `channels=1`, `interim_results=true`, `smart_format=true`, `endpointing=300`, `mip_opt_out=true`. Hosts: `wss://api.deepgram.com/v1/listen` (us), `wss://api.eu.deepgram.com/v1/listen` (eu). Vendor messages: `KeepAlive`, `Finalize`, `CloseStream`; result fields `is_final`, `from_finalize`, `start`, `duration`.
- Client protocol (speech only): `start {type,lang,sample_rate:16000,encoding:"linear16",channels:1,seg_base,client}`; binary frames of 16-bit LE mono PCM at 16 kHz, 20-200 ms each (recommended ≤ 6400 bytes), **hard limit 64 KB** (larger gives close 4400); `finalize {type,seg}`; `stop {type}`. Server: `ready {stream_id,region,asr_region,model,lang,remaining_ms,flush_interval_ms,max_stream_ms,config_version}`, `partial {seg,text}`, `final {seg,text,audio_ms,complete}` (exactly one per `finalize`, possibly empty text, `complete:false` if the vendor's `from_finalize` result is missing after **1500 ms**).
- Metering unit: forwarded audio ms = bytes / 32 (16 kHz x 2 bytes). Slice 0 counts and logs it; it does not enforce caps.
- Vendor behaviour: send `KeepAlive` every 5 s while no audio flows; close the vendor connection after 60 s without audio and reopen lazily on the next frame, buffering at most 2 s of audio meanwhile.
- Close codes used in this slice: 1000 normal after `stop`; 4001 invalid/expired JWT; 4004 language not supported; 4400 protocol violation, frame > 64 KB, or audio faster than 1.5x real time (token bucket, 5 s burst); 4503 vendor unavailable/slow.
- Privacy: **no audio, transcript text, JWTs, API keys or purchase data in logs, ever.** Logs use an allowlist of field names; a non-allowlisted field fails tests.
- Gate (Slice 0 pass criteria): p95 of `finalize` to `final` <= 1.3 s for clients in US and EU measured against their nearest region, and cost <= $0.46 per audio hour (Deepgram billed amount / audio hours forwarded, regular pay-as-you-go rate). Anything else means the cap/price in blueprint section 6 is revisited before more is built.
- Regions: us-east4, europe-west4, asia-southeast1. Cloud Run `captions-rt`: timeout 3600 s, concurrency 250, CPU always allocated, min-instances 1, max-instances 20, startup CPU boost. Load balancer backend-service timeout 3600 s, custom header `X-Client-Region: {client_region}`. Secrets (Deepgram key, JWT secret) in Secret Manager, never in images or the repo.

## Review Focus

1. A Deepgram result that arrives late or out of order must never leak into the next segment (Task 4 test: result with `start` inside segment k delivered after `finalize` k+1; assert it is assigned to k).
2. `finalize` for a segment that contained no speech or no vendor result: exactly one `final` with empty text and `complete:false` after 1500 ms, never zero or two (Task 4, Task 5).
3. A buggy or malicious client sending audio faster than real time must be cut off with 4400 before it can burn vendor money (Task 5 token-bucket test).
4. Vendor auth failure (bad key) must close the client with 4503 and the key must not appear in any log line, error message or close reason (Task 3 and Task 5 tests scan captured logs).
5. Client disconnect mid-stream must close the vendor connection promptly and leave no timers or sockets (Task 5 test with the fake vendor counting open connections).

---

## File Structure

```
backend/
  package.json, tsconfig.json, vitest.config.ts, eslint.config.js, Dockerfile, README.md
  src/config.ts            env -> validated Config
  src/log.ts               allowlisted JSON logger
  src/protocol.ts          client message types, parsing, limits, close codes
  src/auth.ts              HS256 session verify + dev token signing
  src/asr/vendor.ts        AsrVendor / VendorStream interfaces (blueprint 1.9)
  src/asr/deepgram.ts      Deepgram implementation
  src/proxy/segments.ts    SegmentTracker: vendor results -> partial/final per segment (pure)
  src/proxy/ratelimit.ts   real-time audio token bucket (pure)
  src/proxy/session.ts     per-connection StreamSession
  src/server.ts            Fastify app, /v1/stream upgrade, /healthz, /readyz
  src/main.ts              process entry
  test/fake-deepgram/server.ts   scripted fake vendor
  test/**                  unit and integration tests
  tools/check-deepgram-languages.ts
  tools/replay/{wav,vad,metrics,replay}.ts
  tools/{gate,cost-report}.ts
  config/deepgram-languages.json
  deploy/{deploy-region.sh,setup-global-lb.sh,RUNBOOK.md,results-template.md}
.github/workflows/backend-ci.yml
app/src/test/java/com/voicecaption/settings/SupportedLanguagesExportTest.kt
docs/design/deepgram-language-gaps.md   generated
```

---

### Task 1: Backend scaffold, config, logger, CI

**Files:** Create `backend/package.json`, `tsconfig.json`, `vitest.config.ts`, `eslint.config.js`, `src/config.ts`, `src/log.ts`, `test/config.test.ts`, `test/log.test.ts`, `.github/workflows/backend-ci.yml`, `backend/README.md`.

**Interfaces:**
- Produces: `loadConfig(env: NodeJS.ProcessEnv): Config` with `Config { port: number; region: string; deepgramApiKey: string; deepgramHosts: { us: string; eu: string }; jwtSecret: string; supportedLanguages: Set<string>; maxStreamMs: number }`; throws `ConfigError` whose message lists every problem. `createLogger(write: (line: string) => void, opts?: { strict: boolean }): Logger` with `info(event: string, fields?: Record<string, unknown>)` and `error(...)`; `ALLOWED_FIELDS: ReadonlySet<string>`.

- [ ] **Step 1: Write failing tests.** `config.test.ts`: missing `DEEPGRAM_API_KEY` and `JWT_SECRET` -> one `ConfigError` naming both; `PORT=abc` -> error; defaults: `PORT` 8080, hosts as in Global Constraints, `MAX_STREAM_MS` 3300000; `SUPPORTED_LANGUAGES` env (comma list) parsed into a Set, default read from `config/deepgram-languages.json` when present. `log.test.ts`: output is one JSON object per line with `ts`, `level`, `event`; in strict mode a field named `text`, `transcript`, `token`, `authorization`, `api_key` or any name not in `ALLOWED_FIELDS` throws; in non-strict mode it is dropped and a `dropped_field` event is emitted; a value that looks like a JWT (`xxx.yyy.zzz`) in an allowed field is redacted.
- [ ] **Step 2: Run** `cd backend && npm test`. Expected: FAIL (modules missing).
- [ ] **Step 3: Implement** the files. `ALLOWED_FIELDS` starts with: `region, stream_id, session_id, uid, kind, lang, asr_region, model, close_code, reason, audio_ms, segments, frames, bytes, duration_ms, vendor_request_id, client, client_region, error_kind, attempt`. Add scripts `test`, `lint`, `typecheck`, `build`. CI workflow `backend-ci.yml`: trigger on `pull_request` and push to `main` with `paths: backend/**`; Node 22; `npm ci`, `npm run lint`, `npm run typecheck`, `npm test`; `permissions: contents: read`; `timeout-minutes: 15`.
- [ ] **Step 4: Run** `npm run lint && npm run typecheck && npm test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): scaffold, config loader, allowlisted logger, CI`.

### Task 2: Protocol parsing and limits

**Files:** Create `src/protocol.ts`, `test/protocol.test.ts`.

**Interfaces:**
- Produces: `type ClientMessage = StartMsg | FinalizeMsg | StopMsg`; `parseClientMessage(raw: string): { ok: true; msg: ClientMessage } | { ok: false; reason: string }`; `const CLOSE: { NORMAL: 1000; BAD_JWT: 4001; LANG_UNSUPPORTED: 4004; PROTOCOL: 4400; VENDOR: 4503 }`; `MAX_FRAME_BYTES = 65536`; `ServerMessage` union (`ready|partial|final`) and `serialize(msg: ServerMessage): string`.

- [ ] **Step 1: Write failing tests:** valid `start` accepted; `sample_rate` != 16000, `encoding` != `linear16`, `channels` != 1, non-integer `seg_base`, missing `lang`, extra unknown `type`, invalid JSON, and a `start` sent twice are all rejected with a reason; `finalize` requires integer `seg`; `stop` accepted; serialize round-trips `final` with `complete:false` and empty `text`.
- [ ] **Step 2: Run** the file. Expected: FAIL.
- [ ] **Step 3: Implement** with hand-written validation (no schema library). Reasons are short machine strings (`bad_json`, `bad_sample_rate`, ...), never echoing client content.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): client protocol parsing and limits`.

### Task 3: Vendor interface, Deepgram adapter, fake Deepgram, contract suite

**Files:** Create `src/asr/vendor.ts`, `src/asr/deepgram.ts`, `test/fake-deepgram/server.ts`, `test/asr/contract.test.ts`.

**Interfaces:**
- Produces (exactly as blueprint 1.9): `AsrVendor.open(o: { lang: string; model: string; asrRegion: "us"|"eu"; traceId: string }): Promise<VendorStream>`; `VendorStream { sendAudio(pcm: Buffer): void; finalize(): void; keepAlive(): void; close(): Promise<void>; on("result", (r: { text: string; startMs: number; endMs: number; isFinal: boolean; fromFinalize: boolean }) => void): void; on("error" | "closed", (e?: VendorError) => void): void }`; `VendorError { kind: "connect"|"auth"|"rate_limited"|"protocol"|"timeout"|"dropped"; message: string }` (message never contains the API key). `createDeepgramVendor(cfg: { apiKey: string; hosts: { us: string; eu: string } }): AsrVendor`. `startFakeDeepgram(opts?: FakeOptions): Promise<{ url: string; stop(): Promise<void>; openConnections(): number; receivedAudioBytes(): number }>` with `FakeOptions { resultDelayMs?: number; interimEveryBytes?: number; transcriptForSegment?: (n: number) => string; dropAfterMs?: number; rejectAuth?: boolean; noAudioTimeoutMs?: number /* default 10000 */; finalizeResponseDelayMs?: number | null /* null = never answer Finalize */ }`.

- [ ] **Step 1: Write the contract suite** `contract.test.ts` as a function `runContract(makeVendor: () => Promise<{ vendor: AsrVendor; cleanup(): Promise<void> }>)` invoked against the fake; also invoked against real Deepgram only when `DEEPGRAM_API_KEY` is set and `RUN_REAL_DEEPGRAM=1` (skipped in CI, documented in the README). Cases: connect and receive interim then final results for 2 s of fixture audio; `finalize()` yields exactly one result with `fromFinalize:true`; `keepAlive()` keeps the stream open past `noAudioTimeoutMs`; `close()` ends the connection (fake `openConnections()` returns 0); bad key -> `error` event with `kind:"auth"`; mid-stream drop -> `closed` with `kind:"dropped"`; the API key string never appears in any `VendorError.message` nor in the URL logged by the adapter. Request parameters sent to the fake (query string) equal the Global Constraints list exactly.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the interface, the adapter (Authorization header `Token <key>`; query parameters from Global Constraints; map Deepgram `Results` JSON to `result`; `Metadata`/errors to `error`), and the fake server on `ws`.
- [ ] **Step 4: Run.** Expected: PASS (real-vendor run skipped).
- [ ] **Step 5: Commit** `feat(backend): AsrVendor, Deepgram adapter, fake Deepgram, contract suite`.

### Task 4: SegmentTracker (pure)

**Files:** Create `src/proxy/segments.ts`, `test/segments.test.ts`.

**Interfaces:**
- Consumes: the `result` shape from Task 3.
- Produces: `class SegmentTracker { constructor(opts: { segBase: number }); onAudio(bytes: number): void; onFinalize(seg: number): void; onVendorResult(r: VendorResult): TrackerEvent[]; expire(nowMs: number): TrackerEvent[]; readonly forwardedMs: number; readonly pendingFinalizes: number }` and `type TrackerEvent = { kind: "partial"; seg: number; text: string } | { kind: "final"; seg: number; text: string; audioMs: number; complete: boolean }`. `onFinalize` takes the receive time implicitly through an injected clock: constructor option `now: () => number` (default `Date.now`).

Semantics (decided here): forwarded offset = total bytes / 32 ms. A finalize for segment `seg` closes the range `[previous end, forwardedMs)`; the open segment covers `[last end, infinity)`. A vendor result is assigned to the segment whose range contains the result's `startMs`. `partial.text` = the joined `isFinal` chunks of that segment so far plus the latest interim chunk. A `fromFinalize` result completes the oldest pending finalize (FIFO) and emits its `final` (text = joined final chunks for that segment, including this result). If no `fromFinalize` result arrives within 1500 ms of the finalize, `expire(nowMs)` emits `final` with `complete:false` and whatever text was gathered (possibly empty). Exactly one `final` per finalize; a late `fromFinalize` result after expiry is dropped.

- [ ] **Step 1: Write failing tests:** (a) interim then final chunks join into the partial text in order; (b) two segments, vendor result for segment 1 arrives after `onFinalize(2)` -> assigned to segment 1 (Review Focus 1); (c) `fromFinalize` result -> one `final` with `complete:true` and `audioMs` equal to the segment's forwarded ms; (d) finalize with no audio and no vendor result -> after `expire(+1500)` exactly one `final` with `text:""`, `complete:false`; calling `expire` again emits nothing (Review Focus 2); (e) late `fromFinalize` after expiry is dropped, no second `final`; (f) `fromFinalize` results complete finalizes in FIFO order when two are pending; (g) a seeded-random property test (200 runs): any interleaving of audio, finalize and results never yields two `final` for the same `seg`, never a `final` for a segment before its finalize, and the count of `final` events after enough `expire` calls equals the number of finalizes.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** `SegmentTracker` per the semantics above.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): segment tracker with exactly-one-final guarantee`.

### Task 5: Auth, rate limit, StreamSession, server

**Files:** Create `src/auth.ts`, `src/proxy/ratelimit.ts`, `src/proxy/session.ts`, `src/server.ts`, `src/main.ts`, `test/auth.test.ts`, `test/ratelimit.test.ts`, `test/session.integration.test.ts`.

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: `signDevToken(claims: SessionClaims, secret: string, ttlS?: number): Promise<string>`; `verifySession(token: string, secret: string): Promise<SessionClaims>` (throws `AuthError`); `class AudioTokenBucket { constructor(opts: { ratePerS: number; burstS: number; now: () => number }); take(audioMs: number): boolean }` (rate = 1.5 x real time, burst 5 s); `buildServer(cfg: Config, deps: { vendor: AsrVendor; log: Logger; now?: () => number }): Promise<{ listen(): Promise<{ port: number }>; close(): Promise<void> }>` serving `GET /healthz` (200 always), `GET /readyz` (200 when config loaded), `GET /v1/stream` upgrade (Authorization `Bearer <jwt>`; invalid -> HTTP 401 before upgrade, never a socket).

Behaviour to implement in `StreamSession`: first text frame must be `start` (else 4400); `lang` not in `cfg.supportedLanguages` -> `ready` is not sent and the socket closes 4004; send `ready` with `region`, `asr_region` from the JWT, `model:"nova-3"`, `remaining_ms` = JWT `allow_ms`, `flush_interval_ms:10000`, `max_stream_ms:cfg.maxStreamMs`, `config_version` = JWT `cfgv`; forward each binary frame to the vendor (> 64 KB -> 4400; token bucket exhausted -> 4400) and feed `SegmentTracker.onAudio`; `finalize {seg}` -> `tracker.onFinalize` + `vendor.finalize()`; vendor results -> tracker events -> `partial`/`final` messages; a 250 ms timer drives `tracker.expire`; `stop` -> finalize, wait for outstanding finals (max 1500 ms), close 1000; vendor `error`/`closed` of kinds `connect|auth|dropped|timeout` -> close 4503 (reason `vendor_unavailable`, no vendor text); vendor KeepAlive every 5 s without audio; close vendor after 60 s without audio and reopen lazily buffering <= 2 s; on client close: close the vendor, clear all timers; at close log `stream_close` with `audio_ms` (bytes/32), `segments`, `frames`, `bytes`, `close_code`.

- [ ] **Step 1: Write failing tests.** `auth.test.ts`: signed token verifies; wrong secret, expired, wrong `aud`, `alg:none` all throw `AuthError`. `ratelimit.test.ts`: 5 s burst allowed instantly; sustained 1.4x real time passes for 60 s of fake clock; 1.6x real time fails once the burst is spent. `session.integration.test.ts` (real `ws` client against `buildServer` + fake Deepgram): happy path (start, frames, finalize, receive `ready`, >= 1 `partial`, exactly one `final` per finalize); invalid JWT -> HTTP 401; `lang:"xx"` -> close 4004; frame of 65537 bytes -> 4400; audio sent at 3x real time -> 4400 before 15 s of audio is forwarded to the vendor (assert the fake's `receivedAudioBytes()` stays below that) (Review Focus 3); vendor `rejectAuth` -> close 4503 and the captured log lines plus the close reason contain neither the API key nor the JWT (Review Focus 4); vendor drop mid-stream -> 4503; client disconnect mid-stream -> fake `openConnections()` returns to 0 within 1 s and no timers remain (use fake timers' count) (Review Focus 5); finalize with no vendor answer (`finalizeResponseDelayMs: null`) -> `final` with `complete:false` after 1500 ms (Review Focus 2); lazy reopen: 61 s of fake-clock silence closes the vendor connection, the next frame reopens it and nothing is lost within 2 s of buffered audio; `stream_close` log has `audio_ms` equal to bytes/32 and no non-allowlisted field.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the modules. `main.ts` builds the real adapter and starts the server from `loadConfig(process.env)`; tool `tools/mint-dev-token.ts` prints a 12-hour dev JWT for `JWT_SECRET` (used by the harness and the runbook).
- [ ] **Step 4: Run** `npm run lint && npm run typecheck && npm test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): stream proxy session, auth, token bucket, server`.

### Task 6: Container image

**Files:** Create `backend/Dockerfile`, `backend/.dockerignore`; modify `README.md`.

- [ ] **Step 1:** Multi-stage Dockerfile (Node 22 slim; `npm ci`, `npm run build`, runtime image runs as a non-root user with `node dist/main.js`, `PORT` from env, no secrets baked in).
- [ ] **Step 2: Verify:** `docker build backend` succeeds if Docker is available; otherwise run `npm run build && node dist/main.js` with dummy env against the fake vendor and `curl localhost:8080/healthz` returns 200. Record which path was used.
- [ ] **Step 3: Commit** `build(backend): container image`.

### Task 7: Language coverage check

**Files:** Create `app/src/test/java/com/voicecaption/settings/SupportedLanguagesExportTest.kt`, `backend/tools/check-deepgram-languages.ts`, `backend/test/languages.test.ts`, `backend/test/fixtures/deepgram-models.json`, `backend/config/deepgram-languages.json`, `docs/design/deepgram-language-gaps.md`.

**Interfaces:**
- Consumes: `SupportedLanguages.codes` (57 codes, `app/src/main/java/com/voicecaption/settings/SupportedLanguages.kt`).
- Produces: `computeCoverage(appCodes: string[], models: DeepgramModelsResponse): { supported: Record<string, string /* model */>; gaps: string[] }`; `renderGapTable(c: Coverage, fetchedAt: string): string`.

- [ ] **Step 1: Write failing tests.** Kotlin test writes `app/build/outputs/supported-languages.json` (sorted array of the 57 codes) and asserts its size is 57. `languages.test.ts` with the fixture: coverage maps codes to models; gaps are exactly the codes not streamable (fixture includes `cy, gl, ht, is, mt, sq, sw` as non-streamable); `zh` maps to Deepgram `zh`; a code whose model is batch-only counts as a gap; output table is deterministic (stable ordering, fixed `fetchedAt` parameter).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*SupportedLanguagesExportTest*'` (with `ANDROID_HOME=/opt/android-sdk`) and `npm test`. Expected: FAIL.
- [ ] **Step 3: Implement** the export test and the tool. The CLI (`npx tsx tools/check-deepgram-languages.ts --app app/build/outputs/supported-languages.json`) calls `GET https://api.deepgram.com/v1/models` with `DEEPGRAM_API_KEY`; the response shape is **unverified**: parse defensively, fail loudly with the raw keys if the expected fields are missing, and document the fallback (docs page) in the README. Seed `config/deepgram-languages.json` and the gap doc from the preliminary result in the blueprint (50 of 57 covered; gaps `cy, gl, ht, is, mt, sq, sw`) clearly labelled `PRELIMINARY: re-generate with a real key in Task 10`.
- [ ] **Step 4: Run** both test commands. Expected: PASS.
- [ ] **Step 5: Commit** `feat: language coverage export and Deepgram coverage check`.

### Task 8: Replay harness

**Files:** Create `backend/tools/replay/wav.ts`, `vad.ts`, `metrics.ts`, `replay.ts`, `backend/test/replay.test.ts`.

**Interfaces:**
- Produces: `readWav(path: string): { sampleRate: number; samples: Int16Array }` (RIFF chunk walking, 16-bit PCM mono only, rejects other formats); `segmentSpeech(samples: Int16Array, sampleRate: number): { startMs: number; endMs: number }[]` (energy VAD on 32 ms frames, 400 ms hangover, 300 ms minimum speech, mirroring the app's `VadSegmenter`); `percentile(values: number[], p: number): number` (nearest rank; empty input throws); `summarize(records: SegmentRecord[]): Summary`. CLI: `tsx tools/replay/replay.ts --url <wss://host/v1/stream> --token <jwt> --clips <dir> --repeat <n> --label <region-or-vantage> --out <results.jsonl>`.

Metrics per segment (harness clocks only, monotonic `performance.now()`; no cross-machine clock comparison): `ttfp_ms` = first audio batch of the segment sent -> first `partial` for that `seg`; `final_after_finalize_ms` = `finalize` sent -> `final` for that `seg`; per stream `open_to_ready_ms` = socket open start -> `ready` received; `audio_ms_sent` (bytes/32). Frames are sent in real time in 96 ms batches (3 x 32 ms); only speech segments plus their 400 ms hangover are sent, never silence. Records are JSON Lines `{label, clip, seg, ttfp_ms, final_after_finalize_ms, complete, audio_ms}` plus one `{label, type:"stream", open_to_ready_ms, audio_ms_sent}` per stream; `summarize` produces p50/p95 per metric, the share of `complete:false`, and total `audio_ms_sent`.

- [ ] **Step 1: Write failing tests:** WAV reader parses the repo's bench clips (`app/src/androidTest/assets/bench/*.wav`) and rejects stereo/8-bit; `segmentSpeech` on a synthetic signal (1 s tone, 1 s silence, 1 s tone) returns two segments whose ends include the 400 ms hangover; `percentile` of 1..20 at p95 = 19, p50 = 10, unsorted input OK; **self-test against the proxy + fake vendor**: with the fake configured with `finalizeResponseDelayMs: 250`, the harness reports `final_after_finalize_ms` p50 within 250 ms +/- 40 ms; with `resultDelayMs: 400` on interims, `ttfp_ms` p50 within 400 ms +/- 60 ms; the harness never sends a silent frame beyond the hangover (assert the fake received no more audio bytes than speech-plus-hangover length); total `audio_ms_sent` equals the fake's received bytes / 32.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the modules. Pacing must use absolute deadlines (`start + n * 96 ms`), not cumulative sleeps, so drift does not distort the measurement.
- [ ] **Step 4: Run** `npm test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): replay harness with per-hop latency metrics`.

### Task 9: Cost report and gate evaluator

**Files:** Create `backend/tools/cost-report.ts`, `backend/tools/gate.ts`, `backend/test/gate.test.ts`.

**Interfaces:**
- Produces: `costPerAudioHour(billedUsd: number, meteredAudioMs: number): number`; `evaluateGate(input: { runs: { label: string; vantage: "us"|"eu"|"asia"; finalP95Ms: number; completeFalseShare: number }[]; costPerAudioHourUsd: number }): { pass: boolean; checks: { name: string; pass: boolean; value: number; limit: number }[]; advice: string[] }`. CLI: `tsx tools/gate.ts --summary results-*.json --billed-usd <amount> --metered-ms <ms>`.

Gate checks: for each `us` and `eu` vantage `finalP95Ms <= 1300`; `costPerAudioHourUsd <= 0.46`; `asia` is reported but not gating; `completeFalseShare <= 0.01` is reported as a warning only. Advice strings follow blueprint section 6: if cost > 0.46, "lower paid_cap_ms (config), raise the Play price, or move to Deepgram Growth pricing"; if US/EU latency fails, "check the proxy-to-Deepgram hop and endpointing before cutting regions"; if only asia fails, "apply the cut order in blueprint 1.4: cut asia-southeast1".

- [ ] **Step 1: Write failing tests:** `costPerAudioHour(2.31, 18_000_000)` = 0.462; zero metered ms throws; all-pass fixture -> `pass:true`; US p95 1301 ms -> fail naming the vantage; cost 0.4601 -> fail with the cap/price advice; asia failing alone -> overall `pass:true` with the cut-order advice; `completeFalseShare` 0.02 -> still pass with a warning.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** both tools.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): cost per audio hour and Slice 0 gate evaluator`.

### Task 10: Deployment scripts, runbook and measurement matrix (OPERATOR-RUN)

**Files:** Create `backend/deploy/deploy-region.sh`, `setup-global-lb.sh`, `RUNBOOK.md`, `results-template.md`.

Agents write and statically check these files; **the owner (or a human operator) executes them.** Agents must not run `gcloud`, call Deepgram with a real key, or create cloud resources.

- [ ] **Step 1: Write** `deploy-region.sh <project> <region>` (builds and pushes the image with Cloud Build, deploys Cloud Run `captions-rt` with exactly the Global Constraints settings and secrets from Secret Manager `deepgram-api-key` and `jwt-secret`, prints the service URL) and `setup-global-lb.sh <project> <domain>` (serverless NEGs for the three regions, one backend service with timeout 3600 s and outlier detection, URL map, managed certificate, forwarding rule, header `X-Client-Region: {client_region}`, Cloud Armor per-IP throttle). Both scripts support `--dry-run` that only prints the commands, use `set -euo pipefail`, and take no secrets as arguments.
- [ ] **Step 2: Write** `RUNBOOK.md`: prerequisites (billing-enabled GCP project, a domain, Deepgram key with pay-as-you-go billing and usage visible in the console, `gcloud` and Docker installed); one-time secret creation; deploy order (us-east4, europe-west4, asia-southeast1); validate each regional `run.app` URL with the harness for 1 minute; set up the load balancer; **WebSocket-through-load-balancer validation:** open a stream through the global hostname and stream speech for 20 minutes without unexpected close, confirm `X-Client-Region` appears in the `stream_open` log; measurement matrix: run the harness from three vantage points (a VM or Cloud Shell in each of: us-east, europe-west, asia-southeast) against the global hostname and against each regional URL, `--repeat` enough for >= 200 segments per cell, using **real film clips** supplied by the owner (the repo's synthetic eSpeak clips are acceptable for transport latency only); cost measurement: stream a known total of audio (>= 2 audio hours across all runs), note the proxy's logged `audio_ms` sum, wait for Deepgram's billing to settle, then run `gate.ts` with the billed amount and metered ms; language check: run `check-deepgram-languages` with the real key, commit the regenerated `config/deepgram-languages.json` and gap doc; Deepgram EU endpoint: confirm Nova-3 streaming works on `api.eu.deepgram.com`, record price parity and host location; teardown: delete Cloud Run services, NEGs, LB, secrets, and revoke the Deepgram key when the proof is finished.
- [ ] **Step 3: Write** `results-template.md` (every table cell filled with `NOT RUN`): per vantage p50/p95 for `open_to_ready_ms`, `ttfp_ms`, `final_after_finalize_ms`; share of `complete:false`; cost per audio hour; language gap table date; EU endpoint findings; the gate verdict and the decision taken. State that the verdict stays `NOT RUN` until the owner fills it from real measurements.
- [ ] **Step 4: Verify** with `shellcheck backend/deploy/*.sh` (install via apt if absent; otherwise `bash -n`) and `./deploy-region.sh demo-project us-east4 --dry-run`, `./setup-global-lb.sh demo-project example.com --dry-run` printing the expected commands (assert the printed commands contain `--timeout=3600`, `--min-instances=1`, `--max-instances=20`, `--no-cpu-throttling`, `--concurrency=250`, `--cpu-boost`). No secrets appear in the output.
- [ ] **Step 5: Commit** `docs(backend): deployment scripts, runbook and results template for Slice 0`.

---

## Self-review notes

- **Spec coverage:** Phase 0 (language check, app export test, replay harness) = Tasks 7-8; Phase 4 subset (adapter + fake + contract suite) = Task 3; Phase 5 subset (proxy, segment assignment, finalize/final 1:1, KeepAlive, lazy reopen, over-rate) = Tasks 4-5; Slice 0 deployment, WebSocket-through-LB validation, three-continent measurement, cost per audio hour and gate = Tasks 9-10; the "verified language gaps" and EU endpoint checks are in Tasks 7 and 10. Rotation, metering enforcement, DB, billing, circuit breaker and Cloud Armor tuning are deliberately **not** in this slice.
- **Honest limits:** every real-world number (latency per region, cost per hour, Deepgram's `/v1/models` response shape, EU endpoint behaviour) is unknown until Task 10 is run by the owner; the plan produces tooling and a results template, not results.
- **Types:** `VendorResult` (Task 3) is consumed unchanged by `SegmentTracker` (Task 4); `TrackerEvent` maps one-to-one onto `ServerMessage` (Task 2); `SessionClaims` (Task 5) carries the claim names in Global Constraints; `SegmentRecord` fields (Task 8) are the inputs of `evaluateGate` (Task 9) via `summarize`.
