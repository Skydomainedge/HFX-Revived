using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace HybridFileXferLauncher;

/// <summary>
/// Manages the headless {@code hfx-engine} child JVM process over JSON-RPC 2.0 NDJSON {@code --ipc=stdio}.
/// </summary>
public sealed class EngineStdioBridge : IAsyncDisposable
{
    private readonly Process _process;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly ConcurrentDictionary<long, TaskCompletionSource<JsonElement>> _pending = new();
    private readonly CancellationTokenSource _cts = new();
    private long _nextId;
    private Task? _stdoutLoop;
    private Task? _stderrLoop;

    public event Action<string, JsonElement>? EventReceived;
    public event Action<string>? StderrReceived;
    public event Action<int>? Exited;

    public bool HasExited => _process.HasExited;

    private EngineStdioBridge(Process process)
    {
        _process = process;
    }

    public static EngineStdioBridge Start(string engineJarPath, IReadOnlyList<string>? extraEngineArgs = null)
    {
        var psi = new ProcessStartInfo("java")
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            CreateNoWindow = true
        };

        psi.ArgumentList.Add("-Dfile.encoding=UTF-8");
        psi.ArgumentList.Add("-Dsun.stdout.encoding=UTF-8");
        psi.ArgumentList.Add("-Dsun.stderr.encoding=UTF-8");
        psi.ArgumentList.Add("-jar");
        psi.ArgumentList.Add(engineJarPath);
        psi.ArgumentList.Add("--ipc=stdio");

        if (extraEngineArgs != null)
        {
            foreach (var arg in extraEngineArgs)
            {
                psi.ArgumentList.Add(arg);
            }
        }

        var proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
        if (!proc.Start())
        {
            throw new InvalidOperationException("Failed to start hfx-engine Java process.");
        }

        var bridge = new EngineStdioBridge(proc);
        proc.Exited += (_, _) =>
        {
            var code = 0;
            try { code = proc.ExitCode; } catch { }
            foreach (var kv in bridge._pending)
            {
                kv.Value.TrySetCanceled();
            }
            bridge.Exited?.Invoke(code);
        };

        bridge._stdoutLoop = Task.Run(() => bridge.ReadStdoutAsync(bridge._cts.Token));
        bridge._stderrLoop = Task.Run(() => bridge.ReadStderrAsync(bridge._cts.Token));
        return bridge;
    }

    public async Task<JsonElement> CallAsync(string method, object? parameters = null, CancellationToken ct = default)
    {
        var id = Interlocked.Increment(ref _nextId);
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = tcs;

        using var reg = ct.Register(() => tcs.TrySetCanceled(ct));
        try
        {
            var payload = new Dictionary<string, object?>
            {
                ["jsonrpc"] = "2.0",
                ["id"] = id,
                ["method"] = method
            };
            if (parameters != null)
            {
                payload["params"] = parameters;
            }
            var line = JsonSerializer.Serialize(payload);
            await _writeLock.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                await _process.StandardInput.WriteLineAsync(line).ConfigureAwait(false);
                await _process.StandardInput.FlushAsync(ct).ConfigureAwait(false);
            }
            finally
            {
                _writeLock.Release();
            }

            var response = await tcs.Task.ConfigureAwait(false);
            if (response.TryGetProperty("error", out var errObj) && errObj.ValueKind == JsonValueKind.Object)
            {
                var errMsg = errObj.TryGetProperty("message", out var msgEl) ? msgEl.GetString() : "IPC error";
                var errCode = errObj.TryGetProperty("code", out var codeEl) ? codeEl.GetInt32() : -1;
                throw new InvalidOperationException($"IPC error ({errCode}): {errMsg}");
            }
            return response.TryGetProperty("result", out var resultEl) ? resultEl.Clone() : default;
        }
        finally
        {
            _pending.TryRemove(id, out _);
        }
    }

    public Task WaitForExitAsync(CancellationToken ct = default) => _process.WaitForExitAsync(ct);

    private async Task ReadStdoutAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var line = await _process.StandardOutput.ReadLineAsync(ct).ConfigureAwait(false);
                if (line == null)
                {
                    break;
                }
                var trimmed = line.Trim();
                if (trimmed.Length == 0 || trimmed[0] != '{')
                {
                    continue;
                }
                try
                {
                    using var doc = JsonDocument.Parse(trimmed);
                    var root = doc.RootElement;
                    if (root.TryGetProperty("id", out var idEl) && idEl.ValueKind == JsonValueKind.Number)
                    {
                        var id = idEl.GetInt64();
                        if (_pending.TryRemove(id, out var tcs))
                        {
                            tcs.TrySetResult(root.Clone());
                        }
                    }
                    else if (root.TryGetProperty("method", out var methodEl) && methodEl.ValueKind == JsonValueKind.String)
                    {
                        var eventName = methodEl.GetString()!;
                        var payload = root.TryGetProperty("params", out var paramsEl)
                            ? paramsEl.Clone()
                            : default;
                        EventReceived?.Invoke(eventName, payload);
                    }
                }
                catch
                {
                    // Ignore non-JSON noise
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task ReadStderrAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var line = await _process.StandardError.ReadLineAsync(ct).ConfigureAwait(false);
                if (line == null)
                {
                    break;
                }
                StderrReceived?.Invoke(line);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            if (!_process.HasExited)
            {
                using var shutdownCts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try
                {
                    await CallAsync("engine.shutdown", null, shutdownCts.Token).ConfigureAwait(false);
                }
                catch
                {
                }
            }
        }
        finally
        {
            _cts.Cancel();
            try
            {
                if (!_process.HasExited)
                {
                    _process.Kill(entireProcessTree: true);
                }
            }
            catch
            {
            }
            _process.Dispose();
            _writeLock.Dispose();
            _cts.Dispose();
        }
    }
}
