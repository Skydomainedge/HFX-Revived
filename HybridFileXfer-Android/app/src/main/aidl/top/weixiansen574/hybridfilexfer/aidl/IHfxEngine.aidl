package top.weixiansen574.hybridfilexfer.aidl;

import top.weixiansen574.hybridfilexfer.aidl.IHfxEngineCallback;

/**
 * Unified Android headless business engine IPC contract aligned 1:1 with :core/ipc IpcMethods.
 *
 * <p>All command methods return a single-line JSON-RPC 2.0 response envelope
 * ({@code {"jsonrpc":"2.0","result":...}} or {@code {"jsonrpc":"2.0","error":{"code":...,"message":...}}})
 * using the exact DTOs defined in {@code :core/ipc}.</p>
 */
interface IHfxEngine {
    String executeCommand(String method, String paramsJson);

    String getSnapshotJson();
    String refreshNicsJson();

    String startServer(String paramsJson);
    String stopServer();
    String disconnect();

    String listFilesJson(String side, String path);
    String mkdir(String side, String parent, String child);
    String deleteFile(String side, String path);

    String startTransfer(String paramsJson);
    String cancelTransfer();

    void registerCallback(IHfxEngineCallback callback);
    void unregisterCallback(IHfxEngineCallback callback);
}
