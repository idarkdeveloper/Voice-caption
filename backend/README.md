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

## Real Deepgram contract run

The vendor contract suite also runs against real Deepgram when `DEEPGRAM_API_KEY` is set and
`RUN_REAL_DEEPGRAM=1`. It is skipped in CI.

## Privacy

Logs use an allowlist of field names (`src/log.ts`). No audio, transcript text, JWTs or API keys are
ever logged.
