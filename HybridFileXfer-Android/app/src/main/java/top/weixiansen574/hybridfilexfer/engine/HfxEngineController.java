package top.weixiansen574.hybridfilexfer.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.AbstractHFXServer;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcError;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcRequest;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

/**
 * Pure-Java headless mobile engine controller encapsulating the Android state machine,
 * command dispatch, {@link UsbNicAdvisor}, {@link AbstractHFXServer} orchestration,
 * and 250ms-throttled event broadcasting.
 *
 * <p>Architecture red line: strictly zero {@code android.*} / UI / {@code Activity} /
 * {@code View} / {@code Widget} / {@code Toast} / {@code RecyclerView} / {@code PrintStream}
 * references so the full controller can be unit-tested on standard JVM.</p>
 */
public class HfxEngineController implements AutoCloseable {

    public static final long DEFAULT_PROGRESS_THROTTLE_MS = 250L;

    @FunctionalInterface
    public interface EventListener {
        void onEvent(String eventName, Object payload);
    }

    /**
     * Platform-neutral local file and server lifecycle backend (implemented by {@code HfxEngineService}
     * with {@code HFXServer} + {@code IIOService}/Shizuku on Android, or loopback servers in JVM tests).
     */
    public interface ServerSessionBackend {
        AbstractHFXServer acquireServer(int ioMode) throws Exception;

        void releaseServer(AbstractHFXServer server);

        List<RemoteFile> listLocalFiles(AbstractHFXServer server, String path) throws Exception;

        boolean createLocalDir(AbstractHFXServer server, String parent, String child) throws Exception;

        boolean deleteLocalFile(AbstractHFXServer server, String path) throws Exception;

        RemoteFile resolveLocalFile(AbstractHFXServer server, String path) throws Exception;
    }

    private final String deviceId;
    private final String deviceName;
    private final UsbNicAdvisor usbNicAdvisor;
    private final ServerSessionBackend backend;
    private final TrustedPcStore trustedPcStore;
    private final MdnsAdvertisementBridge mdnsBridge;
    private final PairClient pairClient;
    private final long progressThrottleMs;

