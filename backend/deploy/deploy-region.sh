#!/usr/bin/env bash
# Build and deploy the captions-rt Cloud Run service to one region. OPERATOR-RUN.
#
#   ./deploy-region.sh <project> <region> [--dry-run]
#
# Secrets are never passed as arguments: the Deepgram key and the JWT secret are read by Cloud Run
# from Secret Manager (secrets `deepgram-api-key` and `jwt-secret`, created once, see RUNBOOK.md).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<'USAGE'
usage: deploy-region.sh <project> <region> [--dry-run]
  region: us-east4 | europe-west4 | asia-southeast1
  --dry-run  print the gcloud commands without running anything
USAGE
}

parse_args "$@"
[ "${#POSITIONAL[@]}" -eq 2 ] || { usage >&2; exit 2; }
PROJECT="${POSITIONAL[0]}"
REGION="${POSITIONAL[1]}"
case "$REGION" in
  us-east4 | europe-west4 | asia-southeast1) ;;
  *) echo "unsupported region: $REGION" >&2; exit 2 ;;
esac
require_gcloud

SERVICE="captions-rt"
REPO="captions"
TAG="${IMAGE_TAG:-$(date -u +%Y%m%d%H%M%S)}"
IMAGE="$REGION-docker.pkg.dev/$PROJECT/$REPO/$SERVICE:$TAG"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ensure gcloud artifacts repositories describe "$REPO" --location="$REGION" --project="$PROJECT" \
  :: gcloud artifacts repositories create "$REPO" --repository-format=docker \
  --location="$REGION" --project="$PROJECT"

run gcloud builds submit "$BACKEND_DIR" --tag "$IMAGE" --project="$PROJECT"

run gcloud run deploy "$SERVICE" \
  --image="$IMAGE" \
  --project="$PROJECT" \
  --region="$REGION" \
  --platform=managed \
  --execution-environment=gen2 \
  --timeout=3600 \
  --concurrency=250 \
  --no-cpu-throttling \
  --cpu-boost \
  --min-instances=1 \
  --max-instances=20 \
  --cpu=1 \
  --memory=512Mi \
  --port=8080 \
  --allow-unauthenticated \
  --set-env-vars="REGION=$REGION" \
  --set-secrets="DEEPGRAM_API_KEY=deepgram-api-key:latest,JWT_SECRET=jwt-secret:latest"

echo "# service URL:"
run gcloud run services describe "$SERVICE" --project="$PROJECT" --region="$REGION" \
  --format="value(status.url)"
