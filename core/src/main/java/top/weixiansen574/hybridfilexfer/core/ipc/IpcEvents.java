package top.weixiansen574.hybridfilexfer.core.ipc;

/**
 * Unified IPC server-push event names shared by PC JSON-RPC 2.0 and Android AIDL callbacks.
 */
public final class IpcEvents {
    public static final String PAIR_QR_READY = "pair.qr_ready";
    public static final String PAIR_SUCCEEDED = "pair.succeeded";
    public static final String PAIR_FAILED = "pair.failed";
    public static final String PAIR_EXPIRED = "pair.expired";

    public static final String MDNS_PEER_DISCOVERED = "mdns.peer_discovered";
    public static final String MDNS_PEER_LOST = "mdns.peer_lost";

    public static final String SESSION_STATE_CHANGED = "session.state_changed";
    public static final String RAIL_STATUS = "rail.status";
    public static final String RAIL_CONFLICT_RESOLVED = "rail.conflict_resolved";

    public static final String ADB_VERSION_WARNING = "adb.version_warning";
    public static final String DIAGNOSTIC_WARNING = "diagnostic.warning";

    public static final String TRANSFER_PROGRESS = "transfer.progress";
    public static final String TRANSFER_COMPLETED = "transfer.completed";
    public static final String TRANSFER_ERROR = "transfer.error";

    private IpcEvents() {
    }
}
