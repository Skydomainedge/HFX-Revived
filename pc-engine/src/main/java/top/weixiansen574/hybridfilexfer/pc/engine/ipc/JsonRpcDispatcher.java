package top.weixiansen574.hybridfilexfer.pc.engine.ipc;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcError;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcRequest;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.pc.engine.PcEngineRuntime;

/**
 * Unified JSON-RPC 2.0 request router, token authenticator, and multi-subscriber event broadcaster.
 *
 * <p>Shared identically by {@link StdioIpcTransport} (Sidecar mode) and
 * {@link LocalSocketIpcTransport} (Unix domain socket / Loopback + ephemeral token mode).</p>
 */
public class JsonRpcDispatcher {

    /**
     * Event sink implemented by each connected transport client.
     */
    @FunctionalInterface
    public interface EventSink {
        void sendNotification(String ndjsonLine);
    }

    /**
     * Dispatch outcome indicating both the serialized JSON-RPC response and whether
     * {@code engine.shutdown} was accepted.
     */
    public static final class DispatchResult {
        public final JsonRpcResponse response;
        public final boolean shutdownRequested;

        public DispatchResult(JsonRpcResponse response, boolean shutdownRequested) {
            this.response = response;
            this.shutdownRequested = shutdownRequested;
        }

        public String toJsonLine() {
            return IpcJson.toJson(response);
        }
    }

    private final PcEngineRuntime runtime;
    private final String requiredToken;
    private final CopyOnWriteArrayList<EventSink> subscribers = new CopyOnWriteArrayList<>();

    public JsonRpcDispatcher(PcEngineRuntime runtime) {
        this(runtime, null);
    }

    public JsonRpcDispatcher(PcEngineRuntime runtime, String requiredToken) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        this.runtime = runtime;
        this.requiredToken = (requiredToken != null && !requiredToken.isEmpty()) ? requiredToken : null;
        this.runtime.addEventListener(this::broadcastEvent);
    }

    public PcEngineRuntime getRuntime() {
        return runtime;
    }

    public boolean isTokenAuthEnabled() {
        return requiredToken != null;
    }

    public void addSubscriber(EventSink sink) {
        if (sink != null) {
            subscribers.addIfAbsent(sink);
        }
    }

    public void removeSubscriber(EventSink sink) {
        if (sink != null) {
            subscribers.remove(sink);
        }
    }

    public int getSubscriberCount() {
        return subscribers.size();
    }

    public DispatchResult dispatchLine(String rawLine) {
        JsonRpcRequest req;
        try {
            req = IpcJson.fromJson(rawLine, JsonRpcRequest.class);
        } catch (JsonSyntaxException e) {
            return new DispatchResult(
                    JsonRpcResponse.error(null, JsonRpcError.PARSE_ERROR, "Malformed JSON: " + e.getMessage()),
                    false);
        }
        if (req == null || req.method == null || req.method.trim().isEmpty()) {
            return new DispatchResult(
                    JsonRpcResponse.error(req != null ? req.id : null,
                            JsonRpcError.INVALID_REQUEST, "Missing JSON-RPC method"),
                    false);
        }
        return dispatchRequest(req);
    }

    public DispatchResult dispatchRequest(JsonRpcRequest req) {
        JsonElement id = req.id;
        if (requiredToken != null && !constantTimeEquals(requiredToken, req.token)) {
            return new DispatchResult(
                    JsonRpcResponse.error(id, JsonRpcError.UNAUTHORIZED, "Invalid or missing IPC token"),
                    false);
        }

        String method = req.method.trim();
        try {
            switch (method) {
                case IpcMethods.ENGINE_GET_SNAPSHOT:
                    return ok(id, runtime.getSnapshot(), false);

                case IpcMethods.ENGINE_REFRESH_NICS:
                    return ok(id, runtime.refreshNics(), false);

                case IpcMethods.ENGINE_SHUTDOWN:
                    return ok(id, runtime.shutdown(), true);

                case IpcMethods.SESSION_CONNECT: {
                    IpcPayloads.SessionConnectParams params = decodeParams(req, IpcPayloads.SessionConnectParams.class);
                    return ok(id, runtime.connect(params), false);
                }

                case IpcMethods.SESSION_DISCONNECT:
                    return ok(id, runtime.disconnect(), false);

                case IpcMethods.FS_LIST: {
                    IpcPayloads.FsListParams params = decodeParams(req, IpcPayloads.FsListParams.class);
                    return ok(id, runtime.listFiles(params), false);
                }

                case IpcMethods.FS_MKDIR: {
                    IpcPayloads.FsMkdirParams params = decodeParams(req, IpcPayloads.FsMkdirParams.class);
                    return ok(id, runtime.mkdir(params), false);
                }

                case IpcMethods.FS_DELETE: {
                    IpcPayloads.FsDeleteParams params = decodeParams(req, IpcPayloads.FsDeleteParams.class);
                    return ok(id, runtime.deleteFile(params), false);
                }

                case IpcMethods.TRANSFER_START: {
                    IpcPayloads.TransferStartParams params = decodeParams(req, IpcPayloads.TransferStartParams.class);
                    return ok(id, runtime.startTransfer(params), false);
                }

                case IpcMethods.TRANSFER_CANCEL:
                    return ok(id, runtime.cancelTransfer(), false);

                case IpcMethods.PAIR_START_QR: {
                    IpcPayloads.PairStartParams params = decodeParams(req, IpcPayloads.PairStartParams.class);
                    return ok(id, runtime.startQrPairing(params), false);
                }

                case IpcMethods.PAIR_CANCEL:
                    return ok(id, runtime.cancelPairing(), false);

                case IpcMethods.PEERS_LIST:
                    return ok(id, runtime.listTrustedPeers(), false);

                case IpcMethods.PEERS_REMOVE: {
                    IpcPayloads.PeerRemoveParams params = decodeParams(req, IpcPayloads.PeerRemoveParams.class);
                    return ok(id, runtime.removeTrustedPeer(params), false);
                }

                case IpcMethods.PAIR_SUBMIT_QR_URI:
                    return new DispatchResult(
                            JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND,
                                    "Method is only supported on Android engine: " + method),
                            false);

                default:
                    return new DispatchResult(
                            JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Unknown method: " + method),
                            false);
            }
        } catch (PcEngineRuntime.EngineException e) {
            return new DispatchResult(JsonRpcResponse.error(id, e.errorCode, e.getMessage()), false);
        } catch (JsonSyntaxException e) {
            return new DispatchResult(
                    JsonRpcResponse.error(id, JsonRpcError.INVALID_PARAMS, "Invalid params: " + e.getMessage()),
                    false);
        } catch (Exception e) {
            return new DispatchResult(
                    JsonRpcResponse.error(id, JsonRpcError.INTERNAL_ERROR,
                            e.getMessage() != null ? e.getMessage() : e.toString()),
                    false);
        }
    }

    private void broadcastEvent(String eventName, Object payload) {
        if (subscribers.isEmpty()) {
            return;
        }
        String line = IpcJson.encodeEvent(eventName, payload);
        for (EventSink sink : subscribers) {
            try {
                sink.sendNotification(line);
            } catch (Exception ignored) {
            }
        }
    }

    private static DispatchResult ok(JsonElement id, Object result, boolean shutdownRequested) {
        return new DispatchResult(JsonRpcResponse.success(id, IpcJson.toJsonTree(result)), shutdownRequested);
    }

    private static <T> T decodeParams(JsonRpcRequest req, Class<T> clazz) {
        if (req.params == null) {
            return null;
        }
        return IpcJson.fromJson(req.params, clazz);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
