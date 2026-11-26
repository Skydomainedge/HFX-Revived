package top.weixiansen574.hybridfilexfer.core.ipc;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared IPC state & domain DTOs for both PC and Android engines.
 */
public final class IpcDtos {
    private IpcDtos() {
    }

    public static class NicInfo {
        public String name;
        public String displayName;
        public String ip;
        public int prefixLength;
        public String railType;
        public boolean recommended;
        public boolean selected;
        public String clientBindIp;
        public boolean usbNic;
        public boolean vpn;
        public String reason;

        public NicInfo() {
        }
    }

    public static class AdbCapabilityInfo {
        public boolean available;
        public String adbPath;
        public String version;
        public boolean hasLibusb;
        public boolean hasDelayedAck;
        public boolean burstModeEnvInjected;
        public int recommendedStreams;
        public String warning;

        public AdbCapabilityInfo() {
        }
    }

    public static class RailStatusInfo {
        public int railId;
        public String name;
        public String type;
        public String address;
        public String bindAddress;
        public int streamCount;
        public String state;
        public String udcSpeed;
        public String lastError;

        public RailStatusInfo() {
        }
    }

    public static class PeerInfo {
        public String deviceId;
        public String deviceName;
        public List<String> ips = new ArrayList<>();
        public int port;
        public boolean trusted;
        public String usbType;
        public long lastSeenAt;
        public long pairedAt;

        public PeerInfo() {
        }
    }

    public static class EngineSnapshot {
        public String engineRole; // "pc" or "android"
        public int protocolVersion;
        public String engineVersion;
        public SessionState state = SessionState.IDLE;
        public String deviceId;
        public String deviceName;
        public String udcSpeed;
        public boolean vpnActive;
        public AdbCapabilityInfo adb;
        public List<NicInfo> nics = new ArrayList<>();
        public List<RailStatusInfo> rails = new ArrayList<>();
        public List<PeerInfo> trustedPeers = new ArrayList<>();
        public List<PeerInfo> discoveredPeers = new ArrayList<>();
        public String remoteHomeDir;
        public int remoteFileSystem;

        public EngineSnapshot() {
        }
    }

    public static class FsEntryDto {
        public String name;
        public String path;
        public long lastModified;
        public long size;
        public boolean directory;

        public FsEntryDto() {
        }

        public FsEntryDto(String name, String path, long lastModified, long size, boolean directory) {
            this.name = name;
            this.path = path;
            this.lastModified = lastModified;
            this.size = size;
            this.directory = directory;
        }
    }

    public static class RailSelection {
        public String name;
        public String ip;
        public String bindIp;
        public String railType;
        public int streamCount = 1;

        public RailSelection() {
        }
    }
}
