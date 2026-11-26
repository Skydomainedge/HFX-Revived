package top.weixiansen574.hybridfilexfer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;

/**
 * Thin UI-layer helper for decoding JSON-RPC 2.0 responses returned by {@code IHfxEngine} AIDL.
 */
public final class EngineIpcHelper {
    private EngineIpcHelper() {
    }

    public static <T> T unwrap(String responseJson, Class<T> resultClass) throws IOException {
        if (responseJson == null || responseJson.trim().isEmpty()) {
            throw new IOException("Empty response from HfxEngineService");
        }
        JsonRpcResponse resp = IpcJson.fromJson(responseJson, JsonRpcResponse.class);
        if (resp == null) {
            throw new IOException("Null JSON-RPC response from HfxEngineService");
        }
        if (!resp.isSuccess()) {
            String msg = (resp.error != null && resp.error.message != null)
                    ? resp.error.message
                    : "Engine IPC error";
            throw new IOException(msg);
        }
        if (resp.result == null || resp.result.isJsonNull()) {
            return null;
        }
        return IpcJson.fromJson(resp.result, resultClass);
    }

    public static List<RemoteFile> toRemoteFiles(IpcPayloads.FsListResult listResult) {
        if (listResult == null || listResult.entries == null) {
            return null;
        }
        List<RemoteFile> out = new ArrayList<>(listResult.entries.size());
        for (IpcDtos.FsEntryDto dto : listResult.entries) {
            out.add(new RemoteFile(dto.name, dto.path, dto.lastModified, dto.size, dto.directory));
        }
        return out;
    }
}
