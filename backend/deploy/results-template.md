# Slice 0 results

Every cell below is `NOT RUN`. Nothing in this file is a measurement until the operator replaces it
from real runs following `RUNBOOK.md`. **The verdict stays `NOT RUN` until the owner fills it from
real measurements.** Do not copy numbers from tests, the fake Deepgram or the design estimates.

Run date: NOT RUN. Operator: NOT RUN. Clips used (real film clips or synthetic): NOT RUN.
Commit of `backend/`: NOT RUN.

## Deployment

| Item | Result |
|---|---|
| us-east4 service URL | NOT RUN |
| europe-west4 service URL | NOT RUN |
| asia-southeast1 service URL | NOT RUN |
| Global hostname | NOT RUN |
| 20-minute WebSocket through the load balancer, no unexpected close | NOT RUN |
| `X-Client-Region` visible in `stream_open` log | NOT RUN |

## Latency by vantage (against the nearest region and against the global hostname)

Segments per cell must be >= 200. Times in ms.

| Vantage | Target | Segments | open_to_ready p50 | open_to_ready p95 | ttfp p50 | ttfp p95 | final_after_finalize p50 | final_after_finalize p95 | complete:false share |
|---|---|---|---|---|---|---|---|---|---|
| us-east | nearest region | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| us-east | global hostname | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| europe-west | nearest region | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| europe-west | global hostname | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| asia-southeast | nearest region | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| asia-southeast | global hostname | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

## Cost per audio hour

| Item | Result |
|---|---|
| Total audio streamed (>= 2 audio hours) | NOT RUN |
| Proxy-logged `audio_ms` sum (`metered_ms`) | NOT RUN |
| Deepgram billed amount (USD, regular pay-as-you-go rate) | NOT RUN |
| Cost per audio hour (USD) | NOT RUN |
| Limit | 0.46 |

## Language coverage

| Item | Result |
|---|---|
| Date `check-deepgram-languages` was run with a real key | NOT RUN |
| Covered / total app languages | NOT RUN |
| Gaps | NOT RUN |
| `/v1/models` shape as expected, or fallback used | NOT RUN |

## Deepgram EU endpoint

| Item | Result |
|---|---|
| Nova-3 streaming works on `api.eu.deepgram.com` | NOT RUN |
| Price parity with the US endpoint | NOT RUN |
| Host location | NOT RUN |
| Asia endpoint exists | NOT RUN |

## Gate

| Check | Value | Limit | Result |
|---|---|---|---|
| US p95 finalize to final | NOT RUN | 1300 ms | NOT RUN |
| EU p95 finalize to final | NOT RUN | 1300 ms | NOT RUN |
| Cost per audio hour | NOT RUN | 0.46 USD | NOT RUN |
| Asia p95 (reported, not gating) | NOT RUN | n/a | NOT RUN |
| `complete:false` share (warning only) | NOT RUN | 0.01 | NOT RUN |

**Verdict: NOT RUN**

**Decision taken: NOT RUN**
