package top.weixiansen574.hybridfilexfer.pc.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcError;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcRequest;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Headless PC engine entry point (M1 bootstrap; expanded with full state machine,
 * ADB turbo manager, multi-transport IPC, mDNS, and PNG QR in M2-M4).
 *
 * <p>Strictly zero UI / Swing / Toast / user-facing console presentation logic:
 * stdout is reserved solely for NDJSON JSON-RPC 2.0 frames when running in
 * {@code --ipc=stdio} mode.</p>
 */
public final class PcEngineMain {
    private PcEngineMain() {
    }

    public static IpcDtos.EngineSnapshot createInitialSnapshot(String deviceId) {
        IpcDtos.EngineSnapshot snapshot = new IpcDtos.EngineSnapshot();
        snapshot.engineRole = "pc";
        snapshot.protocolVersion = ProtocolConstants.VERSION_CODE;
        snapshot.engineVersion = ProtocolConstants.ENGINE_VERSION;
        snapshot.state = SessionState.IDLE;
        snapshot.deviceId = deviceId;
        try {
            snapshot.deviceName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            snapshot.deviceName = "PC";
        }
        return snapshot;
    }

    public static void main(String[] args) throws Exception {
        String deviceId = UUID.randomUUID().toString();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonRpcResponse response;
                try {
                    JsonRpcRequest req = IpcJson.fromJson(trimmed, JsonRpcRequest.class);
                    if (req == null || req.method == null) {
                        response = JsonRpcResponse.error(null, JsonRpcError.INVALID_REQUEST, "Missing method");
                    } else if (IpcMethods.ENGINE_GET_SNAPSHOT.equals(req.method)) {
                        response = JsonRpcResponse.success(req.id, IpcJson.toJsonTree(createInitialSnapshot(deviceId)));
                    } else if (IpcMethods.ENGINE_SHUTDOWN.equals(req.method)) {
                        response = JsonRpcResponse.success(req.id, IpcJson.toJsonTree(
                                new top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads.OkResult(true)));
                        writer.write(IpcJson.toJson(response));
                        writer.newLine();
                        writer.flush();
                        break;
                    } else {
                        response = JsonRpcResponse.error(req.id, JsonRpcError.METHOD_NOT_FOUND,
                                "Method not available yet: " + req.method);
                    }
                } catch (Exception parseEx) {
                    response = JsonRpcResponse.error(null, JsonRpcError.PARSE_ERROR, parseEx.getMessage());
                }
                writer.write(IpcJson.toJson(response));
                writer.newLine();
                writer.flush();
            }
        }
    }
}
