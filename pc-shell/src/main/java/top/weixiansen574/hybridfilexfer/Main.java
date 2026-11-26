package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin backwards-compatible CLI shell preserving legacy {@code -c adb} / {@code -c <ip>} invocation.
 * In M3/M4 this shell delegates exclusively through the pc-engine IPC contract.
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
        Map<String, String> paramMap = new HashMap<>();
        parseArguments(paramMap, args);

        if (paramMap.containsKey("-v")) {
            System.out.println("HybridFileXfer v" + ProtocolConstants.ENGINE_VERSION
                    + " (protocol " + ProtocolConstants.VERSION_CODE + ")");
            return;
        }

        if (paramMap.containsKey("-h") || paramMap.isEmpty()) {
            Strings.printf("help_pages");
            return;
        }

        final String connect = paramMap.get("-c");
        String homeDir = paramMap.get("-d");
        final String device = paramMap.get("-s");

        if (homeDir == null) {
            homeDir = "/";
        }

        if (connect == null || connect.isEmpty()) {
            System.err.println(Strings.get("control_chanel_null"));
            Strings.printf("see_help");
            return;
        }

        final String serverAddress;
        if ("adb".equals(connect)) {
            if (executeAdbForwardCommand(ProtocolConstants.DEFAULT_CONTROL_PORT, device)) {
                System.out.println(Strings.get("adb_forward_succeed"));
                serverAddress = "127.0.0.1";
            } else {
                return;
            }
        } else {
            serverAddress = connect;
        }

        JdkHFXClient hfxClient = new JdkHFXClient(serverAddress, ProtocolConstants.DEFAULT_CONTROL_PORT, homeDir);
        if (hfxClient.connect(connectServerCallback)) {
            hfxClient.start(clientCallBack);
        }
    }

    private static void parseArguments(Map<String, String> paramMap, String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                String value = null;
                String key = arg;
                if (arg.contains("=")) {
                    final int eqPos = arg.indexOf('=');
                    if (eqPos < arg.length() - 1) {
                        value = arg.substring(eqPos + 1);
                    }
                    key = arg.substring(0, eqPos);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    value = args[i + 1];
                    i++;
                }

                switch (key) {
                    case "--connect": key = "-c"; break;
                    case "--switch-device": key = "-s"; break;
                    case "--dir": key = "-d"; break;
                    case "--help": key = "-h"; break;
                    case "--version": key = "-v"; break;
                    case "-c":
                    case "-s":
                    case "-d":
                    case "-h":
                    case "-v":
                        break;
                    default:
                        Strings.printf("unknown_option", key);
                        Strings.printf("see_help");
                        System.exit(1);
                }
                paramMap.put(key, value);
            }
        }
    }

    public static boolean executeAdbForwardCommand(int port, String device) {
        try {
            String jarDirectory = System.getProperty("user.dir");
            ProcessBuilder versionPb = new ProcessBuilder("adb", "version");
            Process process = versionPb.start();
            int exitCode = process.waitFor();
            String adbPath = (exitCode == 0) ? "adb" : "./adb";

            ProcessBuilder forwardPb = (device != null && !device.isEmpty())
                    ? new ProcessBuilder(adbPath, "-s", device, "forward", "tcp:" + port, "tcp:" + port)
                    : new ProcessBuilder(adbPath, "forward", "tcp:" + port, "tcp:" + port);
            forwardPb.directory(new File(jarDirectory));
            Process forwardProcess = forwardPb.start();
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(forwardProcess.getErrorStream()));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(forwardProcess.getInputStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    System.err.println(line);
                }
                while ((line = reader.readLine()) != null) {
                    System.out.println("adb:" + line);
                }
            }
            return forwardProcess.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }
}
