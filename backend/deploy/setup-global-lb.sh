#!/usr/bin/env bash
# Create the global external Application Load Balancer in front of the three captions-rt regions.
# OPERATOR-RUN, after deploy-region.sh has run for all three regions.
#
#   ./setup-global-lb.sh <project> <domain> [--dry-run]
#
# No secrets are involved. THROTTLE_COUNT (default 120) is the per-IP request limit per minute.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<'USAGE'
usage: setup-global-lb.sh <project> <domain> [--dry-run]
  --dry-run  print the gcloud commands without running anything
USAGE
}

parse_args "$@"
[ "${#POSITIONAL[@]}" -eq 2 ] || { usage >&2; exit 2; }
PROJECT="${POSITIONAL[0]}"
DOMAIN="${POSITIONAL[1]}"
require_gcloud

SERVICE="captions-rt"
REGIONS=(us-east4 europe-west4 asia-southeast1)
BACKEND="captions-rt-backend"
POLICY="captions-rt-armor"
THROTTLE_COUNT="${THROTTLE_COUNT:-120}"

for r in "${REGIONS[@]}"; do
  ensure gcloud compute network-endpoint-groups describe "captions-neg-$r" --region="$r" --project="$PROJECT" \
    :: gcloud compute network-endpoint-groups create "captions-neg-$r" --project="$PROJECT" \
    --region="$r" --network-endpoint-type=serverless --cloud-run-service="$SERVICE"
done

# Backend service: WebSocket lifetime 3600 s, region header, outlier detection (5 consecutive 5xx in
# 10 s ejects a region for 30 s).
ensure gcloud compute backend-services describe "$BACKEND" --global --project="$PROJECT" \
  :: gcloud compute backend-services create "$BACKEND" --project="$PROJECT" --global \
  --load-balancing-scheme=EXTERNAL_MANAGED --timeout=3600

for r in "${REGIONS[@]}"; do
  run gcloud compute backend-services add-backend "$BACKEND" --project="$PROJECT" --global \
    --network-endpoint-group="captions-neg-$r" --network-endpoint-group-region="$r"
done

run gcloud compute backend-services update "$BACKEND" --project="$PROJECT" --global \
  --timeout=3600 \
  --custom-request-header="X-Client-Region:{client_region}" \
  --outlier-detection=consecutive-errors=5,interval=10s,base-ejection-time=30s,max-ejection-percent=100

# Cloud Armor per-IP throttle.
ensure gcloud compute security-policies describe "$POLICY" --project="$PROJECT" \
  :: gcloud compute security-policies create "$POLICY" --project="$PROJECT" \
  --description="captions-rt per-IP throttle"
run gcloud compute security-policies rules create 1000 --project="$PROJECT" \
  --security-policy="$POLICY" --action=throttle \
  --rate-limit-threshold-count="$THROTTLE_COUNT" --rate-limit-threshold-interval-sec=60 \
  --conform-action=allow --exceed-action=deny-429 --enforce-on-key=IP \
  --src-ip-ranges='*' --description="per-IP throttle"
run gcloud compute backend-services update "$BACKEND" --project="$PROJECT" --global \
  --security-policy="$POLICY"

# URL map, managed certificate, proxy, address, forwarding rule.
ensure gcloud compute url-maps describe captions-rt-map --global --project="$PROJECT" \
  :: gcloud compute url-maps create captions-rt-map --project="$PROJECT" \
  --default-service="$BACKEND"
ensure gcloud compute ssl-certificates describe captions-rt-cert --global --project="$PROJECT" \
  :: gcloud compute ssl-certificates create captions-rt-cert --project="$PROJECT" --global \
  --domains="$DOMAIN"
ensure gcloud compute target-https-proxies describe captions-rt-proxy --project="$PROJECT" \
  :: gcloud compute target-https-proxies create captions-rt-proxy --project="$PROJECT" \
  --url-map=captions-rt-map --ssl-certificates=captions-rt-cert
ensure gcloud compute addresses describe captions-rt-ip --global --project="$PROJECT" \
  :: gcloud compute addresses create captions-rt-ip --project="$PROJECT" --global \
  --ip-version=IPV4
ensure gcloud compute forwarding-rules describe captions-rt-https --global --project="$PROJECT" \
  :: gcloud compute forwarding-rules create captions-rt-https --project="$PROJECT" --global \
  --load-balancing-scheme=EXTERNAL_MANAGED --network-tier=PREMIUM \
  --address=captions-rt-ip --target-https-proxy=captions-rt-proxy --ports=443

echo "# point an A record for $DOMAIN at this address, then wait for the certificate to turn ACTIVE:"
run gcloud compute addresses describe captions-rt-ip --global --project="$PROJECT" \
  --format="value(address)"
run gcloud compute ssl-certificates describe captions-rt-cert --global --project="$PROJECT" \
  --format="value(managed.status)"
