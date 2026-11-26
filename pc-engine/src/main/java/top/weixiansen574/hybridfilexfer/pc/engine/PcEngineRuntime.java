package top.weixiansen574.hybridfilexfer.pc.engine;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcError;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;
import top.weixiansen574.hybridfilexfer.pc.engine.adb.AdbManager;
import top.weixiansen574.hybridfilexfer.pc.engine.discovery.NicSelector;
import top.weixiansen574.hybridfilexfer.pc.engine.transport.SubnetBindResolver;

/**
 * Headless PC engine runtime implementing the full M3 state machine and orchestrating
 * {@link AdbManager}, {@link NicSelector}, {@link SubnetBindResolver}, and {@link JdkHFXClient}.
 *
 * <p>Architecture red line: strictly zero UI / Swing / AWT / Android / {@code PrintStream} references.
 * All state transitions, diagnostics, rail updates, and 250ms-throttled transfer progress are emitted
 * solely as structured IPC events to registered {@link EventListener}s.</p>
 */
public class PcEngineRuntime implements AutoCloseable {

    public static final long DEFAULT_PROGRESS_THROTTLE_MS = 250L;

    /**
     * Structured runtime error carrying a JSON-RPC error code.
     */
    public static final class EngineException extends Exception {
        public final int errorCode;

        public EngineException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public EngineException(int errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }
    }

    /**
     * Event subscriber interface for IPC transports and thin shells.
     */
    @FunctionalInterface
    public interface EventListener {
        void onEvent(String eventName, Object payload);
    }

    /**
     * Injectable factory for {@link AdbManager} to support deterministic unit testing.
     */
    @FunctionalInterface
    public interface AdbManagerFactory {
        AdbManager create(AdbManager.AdbConfig config, AdbManager.DiagnosticListener listener);
    }

    /**
     * Tunable runtime configuration preserving defaults while keeping knobs user-configurable.
     */
    public static final class EngineConfig {
        public String deviceId;
        public String deviceName;
        public long progressThrottleMs = DEFAULT_PROGRESS_THROTTLE_MS;
        public boolean defaultAutoSubnetBind = true;
        public AdbManager.AdbConfig adbConfig = new AdbManager.AdbConfig();
        public NicSelector.NicSelectorConfig nicSelectorConfig = new NicSelector.NicSelectorConfig();

        public EngineConfig() {
        }

        public EngineConfig copy() {
            EngineConfig c = new EngineConfig();
            c.deviceId = this.deviceId;
            c.deviceName = this.deviceName;
            c.progressThrottleMs = Math.max(100L, this.progressThrottleMs);
            c.defaultAutoSubnetBind = this.defaultAutoSubnetBind;
            c.adbConfig = this.adbConfig != null ? this.adbConfig.copy() : new AdbManager.AdbConfig();
            c.nicSelectorConfig = this.nicSelectorConfig != null
                    ? this.nicSelectorConfig.copy()
                    : new NicSelector.NicSelectorConfig();
            return c;
        }
    }

    private final EngineConfig config;
    private final String deviceId;
    private final String deviceName;
    private final NicSelector nicSelector;
    private final SubnetBindResolver subnetBindResolver;
    private final AdbManagerFactory adbManagerFactory;

