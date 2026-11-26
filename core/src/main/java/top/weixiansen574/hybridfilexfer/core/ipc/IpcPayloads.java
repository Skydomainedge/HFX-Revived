package top.weixiansen574.hybridfilexfer.core.ipc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared IPC command parameter and event payload models matching RFC Section 5.2.
 */
public final class IpcPayloads {
    private IpcPayloads() {
    }

    // ---- Command Params ----

    public static class PairStartParams {
        public int ttlSeconds = 120;
        public int pngSize = 480;
    }

    public static class PairSubmitUriParams {
        public String uri;
    }

    public static class PeerRemoveParams {
        public String deviceId;
    }

    public static class ServerStartParams {
        public int port = 5740;
        public int ioMode = 0;
        public List<IpcDtos.RailSelection> selectedRails = new ArrayList<>();
        public int localBufferCount = 256;
        public int remoteBufferCount = 512;
        public boolean authRequired = false;
        public int preferredBlockSize = 1024 * 1024;
    }

    public static class SessionConnectParams {
        public String mode = "auto"; // "auto" | "adb" | "direct"
        public String host;
        public int port = 5740;
        public String serial;
        public String homeDir = "/";
        public Integer adbStreams;
        public String deviceId;
        public String adbPath;
        public boolean adbLegacy = false;
        public boolean autoSubnetBind = true;
    }

    public static class FsListParams {
        public String side = "local"; // "local" | "remote"
        public String path;
    }

    public static class FsListResult {
        public String side;
        public String path;
        public List<IpcDtos.FsEntryDto> entries = new ArrayList<>();

        public FsListResult() {
        }

        public FsListResult(String side, String path, List<IpcDtos.FsEntryDto> entries) {
            this.side = side;
            this.path = path;
            if (entries != null) {
                this.entries.addAll(entries);
            }
        }
    }

    public static class NicsRefreshResult {
        public List<IpcDtos.NicInfo> nics = new ArrayList<>();
        public String udcSpeed;
        public boolean vpnActive;
        public RailConflictResolvedPayload conflictResolved;
        public DiagnosticWarningPayload vpnWarning;
    }

    public static class PeersListResult {
        public List<IpcDtos.PeerInfo> peers = new ArrayList<>();
    }

    public static class FsMkdirParams {
        public String side = "local";
        public String parent;
        public String child;
    }

    public static class FsDeleteParams {
        public String side = "local";
        public String path;
    }

    public static class TransferStartParams {
        public String direction; // "upload" | "download"
        public List<String> files = new ArrayList<>();
        public String srcDir;
        public String dstDir;
        public int remoteFileSystem = -1; // -1 = auto-detect
    }

    // ---- Event / Result Payloads ----

    public static class OkResult {
        public boolean ok;
        public String detail;

        public OkResult() {
        }

        public OkResult(boolean ok) {
            this.ok = ok;
        }

        public OkResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    public static class PairQrReadyPayload {
        public String pairUri;
        public String qrPngPath;
        public String hostName;
        public List<String> ips = new ArrayList<>();
        public int port;
        public long expiresAt;
    }

    public static class PairSucceededPayload {
        public String peerId;
        public String peerName;
        public List<String> controlIps = new ArrayList<>();
        public boolean viaFallbackMdns;
    }

    public static class PairErrorPayload {
        public String reason;
        public String code;

        public PairErrorPayload() {
        }

        public PairErrorPayload(String code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

    public static class SessionStateChangedPayload {
        public SessionState prev;
        public SessionState current;
        public String detail;

        public SessionStateChangedPayload() {
        }

        public SessionStateChangedPayload(SessionState prev, SessionState current, String detail) {
            this.prev = prev;
            this.current = current;
            this.detail = detail;
        }
    }

    public static class RailStatusPayload {
        public List<IpcDtos.RailStatusInfo> rails = new ArrayList<>();
    }

    public static class RailConflictResolvedPayload {
        public String disabledRail;
        public List<String> activeUsbNics = new ArrayList<>();
        public String reason;
    }

    public static class DiagnosticWarningPayload {
        public String code;
        public String message;

        public DiagnosticWarningPayload() {
        }

        public DiagnosticWarningPayload(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    public static class TransferProgressPayload {
        public String direction;
        public long totalBytes;
        public long transferredBytes;
        public long aggregateBps;
        public Map<String, Long> perRailBps = new LinkedHashMap<>();
        public Map<String, String> perRailCurrentFile = new LinkedHashMap<>();
        public String currentFile;
        public long elapsedMs;
    }

    public static class TransferCompletedPayload {
        public String direction;
        public long totalBytes;
        public long elapsedMs;
        public long avgBps;
        public String error;
    }
}
