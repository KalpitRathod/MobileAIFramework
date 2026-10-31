@echo off
setlocal
echo ========================================================
echo         Mobile AI Studio - Model Deployment
echo ========================================================
echo.
powershell -ExecutionPolicy Bypass -File "%~dp0push_model.ps1" %*
