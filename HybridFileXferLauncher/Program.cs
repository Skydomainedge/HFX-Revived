using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Toolkit.Uwp.Notifications;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace HybridFileXferLauncher;

/// <summary>
/// Thin Windows 11 native Toast notification host over headless {@code hfx-engine --ipc=stdio}.
///
/// Zero desktop window bloat: displays uncropped inline 480x480 QR PNG notifications
/// (&lt;image hint-crop="none"/&gt;, NEVER placement="hero"), auto-dismisses on pair.succeeded/expired,
/// supports "放大查看" (system image viewer) and "取消" (pair.cancel), and shows lightweight
/// connection/transfer completion notifications.
/// </summary>
public static class Program
{
    private delegate bool ConsoleCtrlHandlerDelegate(uint ctrlType);

    private const uint CTRL_C_EVENT = 0;
    private const uint CTRL_BREAK_EVENT = 1;
    private const uint CTRL_CLOSE_EVENT = 2;
    private const uint CTRL_LOGOFF_EVENT = 5;
    private const uint CTRL_SHUTDOWN_EVENT = 6;

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetConsoleCtrlHandler(ConsoleCtrlHandlerDelegate handler, bool add);

    private static volatile string? _activeQrPngPath;
    private static EngineStdioBridge? _bridge;
    // Keep a static GC root for the native SetConsoleCtrlHandler callback delegate.
    private static ConsoleCtrlHandlerDelegate? _consoleCtrlHandler;

    public static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;

        if (args.Any(a => a.Equals("--verify-toast-xml", StringComparison.OrdinalIgnoreCase)))
        {
            return RunToastXmlSelfTest();
        }

        if (args.Any(a => a.Equals("--verify-job-object", StringComparison.OrdinalIgnoreCase)))
        {
            return RunJobObjectSelfTest();
        }

        if (args.Any(a => a is "-h" or "--help"))
        {
            PrintUsage();
            return 0;
        }

        var engineJar = ResolveEngineJar(args);
        if (engineJar == null)
        {
            Console.Error.WriteLine("[ERROR] Could not locate hfx-engine-*.jar.");
            Console.Error.WriteLine("        Build it first with: gradlew.bat :pc-engine:shadowJar --no-daemon");
            Console.Error.WriteLine("        Or pass --engine-jar=<path>");
            return 1;
        }

        var forcePair = args.Any(a => a.Equals("--pair", StringComparison.OrdinalIgnoreCase));
        var connectTarget = GetOptionValue(args, "-c") ?? GetOptionValue(args, "--connect");
        var serial = GetOptionValue(args, "-s") ?? GetOptionValue(args, "--device");
        var homeDir = GetOptionValue(args, "-d") ?? GetOptionValue(args, "--dir") ?? "/";
        var noToast = args.Any(a => a.Equals("--no-toast", StringComparison.OrdinalIgnoreCase));
        var engineArgs = ExtractPassthroughEngineArgs(args);

        if (!noToast)
        {
            StartMenuAumidRegistrar.EnsureShortcutRegistered(ToastXmlBuilder.DefaultAumid);
            ToastNotificationManagerCompat.OnActivated += HandleToastActivated;
        }

        Console.WriteLine($"[HybridFileXfer] Starting headless engine: {engineJar}");
        await using var bridge = EngineStdioBridge.Start(engineJar, engineArgs);
        _bridge = bridge;

