package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;
import top.weixiansen574.hybridfilexfer.pc.engine.adb.AdbManager;

import java.io.File;
import java.net.InetAddress;
import java.util.List;

/**
 * Thin backwards-compatible CLI shell preserving legacy {@code -c adb} / {@code -c <ip>} invocation.
 * M2 upgrades {@code -c adb} to use {@link AdbManager} (modern ADB version selection, process-level
 * {@code ADB_LIBUSB}/{@code ADB_BURST_MODE} injection, automatic 4-stream fallback, and clean
 * {@code adb forward --remove} on exit) while keeping all legacy flags compatible.
 */
public class Main {
    private static final ConnectServerCallback connectServerCallback = new ConnectServerCallback() {
        @Override
        public void onConnectingControlChannel(String address, int port) {
            Strings.printf("connecting_control_channel", address);
        }

        @Override
        public void onVersionMismatch(int localVersion, int remoteVersion) {
            Strings.printf("version_mismatch", localVersion, remoteVersion);
        }

        @Override
        public void onConnectControlFailed() {
            Strings.printf("connect_control_failed");
        }

        @Override
        public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
            Strings.printf("connecting_transfer_channel", name, inetAddress.getHostAddress(),
                    bindAddress == null ? "null" : bindAddress.getHostAddress());
        }

        @Override
        public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
            Strings.printf("connect_transfer_failed", name,
                    inetAddress == null ? "unknown" : inetAddress.getHostAddress(), e.toString());
        }

        @Override
        public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
            Strings.printf("out_of_memory", createdBuffers, requiredBuffers, maxMemoryMB);
            if (osArch != null && !osArch.contains("64")) {
                Strings.printf("java_32bit_warning");
            }
        }

        @Override
        public void onRemoteOOM() {
            Strings.printf("remote_out_of_memory");
        }

        @Override
        public void onConnectSuccess(List<String> channelNames) {
            Strings.printf("connect_success");
        }
    };

    private static final ClientCallBack clientCallBack = new ClientCallBack() {
        @Override
        public void onReceiving() {
            Strings.printf("receiving_files");
        }

        @Override
        public void onSending() {
            Strings.printf("sending_files");
        }

        @Override
        public void onExit() {
            Strings.printf("client_exit");
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            Strings.printf("channel_complete", iName, time <= 0 ? "∞" : Utils.formatSpeed(traffic * 1000L / time));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            switch (errorType) {
                case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                    Strings.printf("channel_error_exception", iName, message);
                    break;
                case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                    Strings.printf("channel_error_interrupt", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                    Strings.printf("channel_error_read", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                    Strings.printf("channel_error_write", iName);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onReadFileError(String message) {
            Strings.printf("file_read_error", message);
        }

        @Override
        public void onWriteFileError(String message) {
            Strings.printf("file_write_error", message);
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            long safeTime = Math.max(1L, time);
            if (isUpload) {
                Strings.printf("upload_complete", Utils.formatSpeed(traffic * 1000L / safeTime),
                        Utils.formatTime(time), Utils.formatFileSize(traffic));
            } else {
                Strings.printf("download_complete", Utils.formatSpeed(traffic * 1000L / safeTime),
                        Utils.formatTime(time), Utils.formatFileSize(traffic));
            }
        }

        @Override
        public void onIncomplete() {
            Strings.printf("transfer_failed");
        }
    };

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

        AdbManager adbManager = null;
        final String serverAddress;
        int requestedAdbStreams = opts.adbStreams != null ? opts.adbStreams : 0;

        try {
            if ("adb".equalsIgnoreCase(opts.connect)) {
                AdbManager.AdbConfig adbConfig = buildAdbConfig(opts);
                adbManager = new AdbManager(adbConfig, (eventName, payload) -> {
                    if (IpcEvents.ADB_VERSION_WARNING.equals(eventName)
                            && payload instanceof IpcPayloads.DiagnosticWarningPayload) {
                        IpcPayloads.DiagnosticWarningPayload warn = (IpcPayloads.DiagnosticWarningPayload) payload;
                        System.err.println("[WARN][" + warn.code + "] " + warn.message);
                    }
                });
                adbManager.registerShutdownHook();

                AdbManager.ForwardResult forward = adbManager.forwardPort(
                        ProtocolConstants.DEFAULT_CONTROL_PORT,
                        ProtocolConstants.DEFAULT_CONTROL_PORT,
                        opts.device);
                if (!forward.success) {
                    if (!forward.stderr.isEmpty()) {
                        System.err.println(forward.stderr.trim());
                    }
                    return;
                }

                IpcDtos.AdbCapabilityInfo cap = forward.capability;
                if (cap != null) {
                    requestedAdbStreams = cap.recommendedStreams;
                    System.out.printf("ADB: %s (v%s, libusb=%s, delayed_ack=%s, streams=%d)%n",
                            cap.adbPath, cap.version, cap.hasLibusb, cap.hasDelayedAck, requestedAdbStreams);
                }
                System.out.println(Strings.get("adb_forward_succeed"));
                serverAddress = "127.0.0.1";
            } else {
                serverAddress = opts.connect;
            }

            String effectiveHomeDir = resolveEffectiveHomeDir(opts.homeDir);
            JdkHFXClient hfxClient = new JdkHFXClient(
                    serverAddress, ProtocolConstants.DEFAULT_CONTROL_PORT, effectiveHomeDir);
            if (requestedAdbStreams > 0) {
                hfxClient.setRequestedAdbStreams(requestedAdbStreams);
            }
            if (opts.disableAutoBind) {
                hfxClient.setAutoSubnetBindEnabled(false);
            }
            if (hfxClient.connect(connectServerCallback)) {
                hfxClient.start(clientCallBack);
            }
        } finally {
            if (adbManager != null) {
                adbManager.close();
            }
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
