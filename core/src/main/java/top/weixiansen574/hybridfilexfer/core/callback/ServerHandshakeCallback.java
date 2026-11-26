package top.weixiansen574.hybridfilexfer.core.callback;

/**
 * Pure Java server startup and handshake callback (no Android / UI dependencies).
 */
public interface ServerHandshakeCallback {
    void onBindFailed(int port);

    void onServerStarted();

    default void onAuthFailed(String clientId, String reason) {
    }

    default void onAuthSucceeded(String clientId) {
    }

    void onRailAccepted(String name, int railId, int streamIndex, int streamCount);

    void onAcceptFailed(String name);

    void onRemoteOOM();

    void onLocalOOM(int created, int localBufferCount);

    void onConnectSuccess();
}
