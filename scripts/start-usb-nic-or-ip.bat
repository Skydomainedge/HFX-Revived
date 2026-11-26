@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title HybridFileXfer v4 - USB 网卡 / 直连 IP 高速启动

set "REPO_ROOT=%~dp0.."
set "JAR_PATH="
set "STATE_DIR=%LOCALAPPDATA%\HybridFileXfer"
set "LAST_HOST_FILE=%STATE_DIR%\last_direct_host.txt"

:: 1. 自动定位最新的 hfx-shell Fat JAR
for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-shell\build\libs\hfx-shell-*.jar" 2^>nul') do (
    if not defined JAR_PATH set "JAR_PATH=%REPO_ROOT%\pc-shell\build\libs\%%F"
)
if not defined JAR_PATH if exist "%~dp0hfx-shell-4.0.0-SNAPSHOT.jar" (
    set "JAR_PATH=%~dp0hfx-shell-4.0.0-SNAPSHOT.jar"
)

if not defined JAR_PATH (
    echo [提示] 未找到已构建的 hfx-shell JAR。
    set /p "DO_BUILD=是否现在自动运行 Gradle 构建 :pc-shell:shadowJar？(Y/n): "
    if /i "!DO_BUILD!"=="n" goto :end_fail
    pushd "%REPO_ROOT%"
    call gradlew.bat :pc-shell:shadowJar --no-daemon
    if errorlevel 1 (
        popd
        echo [错误] Gradle 构建失败。
        goto :end_fail
    )
    popd
    for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-shell\build\libs\hfx-shell-*.jar" 2^>nul') do (
        if not defined JAR_PATH set "JAR_PATH=%REPO_ROOT%\pc-shell\build\libs\%%F"
    )
)

if not defined JAR_PATH (
    echo [错误] 未找到 hfx-shell-*.jar。
    goto :end_fail
)

set "LAST_HOST="
if exist "%LAST_HOST_FILE%" (
    set /p LAST_HOST=<"%LAST_HOST_FILE%"
)

echo ============================================================
echo   HybridFileXfer v4 - USB 网卡 (NCM/RNDIS) / Wi-Fi IP 直连
echo   自动启用同网段源 IP bind（防多网关/代理 TUN 路由误判）
echo   JAR: !JAR_PATH!
echo ============================================================
echo.
if defined LAST_HOST (
    echo 上次连接 IP: !LAST_HOST!
    set /p "TARGET_HOST=请输入手机端显示的 USB 网卡或 Wi-Fi IP（直接回车复用 !LAST_HOST!）: "
    if "!TARGET_HOST!"=="" set "TARGET_HOST=!LAST_HOST!"
) else (
    set /p "TARGET_HOST=请输入手机端显示的 USB 网卡 (如 10.x.x.x) 或 Wi-Fi IP (如 192.168.x.x): "
)

if "!TARGET_HOST!"=="" (
    echo [错误] 未输入有效 IP 地址。
    goto :end_fail
)

if not exist "%STATE_DIR%" mkdir "%STATE_DIR%" >nul 2>&1
>"%LAST_HOST_FILE%" echo !TARGET_HOST!

set "BIND_ARG="
set "DISABLE_BIND="
set /p "DISABLE_BIND=按回车启用自动同网段 bind（输入 n 关闭自动 bind）: "
if /i "!DISABLE_BIND!"=="n" set "BIND_ARG=--no-auto-bind"

echo.
echo [启动] 正在直连 !TARGET_HOST!:5740 ...
echo 命令: java -jar "!JAR_PATH!" -c !TARGET_HOST! !BIND_ARG!
echo.
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "!JAR_PATH!" -c !TARGET_HOST! !BIND_ARG!

echo.
echo [结束] 连接会话已退出（退出码: !ERRORLEVEL!）。
pause
exit /b 0

:end_fail
echo.
pause
exit /b 1
