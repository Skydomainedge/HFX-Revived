package top.weixiansen574.hybridfilexfer;

import java.io.File;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.pc.engine.PcEngineRuntime;
import top.weixiansen574.hybridfilexfer.pc.engine.adb.AdbManager;

/**
 * Thin backwards-compatible CLI shell over headless {@link PcEngineRuntime}.
 *
 * <p>Preserves 100% compatibility with legacy {@code -c adb} / {@code -c <ip>} / {@code -d} /
 * {@code -s} and M2 {@code --adb-*} / {@code --no-auto-bind} flags while delegating all
 * transport, ADB, NIC, and state-machine logic to {@link PcEngineRuntime}.</p>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        CliOptions opts;
        try {
            opts = CliOptions.parse(args);
        } catch (CliOptions.ParseException e) {
            Strings.printf(e.messageKey, e.detail);
            Strings.printf("see_help");
            System.exit(1);
            return;
        }

        if (opts.showVersion) {
            System.out.println("HybridFileXfer v" + ProtocolConstants.ENGINE_VERSION
                    + " (protocol " + ProtocolConstants.VERSION_CODE + ")");
            return;
        }

        if (opts.showHelp || opts.emptyArgs) {
            Strings.printf("help_pages");
            return;
        }

        if (opts.connect == null || opts.connect.isEmpty()) {
            System.err.println(Strings.get("control_chanel_null"));
            Strings.printf("see_help");
            return;
        }

        PcEngineRuntime.EngineConfig engineConfig = new PcEngineRuntime.EngineConfig();
        engineConfig.adbConfig = buildAdbConfig(opts);
        engineConfig.defaultAutoSubnetBind = !opts.disableAutoBind;

        try (PcEngineRuntime runtime = new PcEngineRuntime(engineConfig)) {
            Thread cliShutdownHook = new Thread(runtime::shutdown, "hfx-cli-shutdown");
            Runtime.getRuntime().addShutdownHook(cliShutdownHook);

            runtime.addEventListener(Main::handleEngineEvent);

            IpcPayloads.SessionConnectParams connectParams = new IpcPayloads.SessionConnectParams();
            if ("adb".equalsIgnoreCase(opts.connect)) {
                connectParams.mode = "adb";
                connectParams.serial = opts.device;
                connectParams.adbPath = opts.adbPath;
                connectParams.adbLegacy = opts.adbLegacy;
                connectParams.adbStreams = opts.adbStreams;
            } else {
                connectParams.mode = "direct";
                connectParams.host = opts.connect;
            }
            connectParams.port = ProtocolConstants.DEFAULT_CONTROL_PORT;
            connectParams.homeDir = resolveEffectiveHomeDir(opts.homeDir);
            connectParams.autoSubnetBind = !opts.disableAutoBind;

            Strings.printf("connecting_control_channel",
                    "adb".equals(connectParams.mode) ? "127.0.0.1 (ADB)" : connectParams.host);
            try {
                runtime.connect(connectParams);
            } catch (PcEngineRuntime.EngineException e) {
                System.err.println("[ERROR] " + e.getMessage());
                removeShutdownHookQuietly(cliShutdownHook);
                return;
            }

            IpcDtos.EngineSnapshot snap = runtime.getSnapshot();
            if ("adb".equals(connectParams.mode) && snap.adb != null && snap.adb.available) {
                int effStreams = opts.adbStreams != null ? opts.adbStreams : snap.adb.recommendedStreams;
                System.out.printf("ADB: %s (v%s, libusb=%s, delayed_ack=%s, streams=%d)%n",
                        snap.adb.adbPath, snap.adb.version, snap.adb.hasLibusb, snap.adb.hasDelayedAck, effStreams);
                System.out.println(Strings.get("adb_forward_succeed"));
            }
            Strings.printf("connect_success");

            runtime.awaitSessionEnd();
            removeShutdownHookQuietly(cliShutdownHook);
        }
    }

    private static void handleEngineEvent(String eventName, Object payload) {
        if (IpcEvents.ADB_VERSION_WARNING.equals(eventName)
                && payload instanceof IpcPayloads.DiagnosticWarningPayload) {
            IpcPayloads.DiagnosticWarningPayload warn = (IpcPayloads.DiagnosticWarningPayload) payload;
            System.err.println("[WARN][" + warn.code + "] " + warn.message);
        } else if (IpcEvents.DIAGNOSTIC_WARNING.equals(eventName)
                && payload instanceof IpcPayloads.DiagnosticWarningPayload) {
            IpcPayloads.DiagnosticWarningPayload warn = (IpcPayloads.DiagnosticWarningPayload) payload;
            System.err.println("[WARN][" + warn.code + "] " + warn.message);
        } else if (IpcEvents.RAIL_STATUS.equals(eventName)
                && payload instanceof IpcPayloads.RailStatusPayload) {
            IpcPayloads.RailStatusPayload rp = (IpcPayloads.RailStatusPayload) payload;
            for (IpcDtos.RailStatusInfo rail : rp.rails) {
                if ("CONNECTING".equals(rail.state)) {
                    Strings.printf("connecting_transfer_channel",
                            rail.name,
                            rail.address == null ? "unknown" : rail.address,
                            rail.bindAddress == null ? "null" : rail.bindAddress);
                }
            }
        } else if (IpcEvents.SESSION_STATE_CHANGED.equals(eventName)
                && payload instanceof IpcPayloads.SessionStateChangedPayload) {
            IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
            if (sc.current == SessionState.TRANSFERRING) {
                if (sc.detail != null && sc.detail.toLowerCase().contains("receiving")) {
                    Strings.printf("receiving_files");
                } else {
                    Strings.printf("sending_files");
                }
            } else if (sc.current == SessionState.IDLE && sc.prev != SessionState.IDLE
                    && sc.prev != SessionState.CONNECTING) {
                Strings.printf("client_exit");
            }
        } else if (IpcEvents.TRANSFER_PROGRESS.equals(eventName)
                && payload instanceof IpcPayloads.TransferProgressPayload) {
            IpcPayloads.TransferProgressPayload prog = (IpcPayloads.TransferProgressPayload) payload;
            if (prog.aggregateBps > 0) {
                StringBuilder railsText = new StringBuilder();
                for (Map.Entry<String, Long> e : prog.perRailBps.entrySet()) {
                    if (railsText.length() > 0) railsText.append(", ");
                    railsText.append(e.getKey()).append("=").append(Utils.formatSpeed(e.getValue()));
                }
                System.out.printf("[%s] %s (%s) %s%n",
                        "upload".equals(prog.direction) ? "UP" : "DN",
                        Utils.formatSpeed(prog.aggregateBps),
                        railsText.length() > 0 ? railsText.toString() : "active",
                        prog.currentFile != null ? prog.currentFile : "");
            }
        } else if (IpcEvents.TRANSFER_COMPLETED.equals(eventName)
                && payload instanceof IpcPayloads.TransferCompletedPayload) {
            IpcPayloads.TransferCompletedPayload done = (IpcPayloads.TransferCompletedPayload) payload;
            long safeTime = Math.max(1L, done.elapsedMs);
            if ("upload".equals(done.direction)) {
                Strings.printf("upload_complete",
                        Utils.formatSpeed(done.totalBytes * 1000L / safeTime),
                        Utils.formatTime(done.elapsedMs),
                        Utils.formatFileSize(done.totalBytes));
            } else {
                Strings.printf("download_complete",
                        Utils.formatSpeed(done.totalBytes * 1000L / safeTime),
                        Utils.formatTime(done.elapsedMs),
                        Utils.formatFileSize(done.totalBytes));
            }
        } else if (IpcEvents.TRANSFER_ERROR.equals(eventName)
                && payload instanceof IpcPayloads.TransferCompletedPayload) {
            IpcPayloads.TransferCompletedPayload err = (IpcPayloads.TransferCompletedPayload) payload;
            System.err.println("[TRANSFER_ERROR] " + (err.error != null ? err.error : "unknown"));
            Strings.printf("transfer_failed");
        }
    }

    private static void removeShutdownHookQuietly(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
        }
    }

    static String resolveEffectiveHomeDir(String requestedHomeDir) {
        if (requestedHomeDir == null || requestedHomeDir.trim().isEmpty() || "/".equals(requestedHomeDir.trim())) {
            return "/";
        }
        String trimmed = requestedHomeDir.trim();
        if (File.separatorChar == '\\' && trimmed.length() == 2
                && Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(1) == ':') {
            trimmed = trimmed + "\\";
        }
        File dir = new File(trimmed);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                System.out.println("Created target home directory: " + dir.getAbsolutePath());
            } else {
                System.err.println("[WARN] Target directory does not exist and could not be created: "
                        + trimmed + "; falling back to /");
                return "/";
            }
        }
        if (!dir.isDirectory() || dir.listFiles() == null) {
            System.err.println("[WARN] Target directory is not accessible: " + trimmed + "; falling back to /");
            return "/";
        }
        return dir.getAbsolutePath();
    }

    static AdbManager.AdbConfig buildAdbConfig(CliOptions opts) {
        AdbManager.AdbConfig config = new AdbManager.AdbConfig();
        config.explicitAdbPath = opts.adbPath;
        config.adbLegacy = opts.adbLegacy;
        config.overrideStreams = opts.adbStreams;
        return config;
    }
}