    private final CopyOnWriteArrayList<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("hfx-android-server"));
    private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("hfx-android-transfer"));
    private final ExecutorService pairExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("hfx-android-pair"));

    private volatile SessionState state = SessionState.IDLE;
    private volatile UsbNicAdvisor.AdviceResult lastAdvice;
    private volatile List<IpcDtos.RailStatusInfo> railStatuses = Collections.emptyList();
    private volatile AbstractHFXServer activeServer;
    private volatile CountDownLatch activeServerBoundLatch;
    private volatile int activeServerPort = ProtocolConstants.DEFAULT_CONTROL_PORT;
    private volatile String activePairAck;
    private volatile int defaultPairServerPort = ProtocolConstants.DEFAULT_CONTROL_PORT;
    private volatile int defaultPairIoMode = 0;
    private volatile int defaultPairLocalBuffers = 256;
    private volatile int defaultPairRemoteBuffers = 512;
    private volatile String remoteHomeDir = "/";
    private volatile int remoteFileSystem = Directory.FILE_SYSTEM_UNIX;
    private volatile IpcPayloads.TransferProgressPayload lastTransferProgress;
    private volatile String activeTransferDirection;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong sessionSeq = new AtomicLong(0L);

    public HfxEngineController(String deviceId,
                               String deviceName,
                               UsbNicAdvisor usbNicAdvisor,
                               ServerSessionBackend backend) {
        this(deviceId, deviceName, usbNicAdvisor, backend, DEFAULT_PROGRESS_THROTTLE_MS,
                new TrustedPcStore(), MdnsAdvertisementBridge.NO_OP, new PairClient());
    }

    public HfxEngineController(String deviceId,
                               String deviceName,
                               UsbNicAdvisor usbNicAdvisor,
                               ServerSessionBackend backend,
                               long progressThrottleMs) {
        this(deviceId, deviceName, usbNicAdvisor, backend, progressThrottleMs,
                new TrustedPcStore(), MdnsAdvertisementBridge.NO_OP, new PairClient());
    }

    public HfxEngineController(String deviceId,
                               String deviceName,
                               UsbNicAdvisor usbNicAdvisor,
                               ServerSessionBackend backend,
                               long progressThrottleMs,
                               TrustedPcStore trustedPcStore,
                               MdnsAdvertisementBridge mdnsBridge,
                               PairClient pairClient) {
        this.deviceId = (deviceId != null && !deviceId.trim().isEmpty())
                ? deviceId.trim() : UUID.randomUUID().toString();
        this.deviceName = (deviceName != null && !deviceName.trim().isEmpty())
                ? deviceName.trim() : "Android";
        this.usbNicAdvisor = usbNicAdvisor != null ? usbNicAdvisor : new UsbNicAdvisor();
        this.backend = backend;
        this.progressThrottleMs = Math.max(100L, progressThrottleMs);
        this.trustedPcStore = trustedPcStore != null ? trustedPcStore : new TrustedPcStore();
        this.mdnsBridge = mdnsBridge != null ? mdnsBridge : MdnsAdvertisementBridge.NO_OP;
        this.pairClient = pairClient != null ? pairClient : new PairClient();

        this.trustedPcStore.setPendingCommitListener(new TrustedPcStore.PendingCommitListener() {
            @Override
            public void onPendingPairingCommitted(TrustedPcStore.TrustedPcRecord record, boolean viaFallbackMdns) {
                activePairAck = null;
                HfxEngineController.this.mdnsBridge.updatePairAck(null);
                IpcPayloads.PairSucceededPayload ok = new IpcPayloads.PairSucceededPayload();
                ok.peerId = record.pcId;
                ok.peerName = record.pcName;
                ok.controlIps = record.lastIps != null ? new ArrayList<>(record.lastIps) : new ArrayList<>();
                ok.viaFallbackMdns = viaFallbackMdns;
                emitEvent(IpcEvents.PAIR_SUCCEEDED, ok);
            }
        });
    }

    public void configurePairDefaults(int serverPort, int ioMode, int localBuffers, int remoteBuffers) {
        if (serverPort > 0) this.defaultPairServerPort = serverPort;
        this.defaultPairIoMode = Math.max(0, ioMode);
        if (localBuffers >= 16) this.defaultPairLocalBuffers = localBuffers;
        if (remoteBuffers >= 16) this.defaultPairRemoteBuffers = remoteBuffers;
    }

    public TrustedPcStore getTrustedPcStore() {
        return trustedPcStore;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void addEventListener(EventListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeEventListener(EventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public SessionState getState() {
        return state;
    }

    public IpcPayloads.TransferProgressPayload getLastTransferProgress() {
        return lastTransferProgress;
    }

    public String getActiveTransferDirection() {
        return activeTransferDirection;
    }

    public String dispatchCommandJson(String method, String paramsJson) {
        JsonObject params = null;
        if (paramsJson != null && !paramsJson.trim().isEmpty()) {
            try {
                JsonElement parsed = IpcJson.parse(paramsJson);
                if (parsed != null && parsed.isJsonObject()) {
                    params = parsed.getAsJsonObject();
                } else {
                    return IpcJson.toJson(JsonRpcResponse.error(
                            null, JsonRpcError.INVALID_PARAMS, "paramsJson must be a JSON object"));
                }
            } catch (JsonSyntaxException e) {
                return IpcJson.toJson(JsonRpcResponse.error(
                        null, JsonRpcError.PARSE_ERROR, "Malformed paramsJson: " + e.getMessage()));
            }
        }
        return IpcJson.toJson(dispatch(null, method, params));
    }

    public String dispatchRawJsonRpcLine(String rawLine) {
        JsonRpcRequest req;
        try {
            req = IpcJson.fromJson(rawLine, JsonRpcRequest.class);
        } catch (JsonSyntaxException e) {
            return IpcJson.toJson(JsonRpcResponse.error(null, JsonRpcError.PARSE_ERROR, e.getMessage()));
        }
        if (req == null || req.method == null || req.method.trim().isEmpty()) {
            return IpcJson.toJson(JsonRpcResponse.error(
                    req != null ? req.id : null, JsonRpcError.INVALID_REQUEST, "Missing method"));
        }
        return IpcJson.toJson(dispatch(req.id, req.method, req.params));
    }

    public JsonRpcResponse dispatch(JsonElement id, String method, JsonObject params) {
        if (method == null || method.trim().isEmpty()) {
            return JsonRpcResponse.error(id, JsonRpcError.INVALID_REQUEST, "Missing method");
        }
        String m = method.trim();
        try {
            if (IpcMethods.ENGINE_GET_SNAPSHOT.equals(m)) {
                return ok(id, getSnapshot());
            } else if (IpcMethods.ENGINE_REFRESH_NICS.equals(m)) {
                return ok(id, refreshNics());
            } else if (IpcMethods.SERVER_START.equals(m)) {
                IpcPayloads.ServerStartParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.ServerStartParams.class)
                        : new IpcPayloads.ServerStartParams();
                return ok(id, startServer(p));
            } else if (IpcMethods.SERVER_STOP.equals(m) || IpcMethods.SESSION_DISCONNECT.equals(m)) {
                return ok(id, stopServer());
            } else if (IpcMethods.FS_LIST.equals(m)) {
                IpcPayloads.FsListParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.FsListParams.class)
                        : new IpcPayloads.FsListParams();
                return ok(id, listFiles(p));
            } else if (IpcMethods.FS_MKDIR.equals(m)) {
                IpcPayloads.FsMkdirParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.FsMkdirParams.class)
                        : null;
                return ok(id, mkdir(p));
            } else if (IpcMethods.FS_DELETE.equals(m)) {
                IpcPayloads.FsDeleteParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.FsDeleteParams.class)
                        : null;
                return ok(id, deleteFile(p));
            } else if (IpcMethods.TRANSFER_START.equals(m)) {
                IpcPayloads.TransferStartParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.TransferStartParams.class)
                        : null;
                return ok(id, startTransfer(p));
            } else if (IpcMethods.TRANSFER_CANCEL.equals(m)) {
                return ok(id, cancelTransfer());
            } else if (IpcMethods.ENGINE_SHUTDOWN.equals(m)) {
                return ok(id, shutdown());
            } else if (IpcMethods.PAIR_SUBMIT_QR_URI.equals(m)) {
                IpcPayloads.PairSubmitUriParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.PairSubmitUriParams.class)
                        : null;
                return ok(id, submitQrUri(p));
            } else if (IpcMethods.PAIR_CANCEL.equals(m)) {
                return ok(id, cancelPairing());
            } else if (IpcMethods.PEERS_LIST.equals(m)) {
                return ok(id, listTrustedPeers());
            } else if (IpcMethods.PEERS_REMOVE.equals(m)) {
                IpcPayloads.PeerRemoveParams p = params != null
                        ? IpcJson.fromJson(params, IpcPayloads.PeerRemoveParams.class)
                        : null;
                return ok(id, removeTrustedPeer(p));
            } else if (IpcMethods.PAIR_START_QR.equals(m)) {
                return JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND,
                        "Method is only supported on PC engine: " + m);
            } else {
                return JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Unknown method: " + m);
            }
        } catch (ControllerException e) {
            return JsonRpcResponse.error(id, e.code, e.getMessage());
        } catch (JsonSyntaxException e) {
            return JsonRpcResponse.error(id, JsonRpcError.INVALID_PARAMS, "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            return JsonRpcResponse.error(id, JsonRpcError.INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    public IpcDtos.EngineSnapshot getSnapshot() {
        UsbNicAdvisor.AdviceResult advice = this.lastAdvice;
        if (advice == null) {
            try {
                advice = usbNicAdvisor.advise();
                this.lastAdvice = advice;
            } catch (SocketException ignored) {
            }
        }
        IpcDtos.EngineSnapshot snap = new IpcDtos.EngineSnapshot();
        snap.engineRole = "android";
        snap.protocolVersion = ProtocolConstants.VERSION_CODE;
        snap.engineVersion = ProtocolConstants.ENGINE_VERSION;
        snap.state = this.state;
        snap.deviceId = this.deviceId;
        snap.deviceName = this.deviceName;
        if (advice != null) {
            snap.udcSpeed = advice.udcSpeed;
            snap.vpnActive = advice.vpnActive;
            snap.nics = new ArrayList<>(advice.nics);
        } else {
            snap.udcSpeed = UsbNicAdvisor.SPEED_UNKNOWN;
        }
        snap.rails = new ArrayList<>(this.railStatuses);
        snap.trustedPeers = trustedPcStore.listPeers();
        snap.remoteHomeDir = this.remoteHomeDir;
        snap.remoteFileSystem = this.remoteFileSystem;
        return snap;
    }

    // ---- M4 Pairing & Trusted PC Management ----

    public IpcPayloads.OkResult submitQrUri(IpcPayloads.PairSubmitUriParams params) throws ControllerException {
        if (closed.get()) {
            throw new ControllerException(JsonRpcError.INVALID_STATE, "Engine is shut down");
        }
        if (params == null || params.uri == null || params.uri.trim().isEmpty()) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS, "uri is required");
        }
        final PairUriCodec.PairPayload payload;
        try {
            payload = PairClient.parseQrUri(params.uri.trim());
        } catch (IllegalArgumentException e) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS, "Invalid QR URI: " + e.getMessage());
        }

        pairExecutor.submit(new Runnable() {
            @Override
            public void run() {
                runPairingWorkflow(payload);
            }
        });
        String label = (payload.hostName != null && !payload.hostName.isEmpty()) ? payload.hostName : payload.pcId;
        return new IpcPayloads.OkResult(true, label);
    }

    public IpcPayloads.OkResult cancelPairing() {
        pairClient.cancel();
        trustedPcStore.clearPendingPairing();
        activePairAck = null;
        mdnsBridge.updatePairAck(null);
        return new IpcPayloads.OkResult(true, "Pairing cancelled");
    }

    public IpcPayloads.PeersListResult listTrustedPeers() {
        IpcPayloads.PeersListResult res = new IpcPayloads.PeersListResult();
        res.peers = trustedPcStore.listPeers();
        return res;
    }

    public IpcPayloads.OkResult removeTrustedPeer(IpcPayloads.PeerRemoveParams params) throws ControllerException {
        if (params == null || params.deviceId == null || params.deviceId.trim().isEmpty()) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS, "deviceId is required");
        }
        boolean removed = trustedPcStore.removePeer(params.deviceId.trim());
        return new IpcPayloads.OkResult(removed, removed ? "Peer removed" : "Peer not found");
    }

    private void runPairingWorkflow(final PairUriCodec.PairPayload payload) {
        final List<String> advertisedIps = new ArrayList<>();
        final String[] detectedUsbType = new String[1];

        pairClient.executePairing(
                payload,
                this.deviceId,
                this.deviceName,
                this.defaultPairServerPort,
                advertisedIps,
                null,
                new PairClient.PairCallback() {
                    @Override
                    public PairClient.PreparedServerInfo onPrepareServerAndPendingTrust(
                            PairUriCodec.PairPayload p, byte[] pairSecret) throws Exception {
                        trustedPcStore.registerPendingPairing(
                                p.pcId, p.hostName, pairSecret, p.ips, 120_000L, false);
                        ensureServerListeningForPairing();
                        collectAdvertisableMobileIps(advertisedIps, detectedUsbType);
                        return new PairClient.PreparedServerInfo(activeServerPort, advertisedIps, detectedUsbType[0]);
                    }

                    @Override
                    public void onDirectPairSucceeded(PairClient.PairOutcome outcome) {
                        trustedPcStore.upsertTrustedPc(
                                outcome.pcId, outcome.pcName, outcome.pairSecret, payload.ips);
                        activePairAck = null;
                        mdnsBridge.updatePairAck(null);
                        IpcPayloads.PairSucceededPayload ok = new IpcPayloads.PairSucceededPayload();
                        ok.peerId = outcome.pcId;
                        ok.peerName = outcome.pcName;
                        ok.controlIps = new ArrayList<>(payload.ips);
                        ok.viaFallbackMdns = false;
                        emitEvent(IpcEvents.PAIR_SUCCEEDED, ok);
                    }

                    @Override
                    public void onActivateMdnsFallback(PairClient.PairOutcome outcome) {
                        trustedPcStore.markPendingAsFallbackMdns(outcome.pcId);
                        activePairAck = outcome.pairAckHash;
                        List<String> mobileIps = new ArrayList<>();
                        String[] usbHolder = new String[1];
                        collectAdvertisableMobileIps(mobileIps, usbHolder);
                        mdnsBridge.startAdvertising(
                                activeServerPort,
                                deviceId,
                                deviceName,
                                mobileIps,
                                usbHolder[0],
                                outcome.pairAckHash);
                    }

                    @Override
                    public void onPairFailed(String code, String reason) {
                        trustedPcStore.clearPendingPairing();
                        activePairAck = null;
                        mdnsBridge.updatePairAck(null);
                        emitEvent(IpcEvents.PAIR_FAILED, new IpcPayloads.PairErrorPayload(code, reason));
                    }
                });
    }

    private void ensureServerListeningForPairing() throws Exception {
        if (state == SessionState.CONNECTED || state == SessionState.TRANSFERRING) {
            stopServer();
        }
        if (state == SessionState.IDLE || state == SessionState.ERROR) {
            IpcPayloads.ServerStartParams startParams = new IpcPayloads.ServerStartParams();
            startParams.port = defaultPairServerPort;
            startParams.ioMode = defaultPairIoMode;
            startParams.localBufferCount = defaultPairLocalBuffers;
            startParams.remoteBufferCount = defaultPairRemoteBuffers;
            startParams.authRequired = true;
            startServer(startParams);
        }
        CountDownLatch boundLatch = this.activeServerBoundLatch;
        if (boundLatch != null && !boundLatch.await(5, TimeUnit.SECONDS)) {
            throw new IOException("Timed out waiting for HFXServer to bind port " + defaultPairServerPort);
        }
        if (state == SessionState.ERROR) {
            throw new IOException("HFXServer failed to bind port " + defaultPairServerPort);
        }
    }

    private void collectAdvertisableMobileIps(List<String> outIps, String[] outUsbType) {
        outIps.clear();
        List<IpcDtos.RailStatusInfo> rails = this.railStatuses;
        if (rails != null) {
            for (IpcDtos.RailStatusInfo r : rails) {
                if (r.address != null && !r.address.isEmpty() && !"127.0.0.1".equals(r.address)) {
                    if (!outIps.contains(r.address)) {
                        outIps.add(r.address);
                    }
                    if (RailType.USB_NIC.name().equals(r.type) && outUsbType != null && outUsbType[0] == null) {
                        outUsbType[0] = r.name;
                    }
                }
            }
        }
        if (outIps.isEmpty() && rails != null) {
            for (IpcDtos.RailStatusInfo r : rails) {
                if (r.address != null && !r.address.isEmpty() && !outIps.contains(r.address)) {
                    outIps.add(r.address);
                }
            }
        }
    }

    public IpcPayloads.NicsRefreshResult refreshNics() throws ControllerException {
        try {
            UsbNicAdvisor.AdviceResult advice = usbNicAdvisor.advise();
            return applyAdvice(advice);
        } catch (SocketException e) {
            throw new ControllerException(JsonRpcError.IO_ERROR, "Failed to enumerate network interfaces: " + e.getMessage());
        }
    }

    /**
     * Pure-Java helper allowing unit tests to inject synthetic {@link UsbNicAdvisor.AdviceResult}.
     */
    public IpcPayloads.NicsRefreshResult applyAdvice(UsbNicAdvisor.AdviceResult advice) {
        this.lastAdvice = advice;
        IpcPayloads.NicsRefreshResult result = new IpcPayloads.NicsRefreshResult();
        if (advice != null) {
            result.nics = new ArrayList<>(advice.nics);
            result.udcSpeed = advice.udcSpeed;
            result.vpnActive = advice.vpnActive;
            result.conflictResolved = advice.conflictResolved;
            result.vpnWarning = advice.vpnWarning;
            if (advice.conflictResolved != null) {
                emitEvent(IpcEvents.RAIL_CONFLICT_RESOLVED, advice.conflictResolved);
            }
            if (advice.vpnWarning != null) {
                emitEvent(IpcEvents.DIAGNOSTIC_WARNING, advice.vpnWarning);
            }
        }
        return result;
    }

    public IpcPayloads.OkResult startServer(IpcPayloads.ServerStartParams params) throws ControllerException {
        if (closed.get()) {
            throw new ControllerException(JsonRpcError.INVALID_STATE, "Engine is shut down");
        }
        if (backend == null) {
            throw new ControllerException(JsonRpcError.INTERNAL_ERROR, "Server backend not configured");
        }
        final IpcPayloads.ServerStartParams p = params != null ? params : new IpcPayloads.ServerStartParams();
        final int port = p.port > 0 ? p.port : ProtocolConstants.DEFAULT_CONTROL_PORT;
        final List<ServerNetInterface> selectedInterfaces = resolveServerInterfaces(p);
        if (selectedInterfaces.isEmpty()) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS, "No network interfaces selected");
        }

        final long mySessionId;
        synchronized (lifecycleLock) {
            if (state != SessionState.IDLE && state != SessionState.ERROR) {
                throw new ControllerException(JsonRpcError.INVALID_STATE,
                        "Server already active in state " + state);
            }
            mySessionId = sessionSeq.incrementAndGet();
            this.lastTransferProgress = null;
            this.activeTransferDirection = null;
            this.remoteHomeDir = "/";
            this.remoteFileSystem = Directory.FILE_SYSTEM_UNIX;
            this.activeServerBoundLatch = new CountDownLatch(1);
            this.activeServerPort = port;
            this.railStatuses = buildInitialRailStatuses(selectedInterfaces, "STARTING");
            transitionState(SessionState.DISCOVERING, "Starting server on port " + port);
            emitRailStatus(this.railStatuses);
        }

        serverExecutor.submit(new Runnable() {
            @Override
            public void run() {
                runServerLifecycle(mySessionId, port, p, selectedInterfaces);
            }
        });

        return new IpcPayloads.OkResult(true, "Server starting on port " + port);
    }

    private void runServerLifecycle(final long mySessionId,
                                    final int port,
                                    final IpcPayloads.ServerStartParams p,
                                    final List<ServerNetInterface> selectedInterfaces) {
        AbstractHFXServer server = null;
        try {
            server = backend.acquireServer(p.ioMode);
            if (server == null) {
                throw new IOException("Backend returned null server");
            }
            synchronized (lifecycleLock) {
                if (sessionSeq.get() != mySessionId || closed.get()) {
                    backend.releaseServer(server);
                    return;
                }
                this.activeServer = server;
            }
            if (p.preferredBlockSize > 0) {
                server.setPreferredBlockSize(p.preferredBlockSize);
            }
            server.setSpeedMonitorIntervalMs(progressThrottleMs);
            trustedPcStore.setExplicitAuthRequired(p.authRequired);
            server.setAuthProvider(trustedPcStore);

            final AbstractHFXServer boundServer = server;
            final AtomicBoolean handshakeOk = new AtomicBoolean(false);
            final String udcSpeed = lastAdvice != null ? lastAdvice.udcSpeed : UsbNicAdvisor.SPEED_UNKNOWN;

            boundServer.startServer(
                    port,
                    selectedInterfaces,
                    Math.max(16, p.localBufferCount),
                    Math.max(16, p.remoteBufferCount),
                    new ServerHandshakeCallback() {
                        @Override
                        public void onBindFailed(int failedPort) {
                            CountDownLatch latch = activeServerBoundLatch;
                            if (latch != null) latch.countDown();
                            emitEvent(IpcEvents.DIAGNOSTIC_WARNING,
                                    new IpcPayloads.DiagnosticWarningPayload("BIND_FAILED",
                                            "Failed to bind server port " + failedPort));
                            transitionIfCurrent(mySessionId, SessionState.ERROR, "Bind failed on port " + failedPort);
                        }

                        @Override
                        public void onServerStarted() {
                            if (sessionSeq.get() != mySessionId) return;
                            int actualPort = boundServer.getBoundPort();
                            if (actualPort > 0) {
                                activeServerPort = actualPort;
                            }
                            updateAllRailStates("WAITING", udcSpeed);
                            List<String> mobileIps = new ArrayList<>();
                            String[] usbHolder = new String[1];
                            collectAdvertisableMobileIps(mobileIps, usbHolder);
                            mdnsBridge.startAdvertising(
                                    activeServerPort,
                                    deviceId,
                                    deviceName,
                                    mobileIps,
                                    usbHolder[0],
                                    activePairAck);
                            CountDownLatch latch = activeServerBoundLatch;
                            if (latch != null) latch.countDown();
                        }

                        @Override
                        public void onAuthFailed(String clientId, String reason) {
                            emitEvent(IpcEvents.DIAGNOSTIC_WARNING,
                                    new IpcPayloads.DiagnosticWarningPayload("AUTH_FAILED",
                                            "Rejected client " + clientId + ": " + reason));
                        }

                        @Override
                        public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {
                            if (sessionSeq.get() != mySessionId) return;
                            if (state == SessionState.DISCOVERING) {
                                transitionIfCurrent(mySessionId, SessionState.CONNECTING, "Client handshake in progress");
                            }
                            updateRailState(name, "CONNECTED", streamCount, udcSpeed, null);
                        }

                        @Override
                        public void onAcceptFailed(String name) {
                            if (sessionSeq.get() != mySessionId) return;
                            updateRailState(name, "FAILED", 1, udcSpeed, "Accept failed");
                        }

                        @Override
                        public void onRemoteOOM() {
                            emitEvent(IpcEvents.DIAGNOSTIC_WARNING,
                                    new IpcPayloads.DiagnosticWarningPayload("REMOTE_OOM",
                                            "Remote PC ran out of buffer memory"));
                            transitionIfCurrent(mySessionId, SessionState.ERROR, "Remote OOM");
                        }

                        @Override
                        public void onLocalOOM(int created, int localBufferCount) {
                            emitEvent(IpcEvents.DIAGNOSTIC_WARNING,
                                    new IpcPayloads.DiagnosticWarningPayload("LOCAL_OOM",
                                            "Local OOM allocating buffer " + created + "/" + localBufferCount));
                            transitionIfCurrent(mySessionId, SessionState.ERROR, "Local OOM");
                        }

                        @Override
                        public void onConnectSuccess() {
                            if (sessionSeq.get() != mySessionId) return;
                            handshakeOk.set(true);
                            remoteHomeDir = boundServer.getRemoteHomeDir() != null ? boundServer.getRemoteHomeDir() : "/";
                            remoteFileSystem = boundServer.getRemoteFileSystem();
                            List<IpcDtos.RailStatusInfo> connectedRails = boundServer.getRailStatusInfos("CONNECTED", udcSpeed);
                            railStatuses = connectedRails;
                            emitRailStatus(connectedRails);
                            transitionIfCurrent(mySessionId, SessionState.CONNECTED,
                                    "Connected to " + (boundServer.getConnectedClientId() != null
                                            ? boundServer.getConnectedClientId() : "peer"));
                        }
                    });

            if (handshakeOk.get() && sessionSeq.get() == mySessionId && !closed.get()) {
                boundServer.startControlLoop(createPeerControlCallback(mySessionId, boundServer));
            }
        } catch (Exception e) {
            if (sessionSeq.get() == mySessionId && !closed.get()) {
                emitEvent(IpcEvents.DIAGNOSTIC_WARNING,
                        new IpcPayloads.DiagnosticWarningPayload("SERVER_STOPPED",
                                e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        } finally {
            cleanupSessionIfCurrent(mySessionId, server);
        }
    }

    public IpcPayloads.OkResult stopServer() {
        AbstractHFXServer serverToStop;
        synchronized (lifecycleLock) {
            sessionSeq.incrementAndGet();
            serverToStop = this.activeServer;
            this.activeServer = null;
            this.railStatuses = Collections.emptyList();
            this.lastTransferProgress = null;
            this.activeTransferDirection = null;
        }
        if (serverToStop != null) {
            try {
                if (serverToStop.isSessionActive()) {
                    serverToStop.disconnect();
                } else {
                    serverToStop.closeServerSocket();
                    serverToStop.close();
                }
            } catch (Exception ignored) {
            }
            if (backend != null) {
                try {
                    backend.releaseServer(serverToStop);
                } catch (Exception ignored) {
                }
            }
        }
        activePairAck = null;
        mdnsBridge.stopAdvertising();
        emitRailStatus(Collections.emptyList());
        transitionState(SessionState.IDLE, "Server stopped");
        return new IpcPayloads.OkResult(true, "Stopped");
    }

    public IpcPayloads.FsListResult listFiles(IpcPayloads.FsListParams params) throws ControllerException {
        IpcPayloads.FsListParams p = params != null ? params : new IpcPayloads.FsListParams();
        String side = (p.side == null || p.side.trim().isEmpty()) ? "local" : p.side.trim().toLowerCase();
        String path = (p.path == null || p.path.trim().isEmpty()) ? "/" : p.path.trim();

        AbstractHFXServer server = requireConnectedServer();
        try {
            List<RemoteFile> files;
            if ("local".equals(side)) {
                files = backend.listLocalFiles(server, path);
            } else if ("remote".equals(side)) {
                files = server.listClientFiles(path);
            } else {
                throw new ControllerException(JsonRpcError.INVALID_PARAMS, "Unsupported side: " + side);
            }
            if (files == null) {
                throw new ControllerException(JsonRpcError.IO_ERROR, "Failed to list " + side + " path: " + path);
            }
            List<RemoteFile> sorted = new ArrayList<>(files);
            Utils.sortFiles(sorted);
            List<IpcDtos.FsEntryDto> entries = new ArrayList<>(sorted.size());
            for (RemoteFile rf : sorted) {
                entries.add(new IpcDtos.FsEntryDto(
                        rf.getName(), rf.getPath(), rf.lastModified(), rf.getSize(), rf.isDirectory()));
            }
            return new IpcPayloads.FsListResult(side, path, entries);
        } catch (ControllerException e) {
            throw e;
        } catch (Exception e) {
            throw new ControllerException(JsonRpcError.IO_ERROR,
                    "fs.list (" + side + ") failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    public IpcPayloads.OkResult mkdir(IpcPayloads.FsMkdirParams params) throws ControllerException {
        if (params == null || params.parent == null || params.child == null || params.child.trim().isEmpty()) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS, "parent and child are required");
        }
        String side = (params.side == null || params.side.trim().isEmpty())
                ? "local" : params.side.trim().toLowerCase();
        AbstractHFXServer server = requireConnectedServer();
        try {
            boolean ok;
            if ("local".equals(side)) {
                ok = backend.createLocalDir(server, params.parent, params.child);
            } else if ("remote".equals(side)) {
                ok = server.createRemoteDir(params.parent, params.child);
            } else {
                throw new ControllerException(JsonRpcError.INVALID_PARAMS, "Unsupported side: " + side);
            }
            return new IpcPayloads.OkResult(ok, ok ? "Created" : "Failed to create directory");
        } catch (ControllerException e) {
            throw e;
        } catch (Exception e) {
            throw new ControllerException(JsonRpcError.IO_ERROR,
                    "fs.mkdir (" + side + ") failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    public IpcPayloads.OkResult deleteFile(IpcPayloads.FsDeleteParams params) throws ControllerException {
        if (params == null || params.path == null || params.path.trim().isEmpty()) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS, "path is required");
        }
        String side = (params.side == null || params.side.trim().isEmpty())
                ? "local" : params.side.trim().toLowerCase();
        AbstractHFXServer server = requireConnectedServer();
        try {
            boolean ok;
            if ("local".equals(side)) {
                ok = backend.deleteLocalFile(server, params.path);
            } else if ("remote".equals(side)) {
                ok = server.deleteRemoteFile(params.path);
            } else {
                throw new ControllerException(JsonRpcError.INVALID_PARAMS, "Unsupported side: " + side);
            }
            return new IpcPayloads.OkResult(ok, params.path);
        } catch (ControllerException e) {
            throw e;
        } catch (Exception e) {
            throw new ControllerException(JsonRpcError.IO_ERROR,
                    "fs.delete (" + side + ") failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    public IpcPayloads.OkResult startTransfer(IpcPayloads.TransferStartParams params) throws ControllerException {
        if (params == null || params.direction == null || params.files == null || params.files.isEmpty()
                || params.srcDir == null || params.dstDir == null) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS,
                    "direction, files, srcDir, and dstDir are required");
        }
        final String direction = params.direction.trim().toLowerCase();
        if (!"upload".equals(direction) && !"download".equals(direction)) {
            throw new ControllerException(JsonRpcError.INVALID_PARAMS,
                    "direction must be 'upload' (Android->PC) or 'download' (PC->Android)");
        }

        final AbstractHFXServer server;
        final long mySessionId;
        synchronized (lifecycleLock) {
            if (state != SessionState.CONNECTED || activeServer == null || !activeServer.isSessionActive()) {
                throw new ControllerException(JsonRpcError.INVALID_STATE,
                        "Cannot start transfer in state " + state);
            }
            server = activeServer;
            mySessionId = sessionSeq.get();
            this.activeTransferDirection = direction;
            this.lastTransferProgress = null;
            transitionState(SessionState.TRANSFERRING, "Transferring (" + direction + ")");
        }

        final List<String> filePaths = new ArrayList<>(params.files);
        final String srcDirPath = params.srcDir;
        final String dstDirPath = params.dstDir;
        final int remoteFs = params.remoteFileSystem >= 0 ? params.remoteFileSystem : this.remoteFileSystem;

        transferExecutor.submit(new Runnable() {
            @Override
            public void run() {
                runTransferWorker(mySessionId, server, direction, filePaths, srcDirPath, dstDirPath, remoteFs);
            }
        });

        return new IpcPayloads.OkResult(true, "Transfer started (" + direction + ")");
    }

    public IpcPayloads.OkResult cancelTransfer() {
        AbstractHFXServer server = this.activeServer;
        if (server == null || state != SessionState.TRANSFERRING) {
            return new IpcPayloads.OkResult(false, "No active transfer");
        }
        boolean signalled = server.cancelActiveTransfer();
        return new IpcPayloads.OkResult(signalled, signalled ? "Cancellation requested" : "No active transfer");
    }

    public IpcPayloads.OkResult shutdown() {
        if (closed.compareAndSet(false, true)) {
            cancelPairing();
            stopServer();
            mdnsBridge.stopAdvertising();
            serverExecutor.shutdownNow();
            transferExecutor.shutdownNow();
            pairExecutor.shutdownNow();
        }
        return new IpcPayloads.OkResult(true, "Engine shut down");
    }

    @Override
    public void close() {
        shutdown();
    }

    // ---- Internal worker & callback helpers ----

    private void runTransferWorker(long mySessionId,
                                   AbstractHFXServer server,
                                   String direction,
                                   List<String> filePaths,
                                   String srcDirPath,
                                   String dstDirPath,
                                   int remoteFs) {
        boolean isUpload = "upload".equals(direction);
        ThrottledTransferCallback cb = new ThrottledTransferCallback(server, direction, isUpload);
        try {
            if (isUpload) {
                List<RemoteFile> localFiles = new ArrayList<>(filePaths.size());
                for (String fp : filePaths) {
                    localFiles.add(backend.resolveLocalFile(server, fp));
                }
                Directory localDir = new Directory(srcDirPath, Directory.getCurrentFileSystem());
                Directory remoteDir = new Directory(dstDirPath, remoteFs);
                server.sendFilesToRemote(localFiles, localDir, remoteDir, cb);
            } else {
                List<RemoteFile> remoteFiles = new ArrayList<>(filePaths.size());
                for (String fp : filePaths) {
                    remoteFiles.add(new RemoteFile(extractBaseName(fp), fp, 0L, 0L, false));
                }
                Directory localDir = new Directory(dstDirPath, Directory.getCurrentFileSystem());
                Directory remoteDir = new Directory(srcDirPath, remoteFs);
                server.sendFilesToShelf(remoteFiles, localDir, remoteDir, cb);
            }
            this.activeTransferDirection = null;
            this.lastTransferProgress = null;
            if (state == SessionState.TRANSFERRING && sessionSeq.get() == mySessionId) {
                transitionIfCurrent(mySessionId, SessionState.CONNECTED, "Transfer finished");
            }
        } catch (Exception e) {
            this.activeTransferDirection = null;
            this.lastTransferProgress = null;
            if (!cb.terminalEmitted.getAndSet(true)) {
                IpcPayloads.TransferCompletedPayload err = new IpcPayloads.TransferCompletedPayload();
                err.direction = direction;
                err.elapsedMs = Math.max(1L, System.currentTimeMillis() - cb.startTimeMs);
                err.error = e.getMessage() != null ? e.getMessage() : e.toString();
                emitEvent(IpcEvents.TRANSFER_ERROR, err);
            }
            if (sessionSeq.get() == mySessionId) {
                stopServer();
            }
        }
    }

    private ClientCallBack createPeerControlCallback(final long mySessionId, final AbstractHFXServer server) {
        final AtomicReference<ThrottledTransferCallback> activeCb = new AtomicReference<>(null);
        return new ClientCallBack() {
            @Override
            public void onReceiving() {
                activeTransferDirection = "download";
                activeCb.set(new ThrottledTransferCallback(server, "download", false));
                transitionIfCurrent(mySessionId, SessionState.TRANSFERRING, "Receiving files from PC");
            }

            @Override
            public void onSending() {
                activeTransferDirection = "upload";
                activeCb.set(new ThrottledTransferCallback(server, "upload", true));
                transitionIfCurrent(mySessionId, SessionState.TRANSFERRING, "Sending files to PC");
            }

            @Override
            public void onExit() {
                cleanupSessionIfCurrent(mySessionId, server);
            }

            @Override
            public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onFileUploading(iName, path, targetSize, totalSize);
            }

            @Override
            public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onFileDownloading(iName, path, targetSize, totalSize);
            }

            @Override
            public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onSpeedInfo(trafficInfoList);
            }

            @Override
            public void onChannelComplete(String iName, long traffic, long time) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onChannelComplete(iName, traffic, time);
            }

            @Override
            public void onChannelError(String iName, int errorType, String message) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onChannelError(iName, errorType, message);
            }

            @Override
            public void onReadFileError(String message) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onReadFileError(message);
                activeTransferDirection = null;
                lastTransferProgress = null;
                if (state == SessionState.TRANSFERRING) {
                    transitionIfCurrent(mySessionId, SessionState.CONNECTED, "Transfer read error");
                }
            }

            @Override
            public void onWriteFileError(String message) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onWriteFileError(message);
                activeTransferDirection = null;
                lastTransferProgress = null;
                if (state == SessionState.TRANSFERRING) {
                    transitionIfCurrent(mySessionId, SessionState.CONNECTED, "Transfer write error");
                }
            }

            @Override
            public void onComplete(boolean isUpload, long traffic, long time) {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onComplete(isUpload, traffic, time);
                activeTransferDirection = null;
                lastTransferProgress = null;
                if (state == SessionState.TRANSFERRING) {
                    transitionIfCurrent(mySessionId, SessionState.CONNECTED, "Transfer completed");
                }
            }

            @Override
            public void onIncomplete() {
                ThrottledTransferCallback cb = activeCb.get();
                if (cb != null) cb.onIncomplete();
                activeTransferDirection = null;
                lastTransferProgress = null;
            }
        };
    }

    private final class ThrottledTransferCallback implements TransferFileCallback {
        final AbstractHFXServer server;
        final String direction;
        final boolean isUpload;
        final long startTimeMs = System.currentTimeMillis();
        final AtomicLong lastEmitNs = new AtomicLong(0L);
        final AtomicBoolean terminalEmitted = new AtomicBoolean(false);
        final Map<String, Long> perRailBps = new LinkedHashMap<>();
        final Map<String, String> perRailFile = new LinkedHashMap<>();
        volatile String currentFile = "";
        volatile long knownTotalBytes = 0L;
        volatile long aggregateBps = 0L;

        ThrottledTransferCallback(AbstractHFXServer server, String direction, boolean isUpload) {
            this.server = server;
            this.direction = direction;
            this.isUpload = isUpload;
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
            updateFile(iName, path, targetSize, totalSize);
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
            updateFile(iName, path, targetSize, totalSize);
        }

        private void updateFile(String iName, String path, long targetSize, long totalSize) {
            synchronized (this) {
                if (path != null) {
                    currentFile = path;
                    if (iName != null) {
                        perRailFile.put(iName, path);
                    }
                }
                if (totalSize > knownTotalBytes) {
                    knownTotalBytes = totalSize;
                }
            }
            maybeEmit(false, targetSize);
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
            synchronized (this) {
                perRailBps.clear();
                long total = 0L;
                if (trafficInfoList != null) {
                    for (TrafficInfo ti : trafficInfoList) {
                        long bps = isUpload ? ti.uploadTraffic : ti.downloadTraffic;
                        perRailBps.put(ti.iName, bps);
                        total += bps;
                    }
                }
                aggregateBps = total;
            }
            maybeEmit(true, 0L);
        }

        private void maybeEmit(boolean fromSpeedMonitor, long fallbackBytes) {
            long nowNs = System.nanoTime();
            long prev = lastEmitNs.get();
            if (prev != 0L && (nowNs - prev) < progressThrottleMs * 1_000_000L) {
                return;
            }
            if (!lastEmitNs.compareAndSet(prev, nowNs)) {
                return;
            }
            IpcPayloads.TransferProgressPayload p = new IpcPayloads.TransferProgressPayload();
            p.direction = direction;
            p.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
            long transferred = Math.max(server.getCurrentTransferredBytes(isUpload), fallbackBytes);
            synchronized (this) {
                p.totalBytes = Math.max(knownTotalBytes, transferred);
                p.transferredBytes = transferred;
                p.aggregateBps = aggregateBps > 0L
                        ? aggregateBps
                        : (fromSpeedMonitor ? 0L : (transferred * 1000L) / p.elapsedMs);
                p.perRailBps = new LinkedHashMap<>(perRailBps);
                p.perRailCurrentFile = new LinkedHashMap<>(perRailFile);
                p.currentFile = currentFile;
            }
            lastTransferProgress = p;
            emitEvent(IpcEvents.TRANSFER_PROGRESS, p);
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
        }

        @Override
        public void onReadFileError(String message) {
            if (terminalEmitted.compareAndSet(false, true)) {
                IpcPayloads.TransferCompletedPayload p = new IpcPayloads.TransferCompletedPayload();
                p.direction = direction;
                p.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
                p.error = "Read error: " + message;
                emitEvent(IpcEvents.TRANSFER_ERROR, p);
            }
        }

        @Override
        public void onWriteFileError(String message) {
            if (terminalEmitted.compareAndSet(false, true)) {
                IpcPayloads.TransferCompletedPayload p = new IpcPayloads.TransferCompletedPayload();
                p.direction = direction;
                p.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
                p.error = "Write error: " + message;
                emitEvent(IpcEvents.TRANSFER_ERROR, p);
            }
        }

        @Override
        public void onComplete(boolean upload, long traffic, long time) {
            if (terminalEmitted.compareAndSet(false, true)) {
                long safeTime = Math.max(1L, time);
                IpcPayloads.TransferCompletedPayload p = new IpcPayloads.TransferCompletedPayload();
                p.direction = upload ? "upload" : "download";
                p.totalBytes = traffic;
                p.elapsedMs = safeTime;
                p.avgBps = (traffic * 1000L) / safeTime;
                emitEvent(IpcEvents.TRANSFER_COMPLETED, p);
            }
        }

        @Override
        public void onIncomplete() {
            if (terminalEmitted.compareAndSet(false, true)) {
                IpcPayloads.TransferCompletedPayload p = new IpcPayloads.TransferCompletedPayload();
                p.direction = direction;
                p.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
                p.error = "Transfer interrupted or incomplete";
                emitEvent(IpcEvents.TRANSFER_ERROR, p);
            }
        }
    }

    private void cleanupSessionIfCurrent(long mySessionId, AbstractHFXServer server) {
        boolean shouldCleanup;
        synchronized (lifecycleLock) {
            shouldCleanup = (sessionSeq.get() == mySessionId);
            if (shouldCleanup) {
                this.activeServer = null;
                this.railStatuses = Collections.emptyList();
                this.activeTransferDirection = null;
                this.lastTransferProgress = null;
            }
        }
        if (shouldCleanup) {
            if (server != null) {
                try {
                    server.close();
                } catch (Exception ignored) {
                }
                if (backend != null) {
                    try {
                        backend.releaseServer(server);
                    } catch (Exception ignored) {
                    }
                }
            }
            mdnsBridge.stopAdvertising();
            emitRailStatus(Collections.emptyList());
            transitionState(SessionState.IDLE, "Session ended");
        }
    }

    private AbstractHFXServer requireConnectedServer() throws ControllerException {
        AbstractHFXServer srv = this.activeServer;
        if (srv == null || !srv.isSessionActive() || (state != SessionState.CONNECTED && state != SessionState.TRANSFERRING)) {
            throw new ControllerException(JsonRpcError.INVALID_STATE,
                    "Operation requires CONNECTED server (current: " + state + ")");
        }
        return srv;
    }

    private List<ServerNetInterface> resolveServerInterfaces(IpcPayloads.ServerStartParams p) throws ControllerException {
        List<ServerNetInterface> out = new ArrayList<>();
        if (p.selectedRails != null && !p.selectedRails.isEmpty()) {
            for (IpcDtos.RailSelection sel : p.selectedRails) {
                if (sel == null || sel.name == null || sel.ip == null || sel.ip.trim().isEmpty()) {
                    continue;
                }
                try {
                    InetAddress addr = InetAddress.getByName(sel.ip.trim());
                    InetAddress bindAddr = (sel.bindIp != null && !sel.bindIp.trim().isEmpty())
                            ? InetAddress.getByName(sel.bindIp.trim()) : null;
                    RailType rt = parseRailType(sel.railType, sel.name);
                    int streams = ProtocolConstants.clampStreamCount(sel.streamCount > 0 ? sel.streamCount : 1);
                    out.add(new ServerNetInterface(sel.name, addr, bindAddr, rt, streams));
                } catch (Exception e) {
                    throw new ControllerException(JsonRpcError.INVALID_PARAMS,
                            "Invalid IP in selectedRails (" + sel.name + "): " + e.getMessage());
                }
            }
            return out;
        }
        try {
            UsbNicAdvisor.AdviceResult advice = this.lastAdvice != null ? this.lastAdvice : usbNicAdvisor.advise();
            this.lastAdvice = advice;
            return UsbNicAdvisor.toServerNetInterfaces(advice.nics);
        } catch (Exception e) {
            throw new ControllerException(JsonRpcError.IO_ERROR, "Failed to resolve default rails: " + e.getMessage());
        }
    }

    private List<IpcDtos.RailStatusInfo> buildInitialRailStatuses(List<ServerNetInterface> interfaces, String railState) {
        String udcSpeed = lastAdvice != null ? lastAdvice.udcSpeed : UsbNicAdvisor.SPEED_UNKNOWN;
        List<IpcDtos.RailStatusInfo> list = new ArrayList<>(interfaces.size());
        for (int i = 0; i < interfaces.size(); i++) {
            ServerNetInterface sni = interfaces.get(i);
            IpcDtos.RailStatusInfo info = new IpcDtos.RailStatusInfo();
            info.railId = i;
            info.name = sni.name;
            info.type = sni.railType != null ? sni.railType.name() : "OTHER";
            info.address = sni.address != null ? sni.address.getHostAddress() : null;
            info.bindAddress = sni.clientBindAddress != null ? sni.clientBindAddress.getHostAddress() : null;
            info.streamCount = sni.streamCount;
            info.state = railState;
            info.udcSpeed = udcSpeed;
            list.add(info);
        }
        return list;
    }

    private void updateAllRailStates(String newState, String udcSpeed) {
        List<IpcDtos.RailStatusInfo> current = new ArrayList<>(this.railStatuses);
        for (IpcDtos.RailStatusInfo info : current) {
            info.state = newState;
            info.udcSpeed = udcSpeed;
        }
        this.railStatuses = current;
        emitRailStatus(current);
    }

    private void updateRailState(String name, String newState, int streamCount, String udcSpeed, String error) {
        List<IpcDtos.RailStatusInfo> current = new ArrayList<>(this.railStatuses);
        for (IpcDtos.RailStatusInfo info : current) {
            if (name != null && name.equals(info.name)) {
                info.state = newState;
                info.streamCount = Math.max(1, streamCount);
                info.udcSpeed = udcSpeed;
                info.lastError = error;
            }
        }
        this.railStatuses = current;
        emitRailStatus(current);
    }

    private void transitionIfCurrent(long mySessionId, SessionState next, String detail) {
        synchronized (lifecycleLock) {
            if (sessionSeq.get() != mySessionId) {
                return;
            }
            transitionState(next, detail);
        }
    }

    private void transitionState(SessionState next, String detail) {
        SessionState prev;
        synchronized (lifecycleLock) {
            prev = this.state;
            if (prev == next) {
                return;
            }
            this.state = next;
        }
        emitEvent(IpcEvents.SESSION_STATE_CHANGED,
                new IpcPayloads.SessionStateChangedPayload(prev, next, detail));
    }

    private void emitRailStatus(List<IpcDtos.RailStatusInfo> rails) {
        IpcPayloads.RailStatusPayload payload = new IpcPayloads.RailStatusPayload();
        if (rails != null) {
            payload.rails.addAll(rails);
        }
        emitEvent(IpcEvents.RAIL_STATUS, payload);
    }

    private void emitEvent(String eventName, Object payload) {
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(eventName, payload);
            } catch (Exception ignored) {
            }
        }
    }

    private static JsonRpcResponse ok(JsonElement id, Object result) {
        return JsonRpcResponse.success(id, IpcJson.toJsonTree(result));
    }

    private static RailType parseRailType(String railTypeName, String ifName) {
        if (railTypeName != null && !railTypeName.isEmpty()) {
            try {
                return RailType.valueOf(railTypeName);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (ProtocolConstants.USB_ADB_RAIL_NAME.equalsIgnoreCase(ifName)) {
            return RailType.ADB;
        }
        return RailType.inferFromInterfaceName(ifName);
    }

    private static String extractBaseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    public static final class ControllerException extends Exception {
        public final int code;

        public ControllerException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }
    }
}
