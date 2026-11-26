package top.weixiansen574.hybridfilexfer.core.ipc;

/**
 * Unified IPC command method names shared by PC JSON-RPC 2.0 and Android AIDL/JSON-RPC bridges.
 */
public final class IpcMethods {
    public static final String ENGINE_GET_SNAPSHOT = "engine.get_snapshot";
    public static final String ENGINE_REFRESH_NICS = "engine.refresh_nics";
    public static final String ENGINE_SHUTDOWN = "engine.shutdown";

    public static final String PAIR_START_QR = "pair.start_qr";
    public static final String PAIR_CANCEL = "pair.cancel";
    public static final String PAIR_SUBMIT_QR_URI = "pair.submit_qr_uri";

    public static final String PEERS_LIST = "peers.list";
    public static final String PEERS_REMOVE = "peers.remove";

    public static final String SERVER_START = "server.start";
    public static final String SERVER_STOP = "server.stop";

    public static final String SESSION_CONNECT = "session.connect";
    public static final String SESSION_DISCONNECT = "session.disconnect";

    public static final String FS_LIST = "fs.list";
    public static final String FS_MKDIR = "fs.mkdir";
    public static final String FS_DELETE = "fs.delete";

    public static final String TRANSFER_START = "transfer.start";
    public static final String TRANSFER_CANCEL = "transfer.cancel";

    private IpcMethods() {
    }
}
