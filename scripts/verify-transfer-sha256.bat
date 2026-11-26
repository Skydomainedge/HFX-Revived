@echo off
chcp 65001 >nul
title HybridFileXfer v4 - 测速造数与 SHA-256 完整性校验工具
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-transfer-sha256.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
if "%~1"=="" (
    echo.
    pause
)
exit /b %EXIT_CODE%
