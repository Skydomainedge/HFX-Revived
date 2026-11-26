<#
.SYNOPSIS
    HybridFileXfer v4 测速造数 & SHA-256 完整性自动校验工具
.DESCRIPTION
    支持四种模式（支持双击交互式使用，也支持参数化自动脚本调用）：
      1) Generate    - 一键生成不可压缩随机二进制测试文件（及可选嵌套多文件），并自动写入 SHA256_BASELINE.txt
      2) VerifyAdb   - 比对 PC 目录与 Android 手机目录（经 adb shell sha256sum）逐文件字节大小与 SHA-256
      3) VerifyLocal - 比对本地两个目录（例如 PC->手机->PC 回传目录）逐文件字节大小与 SHA-256
      4) VerifyManifest - 根据目录内的 SHA256_BASELINE.txt 校验当前目录文件是否 100% 完整无损
#>
param(
    [ValidateSet('', 'Generate', 'VerifyAdb', 'VerifyLocal', 'VerifyManifest')]
    [string]$Action = '',
    [string]$OutDir = '',
    [int]$SizeMB = 100,
    [int]$ExtraSmallFiles = 0,
    [string]$PcDir = '',
    [string]$OtherDir = '',
    [string]$AndroidDir = '',
    [string]$Serial = '',
    [string]$AdbPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Find-AdbExecutable {
    param([string]$ExplicitPath)
    if ($ExplicitPath -and (Test-Path $ExplicitPath)) {
        return (Resolve-Path $ExplicitPath).Path
    }
    $candidates = @()
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        $candidates += $cmd.Source
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) {
            return (Resolve-Path $c).Path
        }
    }
    throw "未找到 adb.exe，请安装 Android platform-tools 或传入 -AdbPath。"
}

function Select-AdbDevice {
    param([string]$AdbBin, [string]$RequestedSerial)
    if ($RequestedSerial) {
        return $RequestedSerial
    }
    $lines = & $AdbBin devices 2>$null
    $devices = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device$') {
            $devices += $Matches[1]
        }
    }
    if ($devices.Count -eq 0) {
        throw "未检测到已授权的 ADB 设备（adb devices 为空）。"
    }
    if ($devices.Count -eq 1) {
        Write-Host "[ADB] 自动选定唯一设备: $($devices[0])" -ForegroundColor Cyan
        return $devices[0]
    }
    Write-Host "检测到多台 ADB 设备：" -ForegroundColor Yellow
    for ($i = 0; $i -lt $devices.Count; $i++) {
        Write-Host ("  [{0}] {1}" -f ($i + 1), $devices[$i])
    }
    while ($true) {
        $pick = Read-Host "请选择设备编号 (1-$($devices.Count))"
        $idx = 0
        if ([int]::TryParse($pick, [ref]$idx) -and $idx -ge 1 -and $idx -le $devices.Count) {
            return $devices[$idx - 1]
        }
    }
}

function Write-RandomBinaryFile {
    param([string]$FilePath, [long]$TotalBytes)
    $parent = Split-Path -Parent $FilePath
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $buf = New-Object byte[] (1024 * 1024)
    $fs = [System.IO.File]::Open($FilePath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        [long]$remaining = $TotalBytes
        while ($remaining -gt 0) {
            [int]$chunk = [int][Math]::Min($buf.Length, $remaining)
            if ($chunk -eq $buf.Length) {
                $rng.GetBytes($buf)
                $fs.Write($buf, 0, $chunk)
            } else {
                $tail = New-Object byte[] $chunk
                $rng.GetBytes($tail)
                $fs.Write($tail, 0, $chunk)
            }
            $remaining -= $chunk
        }
        $fs.Flush()
    } finally {
        $fs.Dispose()
        $rng.Dispose()
    }
}

