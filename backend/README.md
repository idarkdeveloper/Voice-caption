# captions-rt (Slice 0 backend)

Speech-only WebSocket proxy in front of Deepgram streaming, used to prove the latency and cost gate
(see `docs/superpowers/plans/2026-10-17-deepgram-latency-proof.md`).

## Commands

```
npm ci
npm run lint && npm run typecheck && npm test
npm run build
```

## Configuration (environment)

| Variable | Default | Notes |
|---|---|---|
| `DEEPGRAM_API_KEY` | required | never logged |
| `JWT_SECRET` | required | HS256 development secret, never logged |
| `PORT` | 8080 | |
| `REGION` | `local` | Cloud Run region label |
| `DEEPGRAM_HOST_US` / `DEEPGRAM_HOST_EU` | `wss://api.deepgram.com/v1/listen` / `wss://api.eu.deepgram.com/v1/listen` | |
| `MAX_STREAM_MS` | 3300000 | |
| `SUPPORTED_LANGUAGES` | `config/deepgram-languages.json` when present | comma separated list |

## Container image

```
docker build -t captions-rt .
docker run --rm -p 8080:8080 -e DEEPGRAM_API_KEY=... -e JWT_SECRET=... captions-rt
```

Multi-stage build (Node 22 slim), runs as the non-root `node` user, `PORT` from the environment, no
secrets baked in (`.dockerignore` excludes env files, tests and tools).

Verification record: the build environment had the Docker CLI but no Docker daemon, so
`docker build` was **not** run. Instead the alternative path was used: `npm run build && node
dist/main.js` with dummy `DEEPGRAM_API_KEY`/`JWT_SECRET`, then `curl /healthz` and `/readyz` both
returned 200. The runtime layout of the image (`dist/`, `config/`, production-only `node_modules`)
was also reproduced in a scratch directory with `npm ci --omit=dev` and `/healthz` returned 200 there.
The first real `docker build` happens in Task 10 (`deploy-region.sh` uses Cloud Build).

## Real Deepgram contract run

The vendor contract suite also runs against real Deepgram when `DEEPGRAM_API_KEY` is set and
`RUN_REAL_DEEPGRAM=1`. It is skipped in CI.

## Language coverage check

```
./gradlew testDebugUnitTest --tests '*SupportedLanguagesExportTest*'   # writes app/build/outputs/supported-languages.json
DEEPGRAM_API_KEY=... npx tsx tools/check-deepgram-languages.ts --app ../app/build/outputs/supported-languages.json
```

The tool calls `GET https://api.deepgram.com/v1/models`, keeps models that report `streaming: true`,
collapses regional tags (`zh-CN`, `en-US`) to the app's base code, prefers `nova-3`, and writes
`config/deepgram-languages.json` and `docs/design/deepgram-language-gaps.md`.

The shape of the `/v1/models` response is **unverified**. The tool expects `{ stt: [{ name,
languages[], streaming }] }`; if the list or those fields are missing it exits non-zero and prints the
raw keys it saw instead of guessing. Fallback when that happens:

1. Save the response to a file and fix it by hand into the expected shape, then run the tool with
   `--models-file <file>`, or
2. Read the supported streaming languages from Deepgram's models and languages docs page, and edit
   `config/deepgram-languages.json` (`{ "fetchedAt": "...", "languages": { "<code>": "nova-3" } }`)
   and the gap doc by hand, noting the source and date.

The committed `config/deepgram-languages.json` and gap doc are **PRELIMINARY** (seeded from the
blueprint's result, 50 of 57, gaps cy, gl, ht, is, mt, sq, sw). Re-generate them with a real key in
Task 10.

## Privacy

Logs use an allowlist of field names (`src/log.ts`). No audio, transcript text, JWTs or API keys are
ever logged.
