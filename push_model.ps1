# Mobile AI Studio - Model Deployment Helper (PowerShell)
param (
    [string]$ModelFile = ""
)

$AdbPath = "C:\Users\Kalpit\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $AdbPath)) { $AdbPath = "adb" }

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "         Mobile AI Studio - Model Deployment            " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

function Is-VisionProjector([string]$name) {
    $lower = $name.ToLower()
    return ($lower.StartsWith("mmproj") -or $lower.Contains("mmproj") -or $lower.Contains("projector"))
}

# Auto-detect base model candidates
$baseModelCandidate = $null
if ($ModelFile -ne "") {
    $baseModelCandidate = $ModelFile
} else {
    if (Test-Path "smollm2-360m-instruct-q4_k_m.gguf") {
        $baseModelCandidate = "smollm2-360m-instruct-q4_k_m.gguf"
    } elseif (Test-Path "qwen2.5-0.5b-instruct-q4_k_m.gguf") {
        $baseModelCandidate = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    } elseif (Test-Path "gemma-2-2b-it-q4_k_m.gguf") {
        $baseModelCandidate = "gemma-2-2b-it-q4_k_m.gguf"
    } else {
        $candidate = Get-ChildItem -Filter "*.gguf" | Where-Object { -not (Is-VisionProjector $_.Name) } | Select-Object -First 1
        if ($null -ne $candidate) {
            $baseModelCandidate = $candidate.Name
        }
    }
}

# Auto-detect vision projector candidates (e.g. mmproj-GLM-OCR-Q8_0.gguf)
$projectorCandidate = Get-ChildItem -Filter "*.gguf" | Where-Object { Is-VisionProjector $_.Name } | Select-Object -First 1

$filesToPush = @()

if ($null -ne $baseModelCandidate -and (Test-Path $baseModelCandidate)) {
    $filesToPush += $baseModelCandidate
}

if ($null -ne $projectorCandidate) {
    $filesToPush += $projectorCandidate.Name
}

if ($filesToPush.Count -eq 0) {
    # If the user passed or only has an mmproj file
    $anyGguf = Get-ChildItem -Filter "*.gguf" | Select-Object -First 1
    if ($null -ne $anyGguf) {
        $filesToPush += $anyGguf.Name
    }
}

if ($filesToPush.Count -eq 0) {
    Write-Host "[ERROR] No .gguf model files found in current directory!" -ForegroundColor Red
    Write-Host "Place your .gguf file here: $PWD" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Recommended compact base models for mobile:" -ForegroundColor Cyan
    Write-Host "  1. SmolLM2-360M: https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF" -ForegroundColor Gray
    Write-Host "  2. Qwen2.5-0.5B: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF" -ForegroundColor Gray
    exit 1
}

Write-Host "Checking connected Android devices via ADB..." -ForegroundColor Gray
& $AdbPath devices

foreach ($f in $filesToPush) {
    $isProj = Is-VisionProjector $f
    $fileSizeMb = [math]::Round((Get-Item $f).Length / 1MB, 1)

    if ($isProj) {
        Write-Host ""
        Write-Host "[VISION PROJECTOR] $f ($fileSizeMb MB)" -ForegroundColor Magenta
        Write-Host "  Multimodal projection adapter detected." -ForegroundColor DarkGray
    } else {
        Write-Host ""
        Write-Host "[BASE LANGUAGE MODEL] $f ($fileSizeMb MB)" -ForegroundColor Green
    }

    Write-Host "  -> Pushing to /data/local/tmp/$f..." -ForegroundColor Yellow
    & $AdbPath push $f /data/local/tmp/

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [Fallback] Pushing to /sdcard/Download/..." -ForegroundColor Yellow
        & $AdbPath push $f /sdcard/Download/
        Write-Host "  Pushed to /sdcard/Download/. In app, tap 'Select Model File'." -ForegroundColor Green
    } else {
        & $AdbPath shell chmod 644 "/data/local/tmp/$f"
        & $AdbPath shell "run-as com.phoneai.localmodel.debug cp /data/local/tmp/$f /data/data/com.phoneai.localmodel.debug/files/$f" 2>$null
        Write-Host "  -> Successfully deployed to app storage!" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host "Deployment complete! Launch Mobile AI Studio on device." -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