function Get-Sha256Hex {
    param([string]$FilePath)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $fs = [System.IO.File]::OpenRead($FilePath)
    try {
        $hashBytes = $sha.ComputeHash($fs)
        return ([System.BitConverter]::ToString($hashBytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $fs.Dispose()
        $sha.Dispose()
    }
}

function Get-LocalDirectoryInventory {
    param([string]$RootDir)
    $resolvedRoot = (Resolve-Path $RootDir).Path.TrimEnd('\', '/')
    $items = @{}
    $files = Get-ChildItem -Path $resolvedRoot -Recurse -File | Where-Object {
        $_.Name -notin @('SHA256_BASELINE.txt', 'SHA256_BASELINE.json')
    } | Sort-Object FullName
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($resolvedRoot.Length + 1).Replace('\', '/')
        $hash = Get-Sha256Hex -FilePath $f.FullName
        $items[$rel] = [PSCustomObject]@{
            RelativePath = $rel
            Size         = [long]$f.Length
            Sha256       = $hash
        }
    }
    return $items
}

function Invoke-GenerateDataset {
    param([string]$TargetDir, [int]$MainSizeMB, [int]$SmallCount)
    if (-not $TargetDir) {
        $defaultDir = Join-Path ([Environment]::GetFolderPath('Desktop')) ("HFX_Bench_" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
        $inputDir = Read-Host "输出目录路径（回车默认: $defaultDir）"
        $TargetDir = if ([string]::IsNullOrWhiteSpace($inputDir)) { $defaultDir } else { $inputDir.Trim('"') }
    }
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

    $mainFileName = "bench_${MainSizeMB}MB_random.bin"
    $mainFilePath = Join-Path $TargetDir $mainFileName
    Write-Host "[造数] 正在生成不可压缩随机二进制文件: $mainFilePath ($MainSizeMB MB)..." -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Write-RandomBinaryFile -FilePath $mainFilePath -TotalBytes ([long]$MainSizeMB * 1024L * 1024L)
    $sw.Stop()
    Write-Host ("       生成完成，耗时 {0:N2} 秒" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green

    if ($SmallCount -gt 0) {
        $subDir = Join-Path $TargetDir 'small_files'
        New-Item -ItemType Directory -Force -Path $subDir | Out-Null
        Write-Host "[造数] 正在生成 $SmallCount 个嵌套中小随机文件 (32KB ~ 512KB)..." -ForegroundColor Cyan
        $rand = New-Object System.Random
        for ($i = 1; $i -le $SmallCount; $i++) {
            $sz = $rand.Next(32 * 1024, 512 * 1024)
            $sf = Join-Path $subDir ("sample_{0:D3}.bin" -f $i)
            Write-RandomBinaryFile -FilePath $sf -TotalBytes $sz
        }
    }

    Write-Host "[哈希] 正在计算基准 SHA-256 清单..." -ForegroundColor Cyan
    $inv = Get-LocalDirectoryInventory -RootDir $TargetDir
    $txtLines = @()
    $jsonRecords = @()
    foreach ($key in ($inv.Keys | Sort-Object)) {
        $rec = $inv[$key]
        $txtLines += ("{0}  {1}" -f $rec.Sha256, $rec.RelativePath)
        $jsonRecords += $rec
        Write-Host ("  OK  {0}  ({1:N0} B)  {2}" -f $rec.Sha256.Substring(0, 16), $rec.Size, $rec.RelativePath)
    }

    $txtPath = Join-Path $TargetDir 'SHA256_BASELINE.txt'
    $jsonPath = Join-Path $TargetDir 'SHA256_BASELINE.json'
    [System.IO.File]::WriteAllLines($txtPath, $txtLines, [System.Text.UTF8Encoding]::new($false))
    ($jsonRecords | ConvertTo-Json -Depth 4) | Set-Content -Path $jsonPath -Encoding UTF8

    Write-Host ""
    Write-Host "[完成] 共生成 $($inv.Count) 个测试文件，基准清单已写入:" -ForegroundColor Green
    Write-Host "       $txtPath" -ForegroundColor Green
    return 0
}

function Compare-Inventories {
    param(
        [hashtable]$SourceMap,
        [hashtable]$TargetMap,
        [string]$SourceLabel,
        [string]$TargetLabel
    )
    $allKeys = @($SourceMap.Keys) + @($TargetMap.Keys) | Sort-Object -Unique
    if ($allKeys.Count -eq 0) {
        Write-Host "[警告] 目录内没有可比对的文件！" -ForegroundColor Yellow
        return 2
    }

    $passed = 0
    $failed = 0

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  SHA-256 逐文件完整性比对结果" -ForegroundColor Cyan
    Write-Host "  源端 ($SourceLabel)  <->  目标端 ($TargetLabel)" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    foreach ($rel in $allKeys) {
        $inSrc = $SourceMap.ContainsKey($rel)
        $inDst = $TargetMap.ContainsKey($rel)
        if (-not $inSrc) {
            Write-Host "  [EXTRA]   仅目标端存在: $rel" -ForegroundColor Yellow
            $failed++
            continue
        }
        if (-not $inDst) {
            Write-Host "  [MISSING] 目标端缺失  : $rel" -ForegroundColor Red
            $failed++
            continue
        }
        $s = $SourceMap[$rel]
        $d = $TargetMap[$rel]
        if ($s.Size -ne $d.Size) {
            Write-Host ("  [FAIL]    大小不一致  : {0} (源={1:N0} B, 目标={2:N0} B)" -f $rel, $s.Size, $d.Size) -ForegroundColor Red
            $failed++
        } elseif ($s.Sha256 -ne $d.Sha256) {
            Write-Host ("  [FAIL]    SHA256不匹配: {0}`n            源  ={1}`n            目标={2}" -f $rel, $s.Sha256, $d.Sha256) -ForegroundColor Red
            $failed++
        } else {
            Write-Host ("  [PASS]    {0} ({1:N0} B)  SHA256={2}..." -f $rel, $s.Size, $s.Sha256.Substring(0, 16)) -ForegroundColor Green
            $passed++
        }
    }

    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
    if ($failed -eq 0) {
        Write-Host ("  [结果: PASS] 共校验 {0} 个文件，字节大小与 SHA-256 100% 零受损！" -f $passed) -ForegroundColor Green
        return 0
    } else {
        Write-Host ("  [结果: FAIL] 通过 {0} 个，异常/不匹配 {1} 个！" -f $passed, $failed) -ForegroundColor Red
        return 1
    }
}

function Get-AndroidDirectoryInventoryViaAdb {
    param([string]$AdbBin, [string]$DevSerial, [string]$RemoteDir)
    $cleanDir = $RemoteDir.Trim().TrimEnd('/')
    if (-not $cleanDir) { throw "手机目录路径不能为空。" }

    # Uses POSIX sh find + stat/wc + sha256sum available on Android toybox
    $remoteCmd = "cd '$cleanDir' 2>/dev/null || exit 42; find . -type f ! -name 'SHA256_BASELINE.txt' ! -name 'SHA256_BASELINE.json' | sort | while IFS= read -r f; do rel=`${f#./}; sz=`$(stat -c %s `"`$f`" 2>/dev/null || wc -c < `"`$f`"); h=`$(sha256sum `"`$f`" | awk '{print `$1}'); printf '%s|%s|%s\n' `"`$h`" `"`$sz`" `"`$rel`"; done"
    $output = & $AdbBin -s $DevSerial shell $remoteCmd 2>&1
    $code = $LASTEXITCODE
    if ($code -eq 42) {
        throw "手机端目录不存在或无法访问: $cleanDir"
    }
    if ($code -ne 0) {
        throw "ADB 远端计算 SHA-256 失败 (exit=$code): $($output -join "`n")"
    }

    $items = @{}
    foreach ($line in $output) {
        $trimmed = "$line".Trim()
        if (-not $trimmed) { continue }
        $parts = $trimmed -split '\|', 3
        if ($parts.Count -eq 3 -and $parts[0] -match '^[0-9a-fA-F]{64}$') {
            $rel = $parts[2].Trim()
            $items[$rel] = [PSCustomObject]@{
                RelativePath = $rel
                Size         = [long]($parts[1].Trim())
                Sha256       = $parts[0].ToLowerInvariant()
            }
        }
    }
    return $items
}

function Invoke-VerifyManifest {
    param([string]$TargetDir)
    $manifestPath = Join-Path $TargetDir 'SHA256_BASELINE.txt'
    if (-not (Test-Path $manifestPath)) {
        throw "目录内未找到基准清单文件: $manifestPath"
    }
    $expected = @{}
    foreach ($line in (Get-Content -Path $manifestPath -Encoding UTF8)) {
        if ($line -match '^([0-9a-fA-F]{64})\s+(.+)$') {
            $rel = $Matches[2].Trim().Replace('\', '/')
            $expected[$rel] = $Matches[1].ToLowerInvariant()
        }
    }
    $actual = Get-LocalDirectoryInventory -RootDir $TargetDir
    $passed = 0
    $failed = 0
    foreach ($rel in ($expected.Keys | Sort-Object)) {
        if (-not $actual.ContainsKey($rel)) {
            Write-Host "  [MISSING] $rel" -ForegroundColor Red
            $failed++
        } elseif ($actual[$rel].Sha256 -ne $expected[$rel]) {
            Write-Host "  [FAIL]    $rel (SHA-256 不匹配)" -ForegroundColor Red
            $failed++
        } else {
            Write-Host ("  [PASS]    {0} ({1:N0} B)" -f $rel, $actual[$rel].Size) -ForegroundColor Green
            $passed++
        }
    }
    if ($failed -eq 0 -and $passed -gt 0) {
        Write-Host "[结果: PASS] 清单内全部 $passed 个文件 SHA-256 100% 一致！" -ForegroundColor Green
        return 0
    }
    Write-Host "[结果: FAIL] 通过 $passed，失败 $failed！" -ForegroundColor Red
    return 1
}

try {
    if (-not $Action) {
        Write-Host "============================================================" -ForegroundColor Cyan
        Write-Host "  HybridFileXfer v4 - 测速造数 & SHA-256 完整性校验工具" -ForegroundColor Cyan
        Write-Host "============================================================" -ForegroundColor Cyan
        Write-Host "  [1] 一键生成随机二进制测速文件 + 基准 SHA-256 清单"
        Write-Host "  [2] 比对 PC 目录 <-> Android 手机目录 (经 ADB sha256sum)"
        Write-Host "  [3] 比对 本地两个目录 (PC <-> PC 回传校验)"
        Write-Host "  [4] 按目录内 SHA256_BASELINE.txt 校验完整性"
        Write-Host ""
        $pick = Read-Host "请选择功能编号 (1-4)"
        switch ($pick) {
            '1' {
                $Action = 'Generate'
                $szInput = Read-Host "主测速文件大小（MB，回车默认 100）"
                if ($szInput) { $SizeMB = [int]$szInput }
                $smInput = Read-Host "额外生成嵌套中小文件个数（回车默认 0，推荐 20 测试多文件 LRU 池）"
                if ($smInput) { $ExtraSmallFiles = [int]$smInput }
            }
            '2' { $Action = 'VerifyAdb' }
            '3' { $Action = 'VerifyLocal' }
            '4' { $Action = 'VerifyManifest' }
            default { throw "无效选项: $pick" }
        }
    }

    switch ($Action) {
        'Generate' {
            $rc = Invoke-GenerateDataset -TargetDir $OutDir -MainSizeMB $SizeMB -SmallCount $ExtraSmallFiles
            exit $rc
        }
        'VerifyLocal' {
            if (-not $PcDir) { $PcDir = (Read-Host "请输入源目录路径（例如 PC 发送目录）").Trim('"') }
            if (-not $OtherDir) { $OtherDir = (Read-Host "请输入目标目录路径（例如回传接收目录）").Trim('"') }
            Write-Host "[扫描] 正在计算源目录 SHA-256..." -ForegroundColor Cyan
            $srcMap = Get-LocalDirectoryInventory -RootDir $PcDir
            Write-Host "[扫描] 正在计算目标目录 SHA-256..." -ForegroundColor Cyan
            $dstMap = Get-LocalDirectoryInventory -RootDir $OtherDir
            $rc = Compare-Inventories -SourceMap $srcMap -TargetMap $dstMap -SourceLabel $PcDir -TargetLabel $OtherDir
            exit $rc
        }
        'VerifyAdb' {
            if (-not $PcDir) { $PcDir = (Read-Host "请输入 PC 目录路径").Trim('"') }
            if (-not $AndroidDir) { $AndroidDir = (Read-Host "请输入手机目录路径（如 /sdcard/Download/HFX_Bench）").Trim('"') }
            $adbBin = Find-AdbExecutable -ExplicitPath $AdbPath
            $devSerial = Select-AdbDevice -AdbBin $adbBin -RequestedSerial $Serial
            Write-Host "[扫描] 正在计算 PC 目录 SHA-256..." -ForegroundColor Cyan
            $pcMap = Get-LocalDirectoryInventory -RootDir $PcDir
            Write-Host "[扫描] 正在通过 ADB 计算手机目录 SHA-256 ($AndroidDir)..." -ForegroundColor Cyan
            $androidMap = Get-AndroidDirectoryInventoryViaAdb -AdbBin $adbBin -DevSerial $devSerial -RemoteDir $AndroidDir
            $rc = Compare-Inventories -SourceMap $pcMap -TargetMap $androidMap -SourceLabel "PC:$PcDir" -TargetLabel "Android:$AndroidDir"
            exit $rc
        }
        'VerifyManifest' {
            if (-not $PcDir) { $PcDir = (Read-Host "请输入包含 SHA256_BASELINE.txt 的目录路径").Trim('"') }
            $rc = Invoke-VerifyManifest -TargetDir $PcDir
            exit $rc
        }
    }
} catch {
    Write-Host "[错误] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
