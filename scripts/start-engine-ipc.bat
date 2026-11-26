@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title HybridFileXfer v4 - 无头引擎 IPC 守护进程 (hfx-engine)

set "REPO_ROOT=%~dp0.."
set "JAR_PATH="
set "IPC_INFO=%LOCALAPPDATA%\HybridFileXfer\engine.ipc.json"

:: 1. 自动定位最新的 hfx-engine Fat JAR
for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-engine\build\libs\hfx-engine-*.jar" 2^>nul') do (
    if not defined JAR_PATH set "JAR_PATH=%REPO_ROOT%\pc-engine\build\libs\%%F"
)
if not defined JAR_PATH if exist "%~dp0hfx-engine-4.0.0-SNAPSHOT.jar" (
    set "JAR_PATH=%~dp0hfx-engine-4.0.0-SNAPSHOT.jar"
)

if not defined JAR_PATH (
    echo [提示] 未找到已构建的 hfx-engine JAR。
    set /p "DO_BUILD=是否现在自动运行 Gradle 构建 :pc-engine:shadowJar？(Y/n): "
    if /i "!DO_BUILD!"=="n" goto :end_fail
    pushd "%REPO_ROOT%"
    call gradlew.bat :pc-engine:shadowJar --no-daemon
    if errorlevel 1 (
        popd
        echo [错误] Gradle 构建失败。
        goto :end_fail
    )
    popd
    for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-engine\build\libs\hfx-engine-*.jar" 2^>nul') do (
        if not defined JAR_PATH set "JAR_PATH=%REPO_ROOT%\pc-engine\build\libs\%%F"
    )
)

if not defined JAR_PATH (
    echo [错误] 未找到 hfx-engine-*.jar。
    goto :end_fail
)

echo ============================================================
echo   HybridFileXfer v4 - PC 无头业务内核守护模式 (JSON-RPC 2.0)
echo   JAR     : !JAR_PATH!
echo   Endpoint: tcp://127.0.0.1:0 (随机环回端口 + 一次性 ipcToken)
echo   MetaFile: %IPC_INFO%
echo ============================================================
echo.
echo [提示] 正在后台等待 engine.ipc.json 生成后打印端点信息；按 Ctrl+C 可优雅停止并自动清理元文件。
echo.

:: 启动一个轻量异步查看器在元文件生成后打印端点和 Token
start "" /b powershell -NoProfile -Command "$p='%IPC_INFO%'; for($i=0;$i -lt 50;$i++){ if(Test-Path $p){ Start-Sleep -Milliseconds 150; Write-Host '--- engine.ipc.json Ready ---' -ForegroundColor Green; Get-Content $p; Write-Host '-----------------------------' -ForegroundColor Green; break } Start-Sleep -Milliseconds 100 }"

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "!JAR_PATH!" --ipc=tcp://127.0.0.1:0

echo.
echo [结束] 无头引擎已退出，engine.ipc.json 已自动清理。
pause
exit /b 0

:end_fail
echo.
pause
exit /b 1
