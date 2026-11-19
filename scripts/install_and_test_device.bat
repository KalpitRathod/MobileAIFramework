@echo off
set ADB="C:\Users\Kalpit\AppData\Local\Android\Sdk\platform-tools\adb.exe"

echo =========================================================================
echo   MobileAI Framework - Emulator / Device Deployment & Testing
echo =========================================================================
echo.

echo [1/5] Checking connected devices / connecting to BlueStacks...
%ADB% connect 127.0.0.1:5555
%ADB% devices
echo.

echo [2/5] Installing Mobile AI Debug APK...
%ADB% install -r "%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
echo.

echo [3/5] Granting Camera and Audio permissions...
%ADB% shell pm grant com.phoneai.localmodel.debug android.permission.CAMERA
%ADB% shell pm grant com.phoneai.localmodel.debug android.permission.RECORD_AUDIO
echo.

echo [4/5] Pushing ultra-fast test models to /data/local/tmp/...
%ADB% push "%~dp0..\smollm2-360m-instruct-q4_k_m.gguf" /data/local/tmp/
%ADB% push "%~dp0..\whisper-base.en-Q8_0.gguf" /data/local/tmp/
%ADB% push "%~dp0..\all-minilm-l6-v2-q4_k_m.gguf" /data/local/tmp/
echo.

echo [5/5] Starting Mobile AI on device...
%ADB% shell am start -n com.phoneai.localmodel.debug/com.phoneai.localmodel.MainActivity
echo.
echo =========================================================================
echo   Deployment Complete! App is running on device/emulator.
echo =========================================================================
pause
