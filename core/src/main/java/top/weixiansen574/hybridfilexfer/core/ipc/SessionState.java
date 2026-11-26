package top.weixiansen574.hybridfilexfer.core.ipc;

public enum SessionState {
    IDLE,
    DISCOVERING,
    PAIRING,
    CONNECTING,
    CONNECTED,
    TRANSFERRING,
    ERROR
}
