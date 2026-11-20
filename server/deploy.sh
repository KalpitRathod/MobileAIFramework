#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${1:-${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}}"
PROJECT_ID="${PROJECT_ID#projects/}"
REGION="${2:-us-central1}"
SERVICE_NAME="${3:-mobileai-hybrid-gateway}"

if [ -z "$PROJECT_ID" ]; then
  echo "Usage: ./deploy.sh <GCP_PROJECT_ID> [REGION] [SERVICE_NAME]"
  exit 1
fi

echo "🚀 Deploying $SERVICE_NAME + Web App to Google Cloud Run ($REGION / $PROJECT_ID)..."

gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com bigquery.googleapis.com storage.googleapis.com --project="$PROJECT_ID"

echo "📊 Provisioning BigQuery dataset & table..."
bq --project_id="$PROJECT_ID" mk --dataset --location=US "${PROJECT_ID}:mobileai_telemetry" 2>/dev/null || true
bq --project_id="$PROJECT_ID" query --use_legacy_sql=false < bigquery_schema.sql

ENV_VARS="GCP_PROJECT=${PROJECT_ID},BIGQUERY_DATASET=mobileai_telemetry,BIGQUERY_TABLE=routing_events"
if [ -n "${GEMINI_API_KEY:-}" ]; then
  ENV_VARS="${ENV_VARS},GEMINI_API_KEY=${GEMINI_API_KEY}"
fi

gcloud run deploy "$SERVICE_NAME" \
  --project="$PROJECT_ID" \
  --source . \
  --platform managed \
  --region "$REGION" \
  --allow-unauthenticated \
  --memory 512Mi \
  --set-env-vars "$ENV_VARS"

URL=$(gcloud run services describe "$SERVICE_NAME" --project="$PROJECT_ID" --platform managed --region "$REGION" --format 'value(status.url)')
echo "🎉 Shareable Web App & Gateway is LIVE at: $URL"
curl -s "$URL/health"
echo ""
