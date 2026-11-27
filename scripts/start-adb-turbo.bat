@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title HybridFileXfer v4 - ADB Turbo Launcher

set "REPO_ROOT=%~dp0.."
set "JAR_PATH="

:: 1. 自动定位最新的 hfx-shell Fat JAR
for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-shell\build\libs\hfx-shell-*.jar" 2^>nul') do (
    if not defined JAR_PATH set "JAR_PATH=%REPO_ROOT%\pc-shell\build\libs\%%F"
)
if not defined JAR_PATH if exist "%~dp0hfx-shell-4.0.0-SNAPSHOT.jar" (
    set "JAR_PATH=%~dp0hfx-shell-4.0.0-SNAPSHOT.jar"
)

if defined JAR_PATH goto :jar_ready
echo [提示] 未找到已构建的 hfx-shell JAR：
echo        %REPO_ROOT%\pc-shell\build\libs\hfx-shell-*.jar
set /p "DO_BUILD=是否现在自动运行 Gradle 构建 :pc-shell:shadowJar？(Y/n): "
if /i "!DO_BUILD!"=="n" goto :end_fail
pushd "%REPO_ROOT%"
call gradlew.bat :pc-shell:shadowJar --no-daemon
if errorlevel 1 (
    popd
    echo [错误] Gradle 构建失败，请检查 JDK 环境或错误日志。
    goto :end_fail
)
popd
for /f "delims=" %%F in ('dir /b /o-d "%REPO_ROOT%\pc-shell\build\libs\hfx-shell-*.jar" 2^>nul') do (
    if not defined JAR_PATH set "JAR_PATH=%REPO_ROOT%\pc-shell\build\libs\%%F"
)

:jar_ready
if not defined JAR_PATH (
    echo [错误] 仍未找到 hfx-shell-*.jar，请先在项目根目录执行：
    echo        gradlew.bat :pc-shell:shadowJar --no-daemon
    goto :end_fail
)

echo ============================================================
echo   HybridFileXfer v4 - ADB 极速有线轨启动器
echo   JAR: !JAR_PATH!
echo ============================================================
echo.

:: 2. 探测 adb 可执行文件（用于交互选设备与离线自愈；建连时 pc-engine 仍会自动降序择优）
set "ADB_BIN="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_BIN=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
if not defined ADB_BIN (
    for /f "delims=" %%A in ('where adb 2^>nul') do (
        if not defined ADB_BIN set "ADB_BIN=%%A"
    )
)
if not defined ADB_BIN if exist "%~dp0adb.exe" set "ADB_BIN=%~dp0adb.exe"

set "SELECTED_SERIAL="
if not defined ADB_BIN (
    echo [警告] 未在 Android SDK / PATH 找到 adb.exe，将由 pc-engine 自行扫描。
    goto :select_mode
)

echo [探测] 使用 ADB 枚举设备: !ADB_BIN!
call :scan_adb_devices

if !DEV_COUNT! EQU 0 if !OFFLINE_COUNT! GTR 0 (
    echo [恢复] 检测到离线/僵死 ADB 设备（!OFFLINE_COUNT! 台），正在执行 adb reconnect offline ...
    call :run_adb_reconnect_offline
    ping -n 3 127.0.0.1 >nul
    call :scan_adb_devices
)

if !DEV_COUNT! EQU 0 goto :no_online_device
if !DEV_COUNT! EQU 1 goto :single_device
goto :multi_device

:no_online_device
echo [警告] 当前未检测到已授权的在线 ADB 设备（请确认已插线并开启 USB 调试）。
echo        仍可直接回车让 pc-engine 尝试自动恢复并连接，或 Ctrl+C 取消。
goto :select_mode

:single_device
set "SELECTED_SERIAL=!DEV_1!"
echo [设备] 自动选定唯一设备: !SELECTED_SERIAL!
goto :select_mode

:multi_device
echo 检测到多台 ADB 设备：
for /l %%i in (1,1,!DEV_COUNT!) do (
    echo   [%%i] !DEV_%%i!
)

:choose_dev
set "CHOICE="
set /p "CHOICE=请输入设备编号 (1-!DEV_COUNT!): "
set /a "NUM_CHOICE=!CHOICE!" 2>nul
if !NUM_CHOICE! LSS 1 goto :choose_dev
if !NUM_CHOICE! GTR !DEV_COUNT! goto :choose_dev
call set "SELECTED_SERIAL=%%DEV_!NUM_CHOICE!%%"
goto :select_mode

:select_mode
echo.
echo 模式选项（直接回车 = 推荐 ADB Turbo 自动模式）：
echo   [回车]   自动模式（优先新版 ADB + 注入 LIBUSB/BURST + 缺失 delayed_ack 自动 4 流）
echo   [1-8]    强制指定 ADB 并发流数（例如输入 4 或 2）
echo   [legacy] 旧版同步对比模式（关闭 libusb/burst，用于对比测速）
set "MODE_INPUT="
set /p "MODE_INPUT=请选择模式或直接回车: "
if defined MODE_INPUT set "MODE_INPUT=!MODE_INPUT: =!"

set "EXTRA_ARGS="
if /i "!MODE_INPUT!"=="legacy" (
    set "EXTRA_ARGS=--adb-legacy"
) else if not "!MODE_INPUT!"=="" (
    set "EXTRA_ARGS=--adb-streams=!MODE_INPUT!"
)

set "DIR_INPUT="
set /p "DIR_INPUT=可选默认目录 -d（回车默认显示盘符根目录 /）: "
if not "!DIR_INPUT!"=="" (
    set "EXTRA_ARGS=!EXTRA_ARGS! -d "!DIR_INPUT!""
)

if defined SELECTED_SERIAL (
    set "SERIAL_ARG=-s !SELECTED_SERIAL!"
) else (
    set "SERIAL_ARG="
)

echo.
echo [启动] 请确保手机端已点击「启动服务器并等待连接」...
echo 命令: java -jar "!JAR_PATH!" -c adb !SERIAL_ARG! !EXTRA_ARGS!
echo.
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "!JAR_PATH!" -c adb !SERIAL_ARG! !EXTRA_ARGS!

echo.
echo [结束] 连接会话已退出（退出码: !ERRORLEVEL!）。
pause
exit /b 0

:scan_adb_devices
set "DEV_COUNT=0"
set "OFFLINE_COUNT=0"
set "_PREV_LIBUSB=%ADB_LIBUSB%"
set "_PREV_BURST=%ADB_BURST_MODE%"
set "ADB_LIBUSB=1"
set "ADB_BURST_MODE=1"
for /f "skip=1 tokens=1,2" %%a in ('"!ADB_BIN!" devices 2^>nul') do (
    if "%%b"=="device" (
        set /a DEV_COUNT+=1
        set "DEV_!DEV_COUNT!=%%a"
    ) else if "%%b"=="offline" (
        set /a OFFLINE_COUNT+=1
    )
)
set "ADB_LIBUSB=%_PREV_LIBUSB%"
set "ADB_BURST_MODE=%_PREV_BURST%"
set "_PREV_LIBUSB="
set "_PREV_BURST="
exit /b 0

:run_adb_reconnect_offline
setlocal
set "ADB_LIBUSB=1"
set "ADB_BURST_MODE=1"
"!ADB_BIN!" reconnect offline 2>nul
endlocal
exit /b 0

:end_fail
echo.
pause
exit /b 1