        var exitCts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            exitCts.Cancel();
        };

        _consoleCtrlHandler = ctrlType =>
        {
            if (ctrlType is CTRL_CLOSE_EVENT or CTRL_LOGOFF_EVENT or CTRL_SHUTDOWN_EVENT)
            {
                try { exitCts.Cancel(); } catch { }
                if (!noToast)
                {
                    DismissPairQrToast();
                }
                _bridge?.ShutdownSyncAndKill(TimeSpan.FromMilliseconds(2200));
                return false;
            }
            return false;
        };
        SetConsoleCtrlHandler(_consoleCtrlHandler, true);

        EventHandler processExitHandler = (_, _) =>
        {
            if (!noToast)
            {
                DismissPairQrToast();
            }
            _bridge?.ShutdownSyncAndKill(TimeSpan.FromMilliseconds(1500));
        };
        AppDomain.CurrentDomain.ProcessExit += processExitHandler;

        bridge.StderrReceived += line => Console.Error.WriteLine($"[engine] {line}");
        bridge.EventReceived += (eventName, payload) => HandleEngineEvent(eventName, payload, noToast);

        try
        {
            var snap = await bridge.CallAsync("engine.get_snapshot", null, exitCts.Token);
            var pcId = snap.TryGetProperty("deviceId", out var idEl) ? idEl.GetString() : "unknown";
            var pcName = snap.TryGetProperty("deviceName", out var nameEl) ? nameEl.GetString() : "PC";
            Console.WriteLine($"[HybridFileXfer] Engine ready (id={pcId}, host={pcName})");

            if (!string.IsNullOrWhiteSpace(connectTarget))
            {
                var mode = connectTarget.Equals("adb", StringComparison.OrdinalIgnoreCase) ? "adb" : "direct";
                Console.WriteLine($"[HybridFileXfer] Connecting ({mode}: {connectTarget})...");
                await bridge.CallAsync("session.connect", new
                {
                    mode,
                    host = mode == "direct" ? connectTarget : null,
                    serial,
                    homeDir
                }, exitCts.Token);
            }
            else if (forcePair)
            {
                await StartQrPairingAsync(bridge, exitCts.Token);
            }
            else
            {
                Console.WriteLine("[HybridFileXfer] Searching for trusted devices via mDNS...");
                try
                {
                    await bridge.CallAsync("session.connect", new
                    {
                        mode = "auto",
                        serial,
                        homeDir
                    }, exitCts.Token);
                    Console.WriteLine("[HybridFileXfer] Connected automatically to trusted device!");
                }
                catch (Exception ex)
                {
                    if (!ex.Message.Contains("No trusted peer discovered", StringComparison.OrdinalIgnoreCase))
                    {
                        Console.Error.WriteLine($"[WARN] Auto-connect failed: {ex.Message}");
                    }
                    Console.WriteLine("[HybridFileXfer] No trusted device discovered nearby; launching QR pairing...");
                    await StartQrPairingAsync(bridge, exitCts.Token);
                }
            }

            Console.WriteLine("[HybridFileXfer] Running (press Ctrl+C to exit)...");
            try
            {
                await bridge.WaitForExitAsync(exitCts.Token);
            }
            catch (OperationCanceledException)
            {
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[ERROR] {ex.Message}");
            return 1;
        }
        finally
        {
            AppDomain.CurrentDomain.ProcessExit -= processExitHandler;
            if (_consoleCtrlHandler != null)
            {
                SetConsoleCtrlHandler(_consoleCtrlHandler, false);
                _consoleCtrlHandler = null;
            }
            if (!noToast)
            {
                DismissPairQrToast();
            }
            _bridge = null;
        }

        return 0;
    }

    private static async Task StartQrPairingAsync(EngineStdioBridge bridge, CancellationToken ct)
    {
        var result = await bridge.CallAsync("pair.start_qr", new { ttlSeconds = 120, pngSize = 480 }, ct);
        if (result.TryGetProperty("qrPngPath", out var pathEl))
        {
            Console.WriteLine($"[HybridFileXfer] QR PNG ready: {pathEl.GetString()}");
        }
    }

    private static void HandleEngineEvent(string eventName, JsonElement payload, bool noToast)
    {
        switch (eventName)
        {
            case "pair.qr_ready":
            {
                var pngPath = payload.TryGetProperty("qrPngPath", out var p) ? p.GetString() ?? "" : "";
                var hostName = payload.TryGetProperty("hostName", out var h) ? h.GetString() ?? "PC" : "PC";
                var ips = new List<string>();
                if (payload.TryGetProperty("ips", out var ipsEl) && ipsEl.ValueKind == JsonValueKind.Array)
                {
                    foreach (var ip in ipsEl.EnumerateArray())
                    {
                        var val = ip.GetString();
                        if (!string.IsNullOrWhiteSpace(val)) ips.Add(val);
                    }
                }
                _activeQrPngPath = pngPath;
                Console.WriteLine($"[PAIR] Scan QR in Windows 11 notification (PNG: {pngPath}, IPs: {string.Join(", ", ips)})");
                if (!noToast && !string.IsNullOrWhiteSpace(pngPath))
                {
                    var xml = ToastXmlBuilder.BuildQrPairingToastXml(pngPath, hostName, ips, 120);
                    ShowToastXml(xml, ToastXmlBuilder.PairQrTag, ToastXmlBuilder.PairQrGroup);
                }
                break;
            }

            case "pair.succeeded":
            {
                var peerName = payload.TryGetProperty("peerName", out var n) ? n.GetString() ?? "手机" : "手机";
                var viaFallback = payload.TryGetProperty("viaFallbackMdns", out var fb) && fb.GetBoolean();
                _activeQrPngPath = null;
                Console.WriteLine($"[PAIR] Paired successfully with {peerName} (viaFallbackMdns={viaFallback})");
                if (!noToast)
                {
                    DismissPairQrToast();
                    var detail = viaFallback
                        ? $"已通过 mDNS 反向兜底完成与「{peerName}」的配对并建立连接"
                        : $"已完成与「{peerName}」的扫码绑定并建立高速连接";
                    ShowToastXml(
                        ToastXmlBuilder.BuildStatusToastXml("HybridFileXfer · 绑定并连接成功", detail),
                        ToastXmlBuilder.StatusTag,
                        ToastXmlBuilder.StatusGroup);
                }
                break;
            }

            case "pair.expired":
            case "pair.failed":
            {
                var reason = payload.TryGetProperty("reason", out var r) ? r.GetString() ?? eventName : eventName;
                _activeQrPngPath = null;
                Console.WriteLine($"[PAIR] {eventName}: {reason}");
                if (!noToast)
                {
                    DismissPairQrToast();
                    ShowToastXml(
                        ToastXmlBuilder.BuildStatusToastXml("HybridFileXfer · 配对已结束", reason),
                        ToastXmlBuilder.StatusTag,
                        ToastXmlBuilder.StatusGroup);
                }
                break;
            }

            case "session.state_changed":
            {
                var current = payload.TryGetProperty("current", out var c) ? c.GetString() ?? "" : "";
                var detail = payload.TryGetProperty("detail", out var d) ? d.GetString() ?? "" : "";
                Console.WriteLine($"[STATE] {current} ({detail})");
                if (!noToast && current == "CONNECTED")
                {
                    DismissPairQrToast();
                    ShowToastXml(
                        ToastXmlBuilder.BuildStatusToastXml("HybridFileXfer · 已连接", detail),
                        ToastXmlBuilder.StatusTag,
                        ToastXmlBuilder.StatusGroup);
                }
                break;
            }

            case "transfer.completed":
            {
                var dir = payload.TryGetProperty("direction", out var d) ? d.GetString() ?? "transfer" : "transfer";
                var bytes = payload.TryGetProperty("totalBytes", out var b) ? b.GetInt64() : 0L;
                var avgBps = payload.TryGetProperty("avgBps", out var s) ? s.GetInt64() : 0L;
                var summary = $"{(dir == "upload" ? "发送" : "接收")}完成 · {FormatBytes(bytes)} · 平均 {FormatBytes(avgBps)}/s";
                Console.WriteLine($"[TRANSFER] {summary}");
                if (!noToast)
                {
                    ShowToastXml(
                        ToastXmlBuilder.BuildStatusToastXml("HybridFileXfer · 传输完成", summary),
                        ToastXmlBuilder.StatusTag,
                        ToastXmlBuilder.StatusGroup);
                }
                break;
            }

            case "mdns.peer_discovered":
            {
                var name = payload.TryGetProperty("deviceName", out var n) ? n.GetString() ?? "Peer" : "Peer";
                var trusted = payload.TryGetProperty("trusted", out var t) && t.GetBoolean();
                Console.WriteLine($"[mDNS] Discovered {name} (trusted={trusted})");
                break;
            }

            case "adb.version_warning":
            case "diagnostic.warning":
            {
                var code = payload.TryGetProperty("code", out var c) ? c.GetString() ?? eventName : eventName;
                var msg = payload.TryGetProperty("message", out var m) ? m.GetString() ?? "" : "";
                Console.Error.WriteLine($"[WARN][{code}] {msg}");
                break;
            }
        }
    }

    private static void HandleToastActivated(ToastNotificationActivatedEventArgsCompat e)
    {
        try
        {
            var args = ToastArguments.Parse(e.Argument);
            if (!args.TryGetValue("action", out var action))
            {
                return;
            }
            if (action == "open_png")
            {
                var path = args.TryGetValue("path", out var p) ? p : _activeQrPngPath;
                if (!string.IsNullOrWhiteSpace(path) && File.Exists(path))
                {
                    Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
                }
            }
            else if (action == "cancel_pair")
            {
                DismissPairQrToast();
                var bridge = _bridge;
                if (bridge != null && !bridge.HasExited)
                {
                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            await bridge.CallAsync("pair.cancel").ConfigureAwait(false);
                        }
                        catch
                        {
                        }
                    });
                }
            }
        }
        catch
        {
        }
    }

    private static void ShowToastXml(string xml, string tag, string group)
    {
        try
        {
            var xmlDoc = new XmlDocument();
            xmlDoc.LoadXml(xml);
            var toast = new ToastNotification(xmlDoc)
            {
                Tag = tag,
                Group = group
            };
            ToastNotificationManagerCompat.CreateToastNotifier().Show(toast);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[WARN] Failed to show Windows 11 Toast: {ex.Message}");
        }
    }

    private static void DismissPairQrToast()
    {
        try
        {
            ToastNotificationManagerCompat.History.Remove(ToastXmlBuilder.PairQrTag, ToastXmlBuilder.PairQrGroup);
        }
        catch
        {
        }
    }

    private static int RunToastXmlSelfTest()
    {
        var samplePath = Path.Combine(Path.GetTempPath(), "hybridfilexfer", "qr", "pair_selftest.png");
        var xml = ToastXmlBuilder.BuildQrPairingToastXml(
            samplePath,
            "OMEN-8-Plus",
            new[] { "10.94.81.203", "192.168.50.236" },
            120);
        ToastXmlBuilder.AssertQrToastXmlInvariants(xml);

        // Also validate XmlDocument parses cleanly via WinRT XML parser
        var winrtXml = new XmlDocument();
        winrtXml.LoadXml(xml);

        var statusXml = ToastXmlBuilder.BuildStatusToastXml("HybridFileXfer · 已连接", "已自动连接 一加 13");
        var statusDoc = new XmlDocument();
        statusDoc.LoadXml(statusXml);

        Console.WriteLine("[PASS] Win11 Toast XML invariants verified (zero placement=\"hero\", inline hint-crop=\"none\", actions valid).");
        Console.WriteLine(xml);
        return 0;
    }

    private static int RunJobObjectSelfTest()
    {
        ChildProcessJobObject.VerifySelfTest();
        Console.WriteLine("[PASS] Win32 Job Object (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE) verified.");
        return 0;
    }

    private static string? ResolveEngineJar(string[] args)
    {
        var explicitJar = GetOptionValue(args, "--engine-jar");
        if (!string.IsNullOrWhiteSpace(explicitJar) && File.Exists(explicitJar))
        {
            return Path.GetFullPath(explicitJar);
        }

        var searchRoots = new List<string> { AppContext.BaseDirectory, Environment.CurrentDirectory };
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 6 && dir != null; i++)
        {
            searchRoots.Add(dir.FullName);
            searchRoots.Add(Path.Combine(dir.FullName, "pc-engine", "build", "libs"));
            dir = dir.Parent;
        }

        foreach (var root in searchRoots.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (!Directory.Exists(root)) continue;
            var match = Directory.EnumerateFiles(root, "hfx-engine-*.jar")
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (match != null) return match;
        }
        return null;
    }

    private static List<string> ExtractPassthroughEngineArgs(string[] args)
    {
        var result = new List<string>();
        foreach (var arg in args)
        {
            if (arg.StartsWith("--device-id=", StringComparison.OrdinalIgnoreCase)
                || arg.StartsWith("--adb-path=", StringComparison.OrdinalIgnoreCase)
                || arg.StartsWith("--adb-streams=", StringComparison.OrdinalIgnoreCase)
                || arg.Equals("--adb-legacy", StringComparison.OrdinalIgnoreCase)
                || arg.Equals("--no-auto-bind", StringComparison.OrdinalIgnoreCase)
                || arg.StartsWith("--trusted-peers=", StringComparison.OrdinalIgnoreCase)
                || arg.StartsWith("--qr-dir=", StringComparison.OrdinalIgnoreCase))
            {
                result.Add(arg);
            }
        }
        return result;
    }

    private static string? GetOptionValue(string[] args, string key)
    {
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i].Equals(key, StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
            {
                return args[i + 1];
            }
            if (args[i].StartsWith(key + "=", StringComparison.OrdinalIgnoreCase))
            {
                return args[i][(key.Length + 1)..];
            }
        }
        return null;
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:F1} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024.0):F2} MB";
        return $"{bytes / (1024.0 * 1024.0 * 1024.0):F2} GB";
    }

    private static void PrintUsage()
    {
        Console.WriteLine("HybridFileXferLauncher (Win11 Native Toast Host over hfx-engine --ipc=stdio)");
        Console.WriteLine("  (no args)           Auto-discover trusted device via mDNS, or pop Win11 QR Toast to pair");
        Console.WriteLine("  --pair              Immediately generate 480x480 QR PNG and push Win11 Toast");
        Console.WriteLine("  -c adb | <ip>       Connect directly via ADB or IP");
        Console.WriteLine("  -s <serial>         Target ADB serial");
        Console.WriteLine("  -d <dir>            Optional default PC home directory metadata");
        Console.WriteLine("  --engine-jar=<path> Explicit path to hfx-engine-*.jar");
        Console.WriteLine("  --no-toast          Console-only mode (suppress Win11 Toast)");
        Console.WriteLine("  --verify-toast-xml  Run built-in Toast XML anti-crop self-verification");
    }
}
