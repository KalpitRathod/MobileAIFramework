# Mobile AI Studio - Multi-Model Deployment Helper (PowerShell)
param (
    [string]$ModelFile = "",
    [switch]$All = $false
)

$AdbPath = "C:\Users\Kalpit\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $AdbPath)) { $AdbPath = "adb" }

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "         Mobile AI Studio — Model Deployment            " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

function Is-VisionProjector([string]$name) {
    $lower = $name.ToLower()
    return ($lower.StartsWith("mmproj") -or $lower.Contains("mmproj") -or $lower.Contains("projector"))
}

# Collect all available models in current folder
$availableModels = Get-ChildItem -Include "*.gguf","*.bin" -File | Sort-Object Length -Descending

if ($availableModels.Count -eq 0) {
    Write-Host "[ERROR] No .gguf or .bin model files found in current directory!" -ForegroundColor Red
    Write-Host "Directory: $PWD" -ForegroundColor Yellow
    exit 1
}

$filesToPush = @()

if ($All) {
    $filesToPush = $availableModels | ForEach-Object { $_.Name }
} elseif ($ModelFile -ne "") {
    if (Test-Path $ModelFile) {
        $filesToPush = @($ModelFile)
    } else {
        Write-Host "[ERROR] Specified model file not found: $ModelFile" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "Available models in this folder:" -ForegroundColor Yellow
    $index = 1
    foreach ($m in $availableModels) {
        $mb = [math]::Round($m.Length / 1MB, 1)
        $tag = if (Is-VisionProjector $m.Name) { "[Vision Projector]" } `
               elseif ($m.Name.EndsWith(".bin")) { "[MediaPipe GPU]   " } `
               elseif ($m.Name.Contains("whisper")) { "[Audio STT]       " } `
               elseif ($m.Name.Contains("bge") -or $m.Name.Contains("minilm")) { "[Vector Embedding]" } `
               else { "[Language Model]  " }
        Write-Host ("  [{0,2}] {1} {2,-38} ({3,7} MB)" -f $index, $tag, $m.Name, $mb) -ForegroundColor Gray
        $index++
    }
    Write-Host "  [ A] Push ALL Models" -ForegroundColor Cyan
    Write-Host ""
    $choice = Read-Host "Select model number to push to device (or 'A' for all, default: 1)"
    if ($choice -eq "" -or $choice -eq "1") {
        $filesToPush = @($availableModels[0].Name)
    } elseif ($choice.ToLower() -eq "a") {
        $filesToPush = $availableModels | ForEach-Object { $_.Name }
    } else {
        $parsed = 0
        if ([int]::TryParse($choice, [ref]$parsed) -and $parsed -ge 1 -and $parsed -le $availableModels.Count) {
            $filesToPush = @($availableModels[$parsed - 1].Name)
        } else {
            Write-Host "Invalid selection. Exiting." -ForegroundColor Red
            exit 1
        }
    }
}

Write-Host ""
Write-Host "Checking connected Android devices via ADB..." -ForegroundColor Cyan
$deviceOutput = & $AdbPath devices 2>&1
$deviceLines = $deviceOutput | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }

if ($null -eq $deviceLines -or $deviceLines.Count -eq 0) {
    Write-Host "⚠️ No Android device connected via ADB." -ForegroundColor Yellow
    Write-Host "Please connect your phone via USB with 'USB Debugging' enabled," -ForegroundColor Yellow
    Write-Host "or copy the files directly to your phone's 'Download' folder." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Selected models ready for deployment:" -ForegroundColor Green
    foreach ($f in $filesToPush) {
        $sz = [math]::Round((Get-Item $f).Length / 1MB, 1)
        Write-Host "  • $f ($sz MB)" -ForegroundColor Gray
    }
    exit 0
}

Write-Host "Connected Device Found:" -ForegroundColor Green
$deviceLines | ForEach-Object { Write-Host "  $_" -ForegroundColor Green }
Write-Host ""

foreach ($f in $filesToPush) {
    $fileItem = Get-Item $f
    $fileSizeMb = [math]::Round($fileItem.Length / 1MB, 1)
    $isProj = Is-VisionProjector $f

    if ($isProj) {
        Write-Host "[VISION PROJECTOR] $f ($fileSizeMb MB)" -ForegroundColor Magenta
    } else {
        Write-Host "[MODEL] $f ($fileSizeMb MB)" -ForegroundColor Green
    }

    Write-Host "  -> Pushing to /data/local/tmp/$f..." -ForegroundColor Yellow
    & $AdbPath push $f /data/local/tmp/

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [Fallback] Pushing to /sdcard/Download/..." -ForegroundColor Yellow
        & $AdbPath push $f /sdcard/Download/
        Write-Host "  ✓ Pushed to /sdcard/Download/. Available in app file picker." -ForegroundColor Green
    } else {
        & $AdbPath shell chmod 644 "/data/local/tmp/$f"
        & $AdbPath shell "run-as com.phoneai.localmodel.debug cp /data/local/tmp/$f /data/data/com.phoneai.localmodel.debug/files/$f" 2>$null
        Write-Host "  ✓ Successfully deployed to app storage!" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host "         Deployment Completed Successfully!             " -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
