# ==============================================================================
# Deploy MobileAI Hybrid Gateway + Web Control Plane to Google Cloud Run
# & Provision BigQuery Telemetry Dataset/Table (PowerShell)
# ==============================================================================
param(
    [string]$ProjectId = $env:GCP_PROJECT,
    [string]$Region = "us-central1",
    [string]$ServiceName = "mobileai-hybrid-gateway",
    [string]$GeminiApiKey = $env:GEMINI_API_KEY
)

# Load .env if present
if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        if ($_ -match "^([^#=]+)=(.*)$") {
            $k = $matches[1].Trim()
            $v = $matches[2].Trim()
            if ($k -eq "GEMINI_API_KEY" -and -not $GeminiApiKey) { $GeminiApiKey = $v }
            if ($k -eq "GCP_PROJECT" -and -not $ProjectId) { $ProjectId = $v -replace "^projects/", "" }
        }
    }
}

if (-not $ProjectId -or $ProjectId -match "^\d+$") {
    $detected = (gcloud config get-value project 2>$null)
    if ($detected) { $ProjectId = $detected }
}

if (-not $ProjectId) {
    Write-Error "Please specify a GCP Project ID (not number) or run 'gcloud config set project <PROJECT_ID>'"
    exit 1
}

Write-Host "🚀 Deploying $ServiceName + Web App to Google Cloud Run in $Region (Project: $ProjectId)..." -ForegroundColor Cyan

# 1. Enable required Google Cloud APIs
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com bigquery.googleapis.com storage.googleapis.com --project="$ProjectId"

# 2. Provision BigQuery Dataset & Table
Write-Host "📊 Provisioning BigQuery dataset & table (mobileai_telemetry.routing_events)..." -ForegroundColor Yellow
bq --project_id="$ProjectId" mk --dataset --location=US "${ProjectId}:mobileai_telemetry" 2>$null
Get-Content "bigquery_schema.sql" -Raw | bq --project_id="$ProjectId" query --use_legacy_sql=false

# 3. Deploy Cloud Run Service (FastAPI + Interactive Web App)
$envVars = "GCP_PROJECT=$ProjectId,BIGQUERY_DATASET=mobileai_telemetry,BIGQUERY_TABLE=routing_events"
if ($GeminiApiKey) {
    $envVars = "$envVars,GEMINI_API_KEY=$GeminiApiKey"
}

gcloud run deploy "$ServiceName" `
  --project="$ProjectId" `
  --source . `
  --platform managed `
  --region "$Region" `
  --allow-unauthenticated `
  --memory 512Mi `
  --set-env-vars "$envVars"

$serviceUrl = (gcloud run services describe "$ServiceName" --project="$ProjectId" --platform managed --region "$Region" --format "value(status.url)")
Write-Host ""
Write-Host "🎉 Shareable Web App & Gateway is LIVE at:" -ForegroundColor Green
Write-Host "   $serviceUrl" -ForegroundColor White
Write-Host "🔍 Checking health endpoint..." -ForegroundColor Cyan
Invoke-RestMethod -Uri "$serviceUrl/health" | ConvertTo-Json -Depth 4