    private final CopyOnWriteArrayList<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hfx-pc-transfer");
        t.setDaemon(true);
        return t;
    });

    private volatile SessionState state = SessionState.IDLE;
    private volatile List<IpcDtos.NicInfo> cachedNics = Collections.emptyList();
    private volatile List<IpcDtos.RailStatusInfo> activeRails = Collections.emptyList();
    private volatile IpcDtos.AdbCapabilityInfo lastAdbCapability;
    private volatile String effectiveHomeDir = "/";
    private volatile JdkHFXClient activeClient;
    private volatile AdbManager activeAdbManager;
    private volatile CountDownLatch sessionEndedLatch;
    private volatile Runnable shutdownHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PcEngineRuntime() {
        this(new EngineConfig(), null, null, null);
    }

    public PcEngineRuntime(EngineConfig config) {
        this(config, null, null, null);
    }

    public PcEngineRuntime(EngineConfig config,
                           NicSelector nicSelector,
                           SubnetBindResolver subnetBindResolver,
                           AdbManagerFactory adbManagerFactory) {
        this.config = config != null ? config.copy() : new EngineConfig();
        this.deviceId = (this.config.deviceId != null && !this.config.deviceId.trim().isEmpty())
                ? this.config.deviceId.trim()
                : UUID.randomUUID().toString();
        this.deviceName = resolveDeviceName(this.config.deviceName);
        this.nicSelector = nicSelector != null ? nicSelector : new NicSelector(this.config.nicSelectorConfig);
        this.subnetBindResolver = subnetBindResolver != null
                ? subnetBindResolver
                : new SubnetBindResolver(this.nicSelector);
        this.adbManagerFactory = adbManagerFactory != null
                ? adbManagerFactory
                : (adbCfg, diag) -> new AdbManager(adbCfg, diag);
        this.cachedNics = this.nicSelector.selectLocalNics();
    }

    private static String resolveDeviceName(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "PC";
        }
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

    public void setShutdownHandler(Runnable shutdownHandler) {
        this.shutdownHandler = shutdownHandler;
    }

    public SessionState getState() {
        return state;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public EngineConfig getConfig() {
        return config.copy();
    }

    public IpcDtos.EngineSnapshot getSnapshot() {
        IpcDtos.EngineSnapshot snapshot = new IpcDtos.EngineSnapshot();
        snapshot.engineRole = "pc";
        snapshot.protocolVersion = ProtocolConstants.VERSION_CODE;
        snapshot.engineVersion = ProtocolConstants.ENGINE_VERSION;
        snapshot.state = this.state;
        snapshot.deviceId = this.deviceId;
        snapshot.deviceName = this.deviceName;
        snapshot.adb = this.lastAdbCapability;
        snapshot.nics = new ArrayList<>(this.cachedNics);
        snapshot.rails = new ArrayList<>(this.activeRails);
        snapshot.remoteHomeDir = this.effectiveHomeDir;
        snapshot.remoteFileSystem = Directory.getCurrentFileSystem();
        return snapshot;
    }

    public IpcPayloads.NicsRefreshResult refreshNics() {
        List<IpcDtos.NicInfo> nics = nicSelector.selectLocalNics();
        this.cachedNics = nics;
        IpcPayloads.NicsRefreshResult res = new IpcPayloads.NicsRefreshResult();
        res.nics = new ArrayList<>(nics);
        return res;
    }

    public IpcPayloads.OkResult connect(IpcPayloads.SessionConnectParams params) throws EngineException {
        if (closed.get()) {
            throw new EngineException(JsonRpcError.INVALID_STATE, "Engine is shut down");
        }
        IpcPayloads.SessionConnectParams p = params != null ? params : new IpcPayloads.SessionConnectParams();
        String mode = normalizeConnectMode(p);
        int port = p.port > 0 ? p.port : ProtocolConstants.DEFAULT_CONTROL_PORT;

        synchronized (lifecycleLock) {
            if (state != SessionState.IDLE && state != SessionState.ERROR) {
                throw new EngineException(JsonRpcError.INVALID_STATE,
                        "Cannot connect while state is " + state);
            }
            transitionState(SessionState.CONNECTING, "Connecting (" + mode + ")");
        }

        AdbManager adbManager = null;
        JdkHFXClient client = null;
        try {
            String serverAddress;
            int requestedAdbStreams = p.adbStreams != null ? p.adbStreams : 0;

            if ("adb".equals(mode)) {
                AdbManager.AdbConfig adbCfg = mergeAdbConfig(p);
                adbManager = adbManagerFactory.create(adbCfg, this::emitEvent);
                adbManager.registerShutdownHook();

                AdbManager.ForwardResult forward = adbManager.forwardPort(port, port, p.serial);
                if (forward.capability != null) {
                    this.lastAdbCapability = forward.capability;
                }
                if (!forward.success) {
                    String errDetail = !forward.stderr.isEmpty() ? forward.stderr.trim() : "adb forward failed";
                    throw new EngineException(JsonRpcError.CONNECTION_FAILED, errDetail);
                }
                if (forward.capability != null && p.adbStreams == null) {
                    requestedAdbStreams = forward.capability.recommendedStreams;
                }
                serverAddress = "127.0.0.1";
            } else {
                if (p.host == null || p.host.trim().isEmpty()) {
                    throw new EngineException(JsonRpcError.INVALID_PARAMS,
                            "host is required when mode is " + mode);
                }
                serverAddress = p.host.trim();
            }

            String resolvedHomeDir = resolveEffectiveHomeDir(p.homeDir);
            this.effectiveHomeDir = resolvedHomeDir;

            client = new JdkHFXClient(serverAddress, port, resolvedHomeDir, subnetBindResolver);
            client.setClientId(this.deviceId);
            if (requestedAdbStreams > 0) {
                client.setRequestedAdbStreams(requestedAdbStreams);
            }
            client.setAutoSubnetBindEnabled(p.autoSubnetBind && config.defaultAutoSubnetBind);
            client.setSpeedMonitorIntervalMs(config.progressThrottleMs);

            final AtomicReference<String> connectError = new AtomicReference<>(null);
            final List<IpcDtos.RailStatusInfo> connectingRails = Collections.synchronizedList(new ArrayList<>());

            boolean ok = client.connect(new ConnectServerCallback() {
                @Override
                public void onConnectingControlChannel(String address, int targetPort) {
                }

                @Override
                public void onVersionMismatch(int localVersion, int remoteVersion) {
                    connectError.set("Protocol version mismatch: local=" + localVersion + ", remote=" + remoteVersion);
                }

                @Override
                public void onConnectControlFailed() {
                    connectError.compareAndSet(null, "Failed to connect control channel to "
                            + serverAddress + ":" + port);
                }

                @Override
                public void onAuthFailed(String reason) {
                    connectError.set("Authentication failed: " + reason);
                }

                @Override
                public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
                    IpcDtos.RailStatusInfo info = new IpcDtos.RailStatusInfo();
                    info.railId = connectingRails.size();
                    info.name = name;
                    info.address = inetAddress != null ? inetAddress.getHostAddress() : null;
                    info.bindAddress = bindAddress != null ? bindAddress.getHostAddress() : null;
                    info.state = "CONNECTING";
                    connectingRails.add(info);
                    emitRailStatus(new ArrayList<>(connectingRails));
                }

                @Override
                public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
                    connectError.set("Data channel failed for " + name + " ("
                            + (inetAddress != null ? inetAddress.getHostAddress() : "unknown") + "): "
                            + (e != null ? e.getMessage() : "error"));
                }

                @Override
                public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
                    connectError.set("Local OOM allocating transfer buffers: "
                            + createdBuffers + "/" + requiredBuffers + " (maxHeap=" + maxMemoryMB + "MB)");
                }

                @Override
                public void onRemoteOOM() {
                    connectError.set("Remote server OOM allocating transfer buffers");
                }

                @Override
                public void onConnectSuccess(List<String> channelNames) {
                }
            });

            if (!ok) {
                String msg = connectError.get() != null ? connectError.get() : "Connection failed";
                throw new EngineException(JsonRpcError.CONNECTION_FAILED, msg);
            }

            final JdkHFXClient establishedClient = client;
            final AdbManager establishedAdb = adbManager;
            final CountDownLatch loopReady = new CountDownLatch(1);
            final CountDownLatch endedLatch = new CountDownLatch(1);

            synchronized (lifecycleLock) {
                this.activeClient = establishedClient;
                this.activeAdbManager = establishedAdb;
                this.sessionEndedLatch = endedLatch;
                List<IpcDtos.RailStatusInfo> connectedRails = establishedClient.getRailStatusInfos("CONNECTED", null);
                this.activeRails = connectedRails;
                transitionState(SessionState.CONNECTED, "Connected to " + serverAddress + ":" + port);
                emitRailStatus(connectedRails);
            }

            Thread loopThread = new Thread(() -> {
                try {
                    establishedClient.start(createPeerEventCallback(establishedClient), loopReady::countDown);
                } catch (Exception e) {
                    if (activeClient == establishedClient && !closed.get()) {
                        emitEvent(IpcEvents.DIAGNOSTIC_WARNING,
                                new IpcPayloads.DiagnosticWarningPayload("CONTROL_LOOP_ERROR", e.toString()));
                    }
                } finally {
                    loopReady.countDown();
                    onSessionTerminated(establishedClient, establishedAdb);
                    endedLatch.countDown();
                }
            }, "hfx-pc-control-loop");
            loopThread.setDaemon(true);
            loopThread.start();

            try {
                loopReady.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return new IpcPayloads.OkResult(true, "Connected to " + serverAddress + ":" + port);
        } catch (EngineException e) {
            cleanupFailedConnect(client, adbManager, e.getMessage());
            throw e;
        } catch (Exception e) {
            cleanupFailedConnect(client, adbManager, e.getMessage());
            throw new EngineException(JsonRpcError.CONNECTION_FAILED,
                    e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    public IpcPayloads.OkResult disconnect() {
        JdkHFXClient clientToClose;
        AdbManager adbToClose;
        synchronized (lifecycleLock) {
            clientToClose = this.activeClient;
            adbToClose = this.activeAdbManager;
            this.activeClient = null;
            this.activeAdbManager = null;
            this.activeRails = Collections.emptyList();
        }
        if (clientToClose != null) {
            clientToClose.disconnectGracefully();
        }
        if (adbToClose != null) {
            adbToClose.close();
        }
        emitRailStatus(Collections.emptyList());
        transitionState(SessionState.IDLE, "Disconnected");
        return new IpcPayloads.OkResult(true, "Disconnected");
    }

    public IpcPayloads.FsListResult listFiles(IpcPayloads.FsListParams params) throws EngineException {
        IpcPayloads.FsListParams p = params != null ? params : new IpcPayloads.FsListParams();
        String side = (p.side == null || p.side.trim().isEmpty()) ? "local" : p.side.trim().toLowerCase();
        String path = (p.path == null || p.path.trim().isEmpty()) ? "/" : p.path.trim();

        if ("local".equals(side)) {
            List<RemoteFile> files = listLocalFilesWithRoots(path);
            if (files == null) {
                throw new EngineException(JsonRpcError.IO_ERROR, "Failed to list local path: " + path);
            }
            return new IpcPayloads.FsListResult("local", path, toFsEntries(files));
        } else if ("remote".equals(side)) {
            JdkHFXClient client = requireConnectedClient();
            try {
                List<RemoteFile> files = client.listRemoteFiles(path);
                if (files == null) {
                    throw new EngineException(JsonRpcError.IO_ERROR, "Failed to list remote path: " + path);
                }
                return new IpcPayloads.FsListResult("remote", path, toFsEntries(files));
            } catch (IOException e) {
                throw new EngineException(JsonRpcError.IO_ERROR, "Remote fs.list failed: " + e.getMessage(), e);
            }
        } else {
            throw new EngineException(JsonRpcError.INVALID_PARAMS, "Unsupported side: " + side);
        }
    }

    public IpcPayloads.OkResult mkdir(IpcPayloads.FsMkdirParams params) throws EngineException {
        if (params == null || params.parent == null || params.child == null || params.child.trim().isEmpty()) {
            throw new EngineException(JsonRpcError.INVALID_PARAMS, "parent and child are required");
        }
        String side = (params.side == null || params.side.trim().isEmpty())
                ? "local" : params.side.trim().toLowerCase();
        if ("local".equals(side)) {
            File target = new File(params.parent, params.child);
            boolean ok = target.isDirectory() || target.mkdirs();
            return new IpcPayloads.OkResult(ok, target.getAbsolutePath());
        } else if ("remote".equals(side)) {
            JdkHFXClient client = requireConnectedClient();
            try {
                boolean ok = client.createRemoteDir(params.parent, params.child);
                return new IpcPayloads.OkResult(ok, ok ? "Directory created" : "Failed to create remote directory");
            } catch (IOException e) {
                throw new EngineException(JsonRpcError.IO_ERROR, "Remote fs.mkdir failed: " + e.getMessage(), e);
            }
        } else {
            throw new EngineException(JsonRpcError.INVALID_PARAMS, "Unsupported side: " + side);
        }
    }

    public IpcPayloads.OkResult deleteFile(IpcPayloads.FsDeleteParams params) throws EngineException {
        if (params == null || params.path == null || params.path.trim().isEmpty()) {
            throw new EngineException(JsonRpcError.INVALID_PARAMS, "path is required");
        }
        String side = (params.side == null || params.side.trim().isEmpty())
                ? "local" : params.side.trim().toLowerCase();
        if ("local".equals(side)) {
            boolean ok = deleteRecursive(new File(params.path));
            return new IpcPayloads.OkResult(ok, params.path);
        } else if ("remote".equals(side)) {
            JdkHFXClient client = requireConnectedClient();
            try {
                boolean ok = client.deleteRemoteFile(params.path);
                return new IpcPayloads.OkResult(ok, params.path);
            } catch (IOException e) {
                throw new EngineException(JsonRpcError.IO_ERROR, "Remote fs.delete failed: " + e.getMessage(), e);
            }
        } else {
            throw new EngineException(JsonRpcError.INVALID_PARAMS, "Unsupported side: " + side);
        }
    }

    public IpcPayloads.OkResult startTransfer(IpcPayloads.TransferStartParams params) throws EngineException {
        if (params == null || params.direction == null || params.files == null || params.files.isEmpty()
                || params.srcDir == null || params.dstDir == null) {
            throw new EngineException(JsonRpcError.INVALID_PARAMS,
                    "direction, files, srcDir, and dstDir are required");
        }
        final String direction = params.direction.trim().toLowerCase();
        if (!"upload".equals(direction) && !"download".equals(direction)) {
            throw new EngineException(JsonRpcError.INVALID_PARAMS,
                    "direction must be 'upload' or 'download'");
        }

        final JdkHFXClient client;
        synchronized (lifecycleLock) {
            if (state != SessionState.CONNECTED || activeClient == null || !activeClient.isSessionActive()) {
                throw new EngineException(JsonRpcError.INVALID_STATE,
                        "Cannot start transfer while state is " + state);
            }
            client = activeClient;
            transitionState(SessionState.TRANSFERRING, "Transferring (" + direction + ")");
        }

        final List<String> filePaths = new ArrayList<>(params.files);
        final String srcDirPath = params.srcDir;
        final String dstDirPath = params.dstDir;
        final int requestedRemoteFs = params.remoteFileSystem;

        transferExecutor.submit(() -> runInitiatedTransfer(
                client, direction, filePaths, srcDirPath, dstDirPath, requestedRemoteFs));

        return new IpcPayloads.OkResult(true, "Transfer started (" + direction + ")");
    }

    public IpcPayloads.OkResult cancelTransfer() {
        JdkHFXClient client = this.activeClient;
        if (client == null || state != SessionState.TRANSFERRING) {
            return new IpcPayloads.OkResult(false, "No active transfer");
        }
        boolean signalled = client.cancelActiveTransfer();
        return new IpcPayloads.OkResult(signalled, signalled ? "Cancellation requested" : "No active transfer");
    }

    public IpcPayloads.OkResult shutdown() {
        if (closed.compareAndSet(false, true)) {
            disconnect();
            transferExecutor.shutdownNow();
            Runnable handler = this.shutdownHandler;
            if (handler != null) {
                handler.run();
            }
        }
        return new IpcPayloads.OkResult(true, "Shutdown initiated");
    }

    /**
     * Blocks until the active client session finishes (used by thin CLI shells).
     */
    public void awaitSessionEnd() throws InterruptedException {
        CountDownLatch latch = this.sessionEndedLatch;
        if (latch != null) {
            latch.await();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    // ---- Internal orchestration helpers ----

    private void runInitiatedTransfer(JdkHFXClient client,
                                      String direction,
                                      List<String> filePaths,
                                      String srcDirPath,
                                      String dstDirPath,
                                      int requestedRemoteFs) {
        boolean isUpload = "upload".equals(direction);
        long precomputedTotalBytes = isUpload ? computeLocalTotalBytes(filePaths) : 0L;
        ThrottledTransferCallback cb = new ThrottledTransferCallback(
                client, direction, isUpload, precomputedTotalBytes);

        try {
            boolean keepAlive;
            if (isUpload) {
                List<RemoteFile> localFiles = new ArrayList<>(filePaths.size());
                for (String fp : filePaths) {
                    localFiles.add(new RemoteFile(new File(fp)));
                }
                int remoteFs = requestedRemoteFs >= 0 ? requestedRemoteFs : HFXService.inferFileSystemFromPath(dstDirPath);
                Directory localDir = new Directory(srcDirPath, Directory.getCurrentFileSystem());
                Directory remoteDir = new Directory(dstDirPath, remoteFs);
                keepAlive = client.sendFilesToRemote(localFiles, localDir, remoteDir, cb);
            } else {
                List<RemoteFile> remoteFiles = new ArrayList<>(filePaths.size());
                for (String fp : filePaths) {
                    remoteFiles.add(new RemoteFile(extractBaseName(fp), fp, 0L, 0L, false));
                }
                int remoteFs = requestedRemoteFs >= 0 ? requestedRemoteFs : HFXService.inferFileSystemFromPath(srcDirPath);
                Directory localDestDir = new Directory(dstDirPath, Directory.getCurrentFileSystem());
                Directory remoteSrcDir = new Directory(srcDirPath, remoteFs);
                keepAlive = client.sendFilesToShelf(remoteFiles, localDestDir, remoteSrcDir, cb);
            }

            if (!keepAlive) {
                disconnect();
            } else if (state == SessionState.TRANSFERRING && activeClient == client) {
                transitionState(SessionState.CONNECTED, "Transfer finished");
            }
        } catch (Exception e) {
            if (!cb.terminalEventEmitted.getAndSet(true)) {
                IpcPayloads.TransferCompletedPayload err = new IpcPayloads.TransferCompletedPayload();
                err.direction = direction;
                err.elapsedMs = Math.max(1L, System.currentTimeMillis() - cb.startTimeMs);
                err.error = e.getMessage() != null ? e.getMessage() : e.toString();
                emitEvent(IpcEvents.TRANSFER_ERROR, err);
            }
            if (activeClient == client) {
                transitionState(SessionState.ERROR, errMessage(e));
            }
        }
    }

    private ClientCallBack createPeerEventCallback(JdkHFXClient client) {
        final AtomicReference<ThrottledTransferCallback> currentCb = new AtomicReference<>(null);
        return new ClientCallBack() {
            @Override
            public void onReceiving() {
                ThrottledTransferCallback cb = new ThrottledTransferCallback(client, "download", false, 0L);
                currentCb.set(cb);
                transitionState(SessionState.TRANSFERRING, "Receiving files from peer");
            }

            @Override
            public void onSending() {
                ThrottledTransferCallback cb = new ThrottledTransferCallback(client, "upload", true, 0L);
                currentCb.set(cb);
                transitionState(SessionState.TRANSFERRING, "Sending files to peer");
            }

            @Override
            public void onExit() {
                onSessionTerminated(client, activeAdbManager);
            }

            @Override
            public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onFileUploading(iName, path, targetSize, totalSize);
            }

            @Override
            public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onFileDownloading(iName, path, targetSize, totalSize);
            }

            @Override
            public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onSpeedInfo(trafficInfoList);
            }

            @Override
            public void onChannelComplete(String iName, long traffic, long time) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onChannelComplete(iName, traffic, time);
            }

            @Override
            public void onChannelError(String iName, int errorType, String message) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onChannelError(iName, errorType, message);
            }

            @Override
            public void onReadFileError(String message) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onReadFileError(message);
                if (state == SessionState.TRANSFERRING && activeClient == client) {
                    transitionState(SessionState.CONNECTED, "Transfer read error");
                }
            }

            @Override
            public void onWriteFileError(String message) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onWriteFileError(message);
                if (state == SessionState.TRANSFERRING && activeClient == client) {
                    transitionState(SessionState.CONNECTED, "Transfer write error");
                }
            }

            @Override
            public void onComplete(boolean isUpload, long traffic, long time) {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onComplete(isUpload, traffic, time);
                if (state == SessionState.TRANSFERRING && activeClient == client) {
                    transitionState(SessionState.CONNECTED, "Transfer completed");
                }
            }

            @Override
            public void onIncomplete() {
                ThrottledTransferCallback cb = currentCb.get();
                if (cb != null) cb.onIncomplete();
            }
        };
    }

    private final class ThrottledTransferCallback implements TransferFileCallback {
        final JdkHFXClient client;
        final String direction;
        final boolean isUpload;
        final long startTimeMs = System.currentTimeMillis();
        final AtomicLong lastEmitNs = new AtomicLong(0L);
        final AtomicBoolean terminalEventEmitted = new AtomicBoolean(false);
        final Map<String, Long> perRailBps = new LinkedHashMap<>();
        final Map<String, String> perRailFile = new LinkedHashMap<>();
        volatile long knownTotalBytes;
        volatile String currentFile = "";
        volatile long aggregateBps = 0L;

        ThrottledTransferCallback(JdkHFXClient client, String direction, boolean isUpload, long totalBytes) {
            this.client = client;
            this.direction = direction;
            this.isUpload = isUpload;
            this.knownTotalBytes = Math.max(0L, totalBytes);
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
            updateFileProgress(iName, path, targetSize, totalSize);
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
            updateFileProgress(iName, path, targetSize, totalSize);
        }

        private void updateFileProgress(String iName, String path, long targetSize, long totalSize) {
            synchronized (this) {
                if (path != null) {
                    this.currentFile = path;
                    if (iName != null) {
                        this.perRailFile.put(iName, path);
                    }
                }
                if (totalSize > this.knownTotalBytes) {
                    this.knownTotalBytes = totalSize;
                }
            }
            maybeEmitProgress(false, targetSize);
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
                this.aggregateBps = total;
            }
            maybeEmitProgress(true, 0L);
        }

        private void maybeEmitProgress(boolean fromSpeedMonitor, long fallbackTransferred) {
            long nowNs = System.nanoTime();
            long prevNs = lastEmitNs.get();
            long throttleNs = config.progressThrottleMs * 1_000_000L;
            if (prevNs != 0L && (nowNs - prevNs) < throttleNs) {
                return;
            }
            if (!lastEmitNs.compareAndSet(prevNs, nowNs)) {
                return;
            }
            IpcPayloads.TransferProgressPayload payload = new IpcPayloads.TransferProgressPayload();
            payload.direction = direction;
            payload.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
            long transferred = Math.max(client.getCurrentTransferredBytes(isUpload), fallbackTransferred);
            synchronized (this) {
                payload.totalBytes = Math.max(knownTotalBytes, transferred);
                payload.transferredBytes = transferred;
                payload.aggregateBps = aggregateBps > 0L
                        ? aggregateBps
                        : (fromSpeedMonitor ? 0L : (transferred * 1000L) / payload.elapsedMs);
                payload.perRailBps = new LinkedHashMap<>(perRailBps);
                payload.perRailCurrentFile = new LinkedHashMap<>(perRailFile);
                payload.currentFile = currentFile;
            }
            emitEvent(IpcEvents.TRANSFER_PROGRESS, payload);
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
        }

        @Override
        public void onReadFileError(String message) {
            if (terminalEventEmitted.compareAndSet(false, true)) {
                IpcPayloads.TransferCompletedPayload payload = new IpcPayloads.TransferCompletedPayload();
                payload.direction = direction;
                payload.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
                payload.error = "Read error: " + message;
                emitEvent(IpcEvents.TRANSFER_ERROR, payload);
            }
        }

        @Override
        public void onWriteFileError(String message) {
            if (terminalEventEmitted.compareAndSet(false, true)) {
                IpcPayloads.TransferCompletedPayload payload = new IpcPayloads.TransferCompletedPayload();
                payload.direction = direction;
                payload.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
                payload.error = "Write error: " + message;
                emitEvent(IpcEvents.TRANSFER_ERROR, payload);
            }
        }

        @Override
        public void onComplete(boolean upload, long traffic, long time) {
            if (terminalEventEmitted.compareAndSet(false, true)) {
                long safeTime = Math.max(1L, time);
                IpcPayloads.TransferCompletedPayload payload = new IpcPayloads.TransferCompletedPayload();
                payload.direction = upload ? "upload" : "download";
                payload.totalBytes = traffic;
                payload.elapsedMs = safeTime;
                payload.avgBps = (traffic * 1000L) / safeTime;
                emitEvent(IpcEvents.TRANSFER_COMPLETED, payload);
            }
        }

        @Override
        public void onIncomplete() {
            if (terminalEventEmitted.compareAndSet(false, true)) {
                IpcPayloads.TransferCompletedPayload payload = new IpcPayloads.TransferCompletedPayload();
                payload.direction = direction;
                payload.elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
                payload.error = "Transfer interrupted or incomplete";
                emitEvent(IpcEvents.TRANSFER_ERROR, payload);
            }
        }
    }

    private void onSessionTerminated(JdkHFXClient terminatedClient, AdbManager adbToClose) {
        boolean shouldReset;
        synchronized (lifecycleLock) {
            shouldReset = (this.activeClient == terminatedClient);
            if (shouldReset) {
                this.activeClient = null;
                this.activeAdbManager = null;
                this.activeRails = Collections.emptyList();
            }
        }
        if (shouldReset) {
            if (adbToClose != null) {
                adbToClose.close();
            }
            emitRailStatus(Collections.emptyList());
            transitionState(SessionState.IDLE, "Session closed");
        }
    }

    private void cleanupFailedConnect(JdkHFXClient client, AdbManager adbManager, String errorDetail) {
        if (client != null) {
            client.requestShutdown();
        }
        if (adbManager != null) {
            adbManager.close();
        }
        synchronized (lifecycleLock) {
            this.activeClient = null;
            this.activeAdbManager = null;
            this.activeRails = Collections.emptyList();
        }
        emitRailStatus(Collections.emptyList());
        transitionState(SessionState.ERROR, errorDetail);
    }

    private JdkHFXClient requireConnectedClient() throws EngineException {
        JdkHFXClient client = this.activeClient;
        if (client == null || !client.isSessionActive() || state != SessionState.CONNECTED) {
            throw new EngineException(JsonRpcError.INVALID_STATE,
                    "Remote operation requires CONNECTED state (current: " + state + ")");
        }
        return client;
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

    private AdbManager.AdbConfig mergeAdbConfig(IpcPayloads.SessionConnectParams p) {
        AdbManager.AdbConfig merged = config.adbConfig.copy();
        if (p.adbPath != null && !p.adbPath.trim().isEmpty()) {
            merged.explicitAdbPath = p.adbPath.trim();
        }
        if (p.adbLegacy) {
            merged.adbLegacy = true;
        }
        if (p.adbStreams != null) {
            merged.overrideStreams = p.adbStreams;
        }
        return merged;
    }

    private static String normalizeConnectMode(IpcPayloads.SessionConnectParams p) throws EngineException {
        String raw = p.mode != null ? p.mode.trim().toLowerCase() : "auto";
        if ("adb".equals(raw)) {
            return "adb";
        }
        if ("direct".equals(raw)) {
            return "direct";
        }
        if ("auto".equals(raw)) {
            if ("adb".equalsIgnoreCase(p.host)) {
                return "adb";
            }
            if (p.host != null && !p.host.trim().isEmpty()) {
                return "direct";
            }
            throw new EngineException(JsonRpcError.INVALID_PARAMS,
                    "Specify mode='adb' or provide host for direct connection (mDNS auto-discovery lands in M4)");
        }
        throw new EngineException(JsonRpcError.INVALID_PARAMS, "Unsupported connect mode: " + p.mode);
    }

    public String resolveEffectiveHomeDir(String requestedHomeDir) {
        if (requestedHomeDir == null || requestedHomeDir.trim().isEmpty() || "/".equals(requestedHomeDir.trim())) {
            return "/";
        }
        String trimmed = requestedHomeDir.trim();
        if (File.separatorChar == '\\' && trimmed.length() == 2
                && Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(1) == ':') {
            trimmed = trimmed + "\\";
        }
        File dir = new File(trimmed);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                emitEvent(IpcEvents.DIAGNOSTIC_WARNING, new IpcPayloads.DiagnosticWarningPayload(
                        "HOME_DIR_FALLBACK",
                        "Target directory could not be created: " + trimmed + "; falling back to /"));
                return "/";
            }
        }
        if (!dir.isDirectory() || dir.listFiles() == null) {
            emitEvent(IpcEvents.DIAGNOSTIC_WARNING, new IpcPayloads.DiagnosticWarningPayload(
                    "HOME_DIR_FALLBACK",
                    "Target directory is not accessible: " + trimmed + "; falling back to /"));
            return "/";
        }
        return dir.getAbsolutePath();
    }

    private static List<RemoteFile> listLocalFilesWithRoots(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            File[] roots = File.listRoots();
            if (roots == null) {
                return null;
            }
            if (roots.length == 1 && "/".equals(roots[0].getAbsolutePath())) {
                return Utils.listRemoteFiles(roots[0].getPath());
            }
            List<RemoteFile> entries = new ArrayList<>(roots.length);
            for (File root : roots) {
                entries.add(new RemoteFile(root.getPath(), root.getPath(),
                        root.lastModified(), root.length(), root.isDirectory()));
            }
            return entries;
        }
        String effective = path;
        if (File.separatorChar == '\\' && effective.length() == 2
                && Character.isLetter(effective.charAt(0)) && effective.charAt(1) == ':') {
            effective = effective + "\\";
        }
        return Utils.listRemoteFiles(effective);
    }

    private static List<IpcDtos.FsEntryDto> toFsEntries(List<RemoteFile> files) {
        List<RemoteFile> sorted = new ArrayList<>(files);
        Utils.sortFiles(sorted);
        List<IpcDtos.FsEntryDto> out = new ArrayList<>(sorted.size());
        for (RemoteFile rf : sorted) {
            out.add(new IpcDtos.FsEntryDto(
                    rf.getName(), rf.getPath(), rf.lastModified(), rf.getSize(), rf.isDirectory()));
        }
        return out;
    }

    private static boolean deleteRecursive(File file) {
        if (!file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    private static long computeLocalTotalBytes(List<String> paths) {
        long total = 0L;
        for (String p : paths) {
            total += sizeOfFileOrDir(new File(p));
        }
        return total;
    }

    private static long sizeOfFileOrDir(File f) {
        if (!f.exists()) {
            return 0L;
        }
        if (f.isFile()) {
            return f.length();
        }
        long sum = 0L;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                sum += sizeOfFileOrDir(c);
            }
        }
        return sum;
    }

    private static String extractBaseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    private static String errMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
