package top.weixiansen574.hybridfilexfer.pc.engine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.JsonRpcDispatcher;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.LocalSocketIpcTransport;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.StdioIpcTransport;

/**
 * Headless PC engine daemon entry point supporting dual-mode local IPC:
 * <ul>
 *   <li>{@code --ipc=stdio} (default Sidecar mode: strictly NDJSON frames on stdout, zero UI clutter)</li>
 *   <li>{@code --ipc=tcp://127.0.0.1:0} (loopback daemon with ephemeral {@code ipcToken} + {@code engine.ipc.json})</li>
 *   <li>{@code --ipc=unix://<path>} (AF_UNIX daemon with ephemeral {@code ipcToken} + {@code engine.ipc.json})</li>
 * </ul>
 */
public final class PcEngineMain {

    public static final class EngineArgs {
        public String ipcMode = "stdio";
        public Path ipcInfoPath;
        public String deviceId;
        public String adbPath;
        public boolean adbLegacy;
        public Integer adbStreams;
        public boolean disableAutoBind;
        public boolean noTokenAuth;
        public boolean autoConnectTrusted;
        public boolean disableMdns;
        public Path trustedPeersPath;
        public Path qrTempDir;

        public static EngineArgs parse(String[] args) {
            EngineArgs parsed = new EngineArgs();
            if (args == null) {
                return parsed;
            }
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) continue;
                if (arg.startsWith("--ipc=")) {
                    parsed.ipcMode = arg.substring("--ipc=".length()).trim();
                } else if ("--ipc".equals(arg) && i + 1 < args.length) {
                    parsed.ipcMode = args[++i].trim();
                } else if (arg.startsWith("--ipc-info=")) {
                    parsed.ipcInfoPath = Paths.get(arg.substring("--ipc-info=".length()).trim());
                } else if ("--ipc-info".equals(arg) && i + 1 < args.length) {
                    parsed.ipcInfoPath = Paths.get(args[++i].trim());
                } else if (arg.startsWith("--device-id=")) {
                    parsed.deviceId = arg.substring("--device-id=".length()).trim();
                } else if (arg.startsWith("--adb-path=")) {
                    parsed.adbPath = arg.substring("--adb-path=".length()).trim();
                } else if ("--adb-legacy".equals(arg)) {
                    parsed.adbLegacy = true;
                } else if (arg.startsWith("--adb-streams=")) {
                    parsed.adbStreams = Integer.parseInt(arg.substring("--adb-streams=".length()).trim());
                } else if ("--no-auto-bind".equals(arg)) {
                    parsed.disableAutoBind = true;
                } else if ("--no-token-auth".equals(arg)) {
                    parsed.noTokenAuth = true;
                } else if ("--auto-connect".equals(arg)) {
                    parsed.autoConnectTrusted = true;
                } else if ("--no-mdns".equals(arg)) {
                    parsed.disableMdns = true;
                } else if (arg.startsWith("--trusted-peers=")) {
                    parsed.trustedPeersPath = Paths.get(arg.substring("--trusted-peers=".length()).trim());
                } else if (arg.startsWith("--qr-dir=")) {
                    parsed.qrTempDir = Paths.get(arg.substring("--qr-dir=".length()).trim());
                }
            }
            return parsed;
        }
    }

    private PcEngineMain() {
    }

    public static IpcDtos.EngineSnapshot createInitialSnapshot(String deviceId) {
        PcEngineRuntime.EngineConfig cfg = new PcEngineRuntime.EngineConfig();
        cfg.deviceId = deviceId != null ? deviceId : UUID.randomUUID().toString();
        cfg.enableMdns = false;
        try (PcEngineRuntime runtime = new PcEngineRuntime(cfg)) {
            return runtime.getSnapshot();
        }
    }

    public static void main(String[] args) throws Exception {
        EngineArgs parsed = EngineArgs.parse(args);

        boolean isStdio = "stdio".equalsIgnoreCase(parsed.ipcMode);
        boolean isLocalSocket = parsed.ipcMode != null
                && (parsed.ipcMode.startsWith("tcp://") || parsed.ipcMode.startsWith("unix://"));
        if (!isStdio && !isLocalSocket) {
            System.err.println("Unsupported --ipc mode: " + parsed.ipcMode
                    + " (expected stdio | tcp://127.0.0.1:0 | unix://<path>, protocol="
                    + ProtocolConstants.VERSION_CODE + ")");
            System.err.flush();
            System.exit(2);
            return;
        }

        PcEngineRuntime.EngineConfig cfg = new PcEngineRuntime.EngineConfig();
        cfg.deviceId = parsed.deviceId; // null => load/persist stable PC ID in TrustedPeerStore
        cfg.defaultAutoSubnetBind = !parsed.disableAutoBind;
        cfg.adbConfig.explicitAdbPath = parsed.adbPath;
        cfg.adbConfig.adbLegacy = parsed.adbLegacy;
        cfg.adbConfig.overrideStreams = parsed.adbStreams;
        cfg.autoConnectTrustedPeers = parsed.autoConnectTrusted;
        cfg.enableMdns = !parsed.disableMdns;
        cfg.trustedPeersPath = parsed.trustedPeersPath;
        cfg.qrTempDir = parsed.qrTempDir;

        int exitCode = 0;
        try (PcEngineRuntime runtime = new PcEngineRuntime(cfg)) {
            if (isStdio) {
                JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(runtime, null);
                try (StdioIpcTransport transport = new StdioIpcTransport(dispatcher, System.in, System.out)) {
                    runtime.setShutdownHandler(transport::close);
                    transport.runBlocking();
                }
            } else {
                String token = parsed.noTokenAuth ? null : LocalSocketIpcTransport.generateRandomToken();
                JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(runtime, token);
                try (LocalSocketIpcTransport transport = new LocalSocketIpcTransport(
                        dispatcher, parsed.ipcMode, token, parsed.ipcInfoPath)) {
                    transport.start();
                    transport.awaitTermination();
                }
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            exitCode = 1;
        }

        // P0-BUG-01: org.jmdns:jmdns:3.5.9 leaks non-daemon threads (State.Timer / pool-*-thread-1)
        // after jmdns.close(), hanging DestroyJavaVM indefinitely. Explicit System.exit after
        // graceful PcEngineRuntime.close() guarantees deterministic JVM termination and zero JAR locks.
        System.out.flush();
        System.err.flush();
        System.exit(exitCode);
    }
}
