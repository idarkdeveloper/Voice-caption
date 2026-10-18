# Slice 0 runbook: deploy, measure, decide

**Operator-run.** Nothing here has been executed by the agent that wrote it. The scripts were only
checked statically (`shellcheck`) and with `--dry-run`. Every number in `results-template.md` stays
`NOT RUN` until you fill it from real measurements.

Goal: prove, with real measurements, the gate from the plan: p95 of `finalize` to `final` <= 1.3 s for
US and EU clients against their nearest region, and cost <= $0.46 per audio hour.

## 0. Prerequisites

- A Google Cloud project with billing enabled, and a domain you can add an A record to.
- A Deepgram key with pay-as-you-go billing and usage visible in the Deepgram console.
- `gcloud` (logged in, `gcloud config set project <project>`) and Docker installed on your workstation.
  Container images are built by Cloud Build, so local Docker is only for ad-hoc checks.
- Node 22 and `npm ci` in `backend/` on every vantage machine (the replay harness runs from there).
- Real film clips supplied by the owner for the latency matrix. The repo's synthetic eSpeak clips are
  acceptable for transport latency only, not for the gate verdict.
- If your organization policy blocks `--allow-unauthenticated` on Cloud Run, grant an exception for
  this proxy: callers are authenticated by the session JWT at the WebSocket upgrade.

Enable APIs once:

```
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com compute.googleapis.com --project <project>
```

## 1. One-time secrets

Never put these in the repo, an image, a script argument or shell history you share. Read them from
stdin:

```
printf '%s' "$DEEPGRAM_KEY_FROM_CONSOLE" | gcloud secrets create deepgram-api-key --data-file=- --project <project>
openssl rand -base64 48 | tr -d '\n' | gcloud secrets create jwt-secret --data-file=- --project <project>
```

Allow the Cloud Run runtime service account (default: the Compute Engine default service account) to
read them:

```
PROJECT_NUMBER=$(gcloud projects describe <project> --format='value(projectNumber)')
for s in deepgram-api-key jwt-secret; do
  gcloud secrets add-iam-policy-binding "$s" --project <project> \
    --member "serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --role roles/secretmanager.secretAccessor
done
```

## 2. Deploy the three regions

Preview first, then run. The order matters only for your own sanity checks:

```
cd backend/deploy
./deploy-region.sh <project> us-east4 --dry-run && ./deploy-region.sh <project> us-east4
./deploy-region.sh <project> europe-west4
./deploy-region.sh <project> asia-southeast1
```

Each run builds the image with Cloud Build, deploys `captions-rt` (timeout 3600 s, concurrency 250,
CPU always allocated, min-instances 1, max-instances 20, startup CPU boost) and prints the `run.app`
service URL. Write the three URLs into `results-template.md`.

## 3. Validate each regional URL for 1 minute

Mint a development token (HS256, 12 h) with the same secret the services use. Use `--asr eu` for the
EU vantage so audio goes to the EU Deepgram endpoint:

```
JWT_SECRET="$(gcloud secrets versions access latest --secret jwt-secret --project <project>)" \
  npx tsx tools/mint-dev-token.ts --asr us > /tmp/token-us    # keep the file private (chmod 600)
```

Then, from a machine near that region:

```
curl -fsS https://<regional-run-app-url>/healthz
npx tsx tools/replay/replay.ts --url wss://<regional-run-app-host>/v1/stream \
  --token "$(cat /tmp/token-us)" --clips <clips-dir> --repeat 1 --label us-east4-smoke --out smoke.jsonl
```

Expect `ready`, partials and one `final` per `finalize`. Check Cloud Run logs for `stream_open` and
`stream_close` lines; no audio, text, token or key may appear in any log line. Stop here and fix
anything unexpected.

## 4. Global load balancer

```
./setup-global-lb.sh <project> <domain> --dry-run
./setup-global-lb.sh <project> <domain>
```

The script creates serverless NEGs for the three regions, one backend service (timeout 3600 s,
outlier detection, custom header `X-Client-Region: {client_region}`), a Cloud Armor per-IP throttle,
URL map, managed certificate, address and forwarding rule. Create the A record for `<domain>` at the
printed address and wait until the certificate status is `ACTIVE` (can take up to an hour).

Notes:
- The script is meant to run once. If it fails halfway, resources that exist are skipped, but the
  `add-backend` and Cloud Armor rule steps are not idempotent: delete the partial pieces (see
  teardown) or run the remaining commands by hand.
- The `--outlier-detection` flag syntax was written from the gcloud reference and **not** run
  against gcloud. If gcloud rejects it, check `gcloud compute backend-services update --help` and
  adjust the one line.

