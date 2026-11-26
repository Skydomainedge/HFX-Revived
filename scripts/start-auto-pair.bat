@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title HybridFileXfer v4 - 零配置自动发现与扫码绑定 (Win11 Toast)

set "REPO_ROOT=%~dp0.."
set "LAUNCHER_EXE="
set "ENGINE_JAR="
set "SHELL_JAR="

:: 1. 优先定位 Win11 原生 Toast 宿主 HybridFileXferLauncher.exe
for %%P in (
    "%~dp0HybridFileXferLauncher.exe"
    "%~dp0launcher\HybridFileXferLauncher.exe"
    "%REPO_ROOT%\HybridFileXferLauncher\bin\Release\net8.0-windows10.0.19041.0\HybridFileXferLauncher.exe"
    "%REPO_ROOT%\HybridFileXferLauncher\bin\Debug\net8.0-windows10.0.19041.0\HybridFileXferLauncher.exe"
    "%REPO_ROOT%\HybridFileXferLauncher\bin\x64\Release\net8.0-windows10.0.19041.0\HybridFileXferLauncher.exe"
    "%REPO_ROOT%\HybridFileXferLauncher\bin\x64\Debug\net8.0-windows10.0.19041.0\HybridFileXferLauncher.exe"
) do (
    if not defined LAUNCHER_EXE if exist %%~P set "LAUNCHER_EXE=%%~P"
)

for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-engine\build\libs\hfx-engine-*.jar" 2^>nul') do (
    if not defined ENGINE_JAR set "ENGINE_JAR=%REPO_ROOT%\pc-engine\build\libs\%%F"
)
if not defined ENGINE_JAR if exist "%~dp0hfx-engine-4.0.0-SNAPSHOT.jar" (
    set "ENGINE_JAR=%~dp0hfx-engine-4.0.0-SNAPSHOT.jar"
)

for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-shell\build\libs\hfx-shell-*.jar" 2^>nul') do (
    if not defined SHELL_JAR set "SHELL_JAR=%REPO_ROOT%\pc-shell\build\libs\%%F"
)
if not defined SHELL_JAR if exist "%~dp0hfx-shell-4.0.0-SNAPSHOT.jar" (
    set "SHELL_JAR=%~dp0hfx-shell-4.0.0-SNAPSHOT.jar"
)

echo ============================================================
echo   HybridFileXfer v4 - mDNS 零配置自动发现 / 图片式 QR 扫码绑定
echo ============================================================
echo.
echo 请选择启动模式（直接回车 = 推荐自动模式）：
echo   [回车] 自动发现已信任手机并秒连；未发现则自动弹出 Win11 扫码通知
echo   [pair] 立即生成 480x480 PNG 二维码并推送 Win11 通知（新手机首次绑定）
set "MODE_INPUT="
set /p "MODE_INPUT=请输入模式或直接回车: "

set "PAIR_ARG="
if /i "!MODE_INPUT!"=="pair" set "PAIR_ARG=--pair"

if defined LAUNCHER_EXE if defined ENGINE_JAR (
    echo.
    echo [启动] Win11 原生 Toast 宿主: !LAUNCHER_EXE!
    echo [引擎] !ENGINE_JAR!
    echo.
    "!LAUNCHER_EXE!" --engine-jar="!ENGINE_JAR!" !PAIR_ARG!
    goto :done
)

if defined SHELL_JAR (
    echo.
    echo [启动] PC Shell 轻量模式（Win11 Toast 兜底）: !SHELL_JAR!
    if "!PAIR_ARG!"=="" set "PAIR_ARG=--auto"
    java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "!SHELL_JAR!" !PAIR_ARG!
    goto :done
)

echo [错误] 未找到已构建的 HybridFileXferLauncher.exe / hfx-engine.jar / hfx-shell.jar。
echo        请先在项目根目录执行：
echo          gradlew.bat :pc-engine:shadowJar :pc-shell:shadowJar --no-daemon
echo          dotnet build HybridFileXferLauncher\HybridFileXferLauncher.csproj
pause
exit /b 1

:done
echo.
echo [结束] 会话已退出（退出码: !ERRORLEVEL!）。
pause
exit /b 0
