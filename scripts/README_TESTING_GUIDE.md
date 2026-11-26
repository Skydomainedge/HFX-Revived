# HybridFileXfer v4 快捷启动脚本与 SHA-256 完整性测速指南

本目录提供 Windows 环境下可直接**双击运行**的 JAR 快捷启动脚本、无头 IPC 引擎调试脚本，以及一键造数 + **SHA-256 零损坏自动校验工具**。

> **前置条件**：
> - Windows 10/11 已安装 JDK 17+（推荐 JDK 21）且 `java` 在 `PATH` 中；
> - 若首次运行尚未构建 JAR，各启动脚本会自动提示并一键执行对应 Gradle 构建任务（或在仓库根目录手动运行 `gradlew.bat :pc-shell:shadowJar :pc-engine:shadowJar --no-daemon`）。

---

## 一、 脚本一览与双击使用说明

| 脚本文件 | 用途 | 双击运行效果 |
|---|---|---|
| `start-adb-turbo.bat` | **ADB 极速有线轨启动** | 自动定位最新 `hfx-shell-*.jar` 与最高版 `adb.exe`，自动枚举已连设备（单设备直接选定），支持三种模式：<br>• 直接回车：**ADB Turbo 自动模式**（进程级注入 `ADB_LIBUSB=1` + `ADB_BURST_MODE=1`，缺失 `delayed_ack` 时自动启用 4 并发流）；<br>• 输入 `1`~`8`：强制指定并发流数（如 `4` / `2`）；<br>• 输入 `legacy`：旧版同步模式（`--adb-legacy`），用于与 Turbo 做 A/B 对比测速。 |
| `start-usb-nic-or-ip.bat` | **USB 网卡 (NCM/RNDIS) / Wi-Fi 直连 IP 启动** | 交互式输入手机端展示的 USB 网卡 IP（`ncm0`/`rndis0`，如 `10.x.x.x`）或 Wi-Fi IP（`192.168.x.x`），**自动记忆上次 IP**（保存在 `%LOCALAPPDATA%\HybridFileXfer\last_direct_host.txt`，下次直接回车复用），默认自动同网段源 IP `bind` 防多网关/代理 TUN 路由误判。 |
| `start-engine-ipc.bat` | **PC 无头引擎 IPC 守护进程启动** | 启动 `hfx-engine-*.jar --ipc=tcp://127.0.0.1:0`，生成一次性随机 `ipcToken` 并原子写入 `%LOCALAPPDATA%\HybridFileXfer\engine.ipc.json`，自动在窗口打印监听端点、PID 与 Token；按 `Ctrl+C` 优雅退出并自动删除 `engine.ipc.json`。 |
| `verify-transfer-sha256.bat` | **测速造数 & SHA-256 零损坏校验器** | 双击打开中文交互菜单（底层调用同目录 `verify-transfer-sha256.ps1`）：<br>1. 一键生成不可压缩随机二进制测速文件 + 基准 `SHA256_BASELINE.txt`；<br>2. **PC 目录 ↔ 手机目录**（经 `adb shell sha256sum`）逐文件校验字节大小与 SHA-256；<br>3. **本地双目录（PC ↔ PC 回传）**逐文件校验字节大小与 SHA-256；<br>4. 根据目录内 `SHA256_BASELINE.txt` 校验完整性。 |

---

## 二、 推荐测速流程：ADB 对比 USB 虚拟网卡（A/B 实测）

### 准备阶段：生成不可压缩随机测试集
1. 双击运行 `verify-transfer-sha256.bat`，选择 **`[1]`**；
2. 建议生成 **500 MB ~ 1024 MB** 主文件（例如桌面 `HFX_Bench_xxx` 目录），可选附带 **20 个嵌套中小文件**（同时验证异构多轨下的 LRU 文件句柄池与目录时间戳回填）；
3. 脚本会自动在目录内生成 `SHA256_BASELINE.txt`。

### 对比组 A：有线 ADB 极速轨 vs 旧版同步轨
1. 手机端安装新版 APK（M3 起打开「传输文件」后，右侧 PC 面板**首屏默认展示 Windows 盘符列表 `C:\`、`D:\`、`E:\`...**，无需手动输路径）；
2. 在手机端仅勾选 `USB_ADB` 并点击「启动服务器并等待连接」（后台将常驻前台通知，切后台或旋转屏幕均不断连）；
3. **测试 Turbo 模式**：双击 `start-adb-turbo.bat` → 回车采用自动模式（观察控制台打印 `libusb=... delayed_ack=... streams=...`）→ 在手机端互传刚才生成的测试目录，记录平均速率；
4. **对比 Legacy 模式**：断开后再次双击 `start-adb-turbo.bat` → 输入 `legacy`（或强制 `1` 流）重复传输，直观对比单流停等 vs 现代突发/多流池吞吐差异。

### 对比组 B：内核态 USB 网卡轨（NCM / RNDIS）与 Wi-Fi 多轨聚合
1. 在手机「网络共享」中开启 **USB 网络共享**（手机端刷新网卡列表后，`ncm0`/`rndis0`/`usb0` 会置顶推荐，同时自动取消勾选 `USB_ADB` 防止同线争抢）；
2. 点击手机端「启动服务器并等待连接」；
3. 双击 `start-usb-nic-or-ip.bat` → 输入手机端显示的 USB 网卡 IP（如 `10.x.x.x`）→ 回车启用自动同网段 `bind`；
4. 单勾 USB 网卡可测纯有线内核态极限带宽（零 `adbd` CPU 占用）；同时勾选 USB 网卡 + Wi-Fi 即可实测跨介质异构多轨聚合速度！

---

## 三、 如何确认传输文件 100% 零损坏（SHA-256 校验）

### 方式 1：PC ↔ 手机直接校验（推荐，手机插线开启 USB 调试即可）
1. 完成 PC → 手机（或手机 → PC）文件传输；
2. 双击运行 `verify-transfer-sha256.bat` → 选择 **`[2]`**；
3. 输入 PC 侧测试目录路径（如 `C:\Users\你的用户名\Desktop\HFX_Bench_xxx`）与手机侧目录路径（如 `/sdcard/Download/HFX_Bench_xxx`）；
4. 工具会自动对两边所有子目录/文件逐一计算字节大小与 `SHA-256`，全部匹配时输出绿色 **`[结果: PASS] 共校验 N 个文件，字节大小与 SHA-256 100% 零受损！`**。

### 方式 2：无需 ADB 的回传闭环校验（纯 Wi-Fi / USB 网卡场景）
1. 先将 PC 测试目录 A 传到手机，再从手机传回 PC 的空目录 B；
2. 双击 `verify-transfer-sha256.bat` → 选择 **`[3]`** → 分别输入目录 A 与目录 B；
3. 若全部文件显示 `[PASS]`，即证明双方向完整链路均零字节损坏。

### 方式 3：命令行无交互自动化调用示例（PowerShell）
```powershell
# 1. 生成 100 MB 随机测试集 + 10 个中小文件
.\verify-transfer-sha256.bat -Action Generate -OutDir D:\HFX_Bench -SizeMB 100 -ExtraSmallFiles 10

# 2. PC 与手机目录直连比对 SHA-256
.\verify-transfer-sha256.bat -Action VerifyAdb -PcDir D:\HFX_Bench -AndroidDir /sdcard/Download/HFX_Bench

# 3. 本地双目录比对 SHA-256
.\verify-transfer-sha256.bat -Action VerifyLocal -PcDir D:\HFX_Bench -OtherDir D:\HFX_RoundTrip
```