## 5. WebSocket through the load balancer (20 minutes)

Open a stream through the global hostname and stream speech for 20 minutes without an unexpected
close (the harness with a long clip and `--repeat` high enough, label `lb-soak-20min`):

```
npx tsx tools/replay/replay.ts --url wss://<domain>/v1/stream --token "$(cat /tmp/token-us)" \
  --clips <clips-dir> --repeat <n for 20 minutes> --label lb-soak-20min --out lb-soak.jsonl
```

Confirm: no close other than the one you initiated, and the `stream_open` log line in Cloud Run logs
carries `client_region` (the value of `X-Client-Region`). Record the result in the template.

## 6. Measurement matrix

From three vantage points, a VM or Cloud Shell in each of: us-east, europe-west, asia-southeast:

| Vantage | Against global hostname | Against nearest regional URL | Token |
|---|---|---|---|
| us-east | `wss://<domain>/v1/stream` | us-east4 `run.app` | `--asr us` |
| europe-west | same | europe-west4 `run.app` | `--asr eu` |
| asia-southeast | same | asia-southeast1 `run.app` | `--asr us` |

Use `--repeat` high enough for **>= 200 segments per cell**, `--label <vantage>-global` or
`<vantage>-<region>`, and one `--out` file per cell. Real film clips only for the verdict. Summarize
each file with the harness summary (p50/p95 of `open_to_ready_ms`, `ttfp_ms`,
`final_after_finalize_ms`, share of `complete:false`) and copy the numbers into the template. Keep
the raw `.jsonl` files with the results.

## 7. Cost measurement

1. Stream a known total of **>= 2 audio hours** across all runs (the matrix usually covers it; add
   more runs if not). Record the start and end time of the measured window.
2. Sum the proxy's logged `audio_ms` over the window (the `stream_close` lines in Cloud Logging) and
   write it down as `metered_ms`.
3. Wait for Deepgram's billing to settle (usage can lag; check the console usage page), then note the
   billed amount in USD for the window at the regular pay-as-you-go rate.
4. Evaluate the gate:

```
npx tsx tools/gate.ts --summary results-*.json --billed-usd <amount> --metered-ms <ms>
```

## 8. Language check

```
cd backend
DEEPGRAM_API_KEY=... npx tsx tools/check-deepgram-languages.ts \
  --app ../app/build/outputs/supported-languages.json
```

(The app export comes from `./gradlew testDebugUnitTest --tests '*SupportedLanguagesExportTest*'`.)
Commit the regenerated `config/deepgram-languages.json` and `docs/design/deepgram-language-gaps.md`;
this replaces the PRELIMINARY seed. If the tool exits with "unexpected /v1/models response", follow
the fallback in `backend/README.md` and say so in the results.

## 9. Deepgram EU endpoint

Confirm Nova-3 streaming works on `api.eu.deepgram.com` (run the EU vantage with `--asr eu`; the
contract suite also runs with `DEEPGRAM_API_KEY=... RUN_REAL_DEEPGRAM=1 npm test`), record price
parity with the US endpoint and the host location, and whether an Asia endpoint exists. Put the
findings in the template.

## 10. Decide

Fill `results-template.md` completely from your measurements and apply the gate. If it fails, the
advice printed by `gate.ts` applies (blueprint sections 1.4 and 6): revisit the cap and price, check
the proxy-to-Deepgram hop and endpointing, or cut asia-southeast1 first.

## 11. Teardown (when the proof is finished)

```
for r in us-east4 europe-west4 asia-southeast1; do
  gcloud run services delete captions-rt --region $r --project <project> --quiet
  gcloud artifacts repositories delete captions --location $r --project <project> --quiet
done
gcloud compute forwarding-rules delete captions-rt-https --global --project <project> --quiet
gcloud compute target-https-proxies delete captions-rt-proxy --project <project> --quiet
gcloud compute url-maps delete captions-rt-map --global --project <project> --quiet
gcloud compute ssl-certificates delete captions-rt-cert --global --project <project> --quiet
gcloud compute backend-services delete captions-rt-backend --global --project <project> --quiet
for r in us-east4 europe-west4 asia-southeast1; do
  gcloud compute network-endpoint-groups delete captions-neg-$r --region $r --project <project> --quiet
done
gcloud compute security-policies delete captions-rt-armor --project <project> --quiet
gcloud compute addresses delete captions-rt-ip --global --project <project> --quiet
gcloud secrets delete deepgram-api-key --project <project> --quiet
gcloud secrets delete jwt-secret --project <project> --quiet
```

Then **revoke the Deepgram key** in the Deepgram console and delete the DNS record.
