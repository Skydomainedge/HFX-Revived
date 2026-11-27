package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.DataChannelHandshake;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.core.transport.SocketTuner;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXClient extends HFXService {
    protected final String serverControllerAddress;
    protected final int serverPort;
    protected final String homeDir;
    protected volatile boolean isRun = true;
    protected ClientCallBack callBack;

    private String clientId = UUID.randomUUID().toString();
    private byte[] pairSecret;
    private int requestedAdbStreams = 0; // 0 = accept server default; 1..8 = explicit request
    private int clientCapabilities = ProtocolConstants.ALL_V4_CAPS;
    private volatile List<ServerNetInterface> negotiatedRails = Collections.emptyList();
    private volatile List<InetAddress> resolvedBindAddresses = Collections.emptyList();
    private final AtomicBoolean exitNotified = new AtomicBoolean(false);

    public HFXClient(String serverControllerAddress, int serverPort, String homeDir) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
        this.homeDir = homeDir;
    }

    public void setClientId(String clientId) {
        if (clientId != null && !clientId.isEmpty()) {
            this.clientId = clientId;
        }
    }

    public String getClientId() {
        return clientId;
    }

    public String getHomeDir() {
        return homeDir;
    }

    public String getServerControllerAddress() {
        return serverControllerAddress;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setPairSecret(byte[] pairSecret) {
        this.pairSecret = pairSecret;
    }

    public void setRequestedAdbStreams(int requestedAdbStreams) {
        this.requestedAdbStreams = Math.max(0, Math.min(ProtocolConstants.MAX_STREAMS_PER_RAIL, requestedAdbStreams));
    }

    public int getRequestedAdbStreams() {
        return requestedAdbStreams;
    }

    public void setClientCapabilities(int capabilities) {
        this.clientCapabilities = capabilities;
    }

    @Override
    public List<IpcDtos.RailStatusInfo> getRailStatusInfos(String state, String udcSpeed) {
        List<IpcDtos.RailStatusInfo> base = super.getRailStatusInfos(state, udcSpeed);
        List<ServerNetInterface> rails = this.negotiatedRails;
        List<InetAddress> binds = this.resolvedBindAddresses;
        for (IpcDtos.RailStatusInfo info : base) {
            if (info.railId >= 0 && info.railId < rails.size()) {
                ServerNetInterface sni = rails.get(info.railId);
                if (sni.address != null) {
                    info.address = sni.address.getHostAddress();
                }
                if (info.railId < binds.size() && binds.get(info.railId) != null) {
                    info.bindAddress = binds.get(info.railId).getHostAddress();
                } else if (sni.clientBindAddress != null) {
                    info.bindAddress = sni.clientBindAddress.getHostAddress();
                }
            }
        }
        return base;
    }

    /**
     * Hook for subclasses (e.g. PC engine in M2) to resolve an automatic same-subnet
     * source bind IP when the server does not specify an explicit {@code clientBindAddress}.
     */
    protected InetAddress resolveLocalBindAddress(ServerNetInterface rail) {
        return rail.clientBindAddress;
    }

    public boolean connect(ConnectServerCallback callback) throws IOException {
        SocketChannel controlSocket = null;
        try {
            callback.onConnectingControlChannel(serverControllerAddress, serverPort);
            controlSocket = SocketChannel.open();
            SocketTuner.tune(controlSocket);
            controlSocket.socket().connect(
                    new InetSocketAddress(serverControllerAddress, serverPort),
                    ProtocolConstants.SOCKET_CONNECT_TIMEOUT_MS);
            ctChannel = new DataByteChannel(controlSocket);

            byte[] nonceC = HmacAuth.generateRandomBytes(ProtocolConstants.NONCE_LENGTH);
            byte[] clientIdBytes = clientId.getBytes(StandardCharsets.UTF_8);
            if (clientIdBytes.length > 65535) {
                throw new IOException("clientId too long");
            }
            ByteBuffer hello = ByteBuffer.allocate(
                    4 + 4 + 4 + 2 + clientIdBytes.length + ProtocolConstants.NONCE_LENGTH + 1);
            hello.put(ProtocolConstants.MAGIC_CONTROL_BYTES);
            hello.putInt(VERSION_CODE);
            hello.putInt(clientCapabilities);
            hello.putShort((short) clientIdBytes.length);
            hello.put(clientIdBytes);
            hello.put(nonceC);
            hello.put((byte) requestedAdbStreams);
            hello.flip();
            ctChannel.writeFully(hello);

            boolean versionOk = ctChannel.readBoolean();
            int serverVersion = ctChannel.readInt();
            if (!versionOk) {
                callback.onVersionMismatch(VERSION_CODE, serverVersion);
                closeQuietly(ctChannel);
                return false;
            }

            negotiatedCapabilities = ctChannel.readInt();
            boolean authRequired = ctChannel.readBoolean();
            if (authRequired) {
                byte[] nonceS = new byte[ProtocolConstants.NONCE_LENGTH];
                ctChannel.readFully(nonceS);
                byte[] hmac;
                if (pairSecret != null && pairSecret.length > 0) {
                    hmac = HmacAuth.computeHandshakeHmac(pairSecret, nonceC, nonceS, clientId);
                } else {
                    hmac = new byte[ProtocolConstants.HMAC_LENGTH];
                }
                ctChannel.write(hmac);
                boolean authOk = ctChannel.readBoolean();
                if (!authOk) {
                    callback.onAuthFailed("HMAC authentication rejected by server");
                    closeQuietly(ctChannel);
                    return false;
                }
            }

            byte[] sessionToken = new byte[ProtocolConstants.SESSION_TOKEN_LENGTH];
            ctChannel.readFully(sessionToken);
            negotiatedBlockSize = ProtocolConstants.clampBlockSize(ctChannel.readInt());

            int railCount = ctChannel.readInt();
            if (railCount <= 0 || railCount > 64) {
                throw new IOException("Invalid railCount from server: " + railCount);
            }
            List<ServerNetInterface> rails = new ArrayList<>(railCount);
            int totalStreams = 0;
            for (int r = 0; r < railCount; r++) {
                String name = ctChannel.readUTF();
                RailType railType = RailType.fromCode(ctChannel.readByte());
                int streamCount = ProtocolConstants.clampStreamCount(ctChannel.readUnsignedByte());
                int addrLen = ctChannel.readUnsignedByte();
                byte[] addrBytes = new byte[addrLen];
                ctChannel.readFully(addrBytes);
                InetAddress remoteAddr = InetAddress.getByAddress(addrBytes);

                int bindLen = ctChannel.readUnsignedByte();
                InetAddress bindAddr = null;
                if (bindLen != 0) {
                    byte[] bindBytes = new byte[bindLen];
                    ctChannel.readFully(bindBytes);
                    bindAddr = InetAddress.getByAddress(bindBytes);
                }
                rails.add(new ServerNetInterface(name, remoteAddr, bindAddr, railType, streamCount));
                totalStreams += streamCount;
            }

            // Connect all (railId, streamIdx) data channels concurrently
            List<InetAddress> effectiveBinds = new ArrayList<>(rails.size());
            for (ServerNetInterface rail : rails) {
                effectiveBinds.add(resolveLocalBindAddress(rail));
            }
            List<TransferConnection> established = connectDataChannelsParallel(
                    rails, effectiveBinds, totalStreams, sessionToken, callback);
            if (established == null) {
                closeQuietly(ctChannel);
                return false;
            }
            connections = established;
            negotiatedRails = Collections.unmodifiableList(rails);
            resolvedBindAddresses = Collections.unmodifiableList(effectiveBinds);

            // Confirm data-channel phase with server
            ctChannel.writeBoolean(true);
            if (!ctChannel.readBoolean()) {
                closeAllConnections();
                closeQuietly(ctChannel);
                return false;
            }

            // Allocate transfer buffers with negotiatedBlockSize
            int bufferCount = ctChannel.readInt();
            for (int i = 0; i < bufferCount; i++) {
                ByteBuffer buffer = createBuffer(negotiatedBlockSize);
                if (buffer != null) {
                    buffers.add(buffer);
                } else {
                    String arch = System.getProperty("os.arch");
                    long availableMemoryMB = getAvailableMemoryMB();
                    freeBuffers();
                    callback.onOOM(i, bufferCount, availableMemoryMB, arch);
                    ctChannel.writeBoolean(false);
                    closeAllConnections();
                    closeQuietly(ctChannel);
                    return false;
                }
            }
            ctChannel.writeBoolean(true);
            if (!ctChannel.readBoolean()) {
                freeBuffers();
                callback.onRemoteOOM();
                closeAllConnections();
                closeQuietly(ctChannel);
                return false;
            }

            // Exchange local filesystem type and homeDir
            ctChannel.writeInt(Directory.getCurrentFileSystem());
            ctChannel.writeUTF(homeDir);

            Set<String> distinctNames = new LinkedHashSet<>();
            for (TransferConnection connection : connections) {
                distinctNames.add(connection.iName);
            }
            callback.onConnectSuccess(new ArrayList<>(distinctNames));
            return true;
        } catch (IOException e) {
            closeAllConnections();
            closeQuietly(ctChannel);
            if (ctChannel == null) {
                closeQuietly(controlSocket);
            }
            callback.onConnectControlFailed();
            return false;
        }
    }

    private List<TransferConnection> connectDataChannelsParallel(
            List<ServerNetInterface> rails,
            List<InetAddress> effectiveBinds,
            int totalStreams,
            final byte[] sessionToken,
            final ConnectServerCallback callback) throws IOException {
        for (int i = 0; i < rails.size(); i++) {
            ServerNetInterface rail = rails.get(i);
            callback.onConnectingTransferChannel(rail.name, rail.address, effectiveBinds.get(i));
        }

        int poolSize = Math.min(totalStreams, 16);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Future<TransferConnection>> futures = new ArrayList<>(totalStreams);
        final AtomicReference<String> firstFailedRail = new AtomicReference<>(null);
        final AtomicReference<InetAddress> firstFailedAddr = new AtomicReference<>(null);
        final AtomicReference<Exception> firstError = new AtomicReference<>(null);

        for (int railId = 0; railId < rails.size(); railId++) {
            final int rId = railId;
            final ServerNetInterface rail = rails.get(railId);
            final InetAddress effectiveBind = effectiveBinds.get(railId);
            for (int sIdx = 0; sIdx < rail.streamCount; sIdx++) {
                final int streamIdx = sIdx;
                futures.add(executor.submit(new Callable<TransferConnection>() {
                    @Override
                    public TransferConnection call() throws Exception {
                        SocketChannel sc = null;
                        try {
                            sc = SocketChannel.open();
                            SocketTuner.tune(sc);
                            if (effectiveBind != null) {
                                sc.bind(new InetSocketAddress(effectiveBind, 0));
                            }
                            sc.socket().connect(
                                    new InetSocketAddress(rail.address, serverPort),
                                    ProtocolConstants.SOCKET_CONNECT_TIMEOUT_MS);
                            DataChannelHandshake.writeClientHelloAndAwaitAck(
                                    sc, sessionToken, rId, streamIdx, ProtocolConstants.DATA_HANDSHAKE_TIMEOUT_MS);
                            return new TransferConnection(
                                    rId, streamIdx, rail.name, rail.railType, new DataByteChannel(sc));
                        } catch (Exception e) {
                            closeQuietly(sc);
                            if (firstFailedRail.compareAndSet(null, rail.name)) {
                                firstFailedAddr.set(rail.address);
                                firstError.set(e);
                            }
                            throw e;
                        }
                    }
                }));
            }
        }

        executor.shutdown();
        List<TransferConnection> result = new ArrayList<>(totalStreams);
        boolean failed = false;
        for (Future<TransferConnection> future : futures) {
            try {
                result.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = true;
                if (firstFailedRail.compareAndSet(null, "INTERRUPTED")) {
                    firstError.set(e);
                }
            } catch (ExecutionException e) {
                failed = true;
            }
        }

        if (failed) {
            for (TransferConnection conn : result) {
                try {
                    conn.close();
                } catch (IOException ignored) {
                }
            }
            String failedName = firstFailedRail.get() != null ? firstFailedRail.get() : "unknown";
            InetAddress failedAddr = firstFailedAddr.get();
            Exception ex = firstError.get() != null ? firstError.get() : new IOException("Data channel connect failed");
            callback.onConnectTransferChannelFailed(failedName, failedAddr, ex);
            try {
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF(failedName);
            } catch (IOException ignored) {
            }
            return null;
        }
        return result;
    }

    /**
     * Initiates a graceful shutdown towards the server, stops the control loop, and releases all resources.
     * Uses bounded lock acquisition so a stuck transfer on a half-open Wi-Fi socket can never deadlock teardown.
     */
    public void disconnectGracefully() {
        isRun = false;
        boolean busyTransfer = isTransferInProgress();
        cancelActiveTransfer();
        if (!busyTransfer && ctChannel != null && ctChannel.isOpen()) {
            tryExecuteWithControlLock(new ControlAction<Void>() {
                @Override
                public Void run() throws IOException {
                    try {
                        ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
                    } catch (IOException ignored) {
                    }
                    return null;
                }
            }, DEFAULT_SHUTDOWN_LOCK_TIMEOUT_MS);
        }
        stopControlLoop();
        closeAllConnections();
        closeQuietly(ctChannel);
        awaitTransferThreadsQuietly(1000L);
        freeBuffers();
    }

    public void requestShutdown() {
        isRun = false;
        cancelActiveTransfer();
        stopControlLoop();
        closeAllConnections();
        closeQuietly(ctChannel);
        awaitTransferThreadsQuietly(1000L);
        freeBuffers();
    }

    public abstract ByteBuffer createBuffer(int size);

    public abstract long getAvailableMemoryMB();

    // ---- Symmetric client-initiated peer RPCs (M3 headless PC control) ----

    public List<RemoteFile> listRemoteFiles(final String path) throws IOException {
        return executeWithControlLock(new ControlAction<List<RemoteFile>>() {
            @Override
            public List<RemoteFile> run() throws IOException {
                ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
                ctChannel.writeUTF(path != null ? path : "/");
                int listSize = ctChannel.readInt();
                if (listSize == -1) {
                    return null;
                }
                ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
                for (int i = 0; i < listSize; i++) {
                    remoteFiles.add(new RemoteFile(
                            ctChannel.readUTF(),
                            ctChannel.readUTF(),
                            ctChannel.readLong(),
                            ctChannel.readLong(),
                            ctChannel.readBoolean()));
                }
                return remoteFiles;
            }
        });
    }

    public boolean createRemoteDir(final String parent, final String child) throws IOException {
        return executeWithControlLock(new ControlAction<Boolean>() {
            @Override
            public Boolean run() throws IOException {
                ctChannel.writeShort(ControllerIdentifiers.MKDIR);
                ctChannel.writeUTF(parent);
                ctChannel.writeUTF(child);
                return ctChannel.readBoolean();
            }
        });
    }

    public boolean deleteRemoteFile(final String path) throws IOException {
        return executeWithControlLock(new ControlAction<Boolean>() {
            @Override
            public Boolean run() throws IOException {
                ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
                ctChannel.writeUTF(path);
                return ctChannel.readBoolean();
            }
        });
    }

    /**
     * Uploads local files from PC client to the remote server.
     */
    public boolean sendFilesToRemote(final List<RemoteFile> files,
                                     final Directory localDir,
                                     final Directory remoteDir,
                                     final TransferFileCallback callback) throws IOException {
        return executeWithControlLock(new ControlAction<Boolean>() {
            @Override
            public Boolean run() throws IOException {
                ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);
                return sendFiles(files, localDir, remoteDir, callback);
            }
        });
    }

    /**
     * Downloads remote files from the server to local PC client directory.
     */
    public boolean sendFilesToShelf(final List<RemoteFile> remoteFiles,
                                    final Directory localDestDir,
                                    final Directory remoteSrcDir,
                                    final TransferFileCallback callback) throws IOException {
        return executeWithControlLock(new ControlAction<Boolean>() {
            @Override
            public Boolean run() throws IOException {
                ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
                ctChannel.writeInt(remoteFiles.size());
                for (RemoteFile file : remoteFiles) {
                    ctChannel.writeUTF(file.getPath());
                }
                ctChannel.writeUTF(localDestDir.path);
                ctChannel.writeInt(localDestDir.fileSystem);
                ctChannel.writeUTF(remoteSrcDir.path);
                return receiveFiles(callback);
            }
        });
    }

    // ---- Local filesystem helpers exposed to PC engine runtime ----

    public List<RemoteFile> listLocalFilesWithRoots(String path) throws Exception {
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            if (Directory.getCurrentFileSystem() == Directory.FILE_SYSTEM_WINDOWS
                    && path.length() == 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
                path = path + "\\";
            }
            return listFiles(path);
        }
        File[] roots = File.listRoots();
        if (roots == null) {
            return null;
        }
        if (roots.length == 1 && "/".equals(roots[0].getAbsolutePath())) {
            return listFiles(roots[0].getPath());
        }
        List<RemoteFile> entries = new ArrayList<>(roots.length);
        for (File root : roots) {
            entries.add(new RemoteFile(root.getPath(), root.getPath(), root.lastModified(), root.length(), root.isDirectory()));
        }
        return entries;
    }

    public boolean createLocalDirectory(String parent, String child) throws Exception {
        return mkdir(parent, child);
    }

    public boolean deleteLocalPath(String path) throws Exception {
        return deleteLocalFile(path);
    }

    public void start(ClientCallBack transferFileCallback) throws Exception {
        start(transferFileCallback, null);
    }

    public void start(ClientCallBack transferFileCallback, Runnable onLoopStarted) throws Exception {
        this.callBack = transferFileCallback != null ? transferFileCallback : NO_OP_CALLBACK;
        final AtomicReference<Throwable> loopError = new AtomicReference<>(null);
        runPausableControlLoop(new ControlCommandHandler() {
            @Override
            public boolean handleCommand(short id) throws Exception {
                if (!isRun) {
                    return false;
                }
                switch (id) {
                    case ControllerIdentifiers.LIST_FILES:
                        handleListFiles();
                        return isRun;
                    case ControllerIdentifiers.DELETE_FILE:
                        handleDeleteFile();
                        return isRun;
                    case ControllerIdentifiers.MKDIR:
                        handleMkdir();
                        return isRun;
                    case ControllerIdentifiers.REQUEST_RECEIVE:
                        handleReceiveFiles();
                        return isRun;
                    case ControllerIdentifiers.REQUEST_SEND:
                        handleSendFiles();
                        return isRun;
                    case ControllerIdentifiers.SHUTDOWN:
                        handleShutdown();
                        return false;
                    default:
                        throw new IOException("Unknown controller command id: " + id);
                }
            }

            @Override
            public void onLoopExit(Throwable error) {
                loopError.set(error);
                isRun = false;
                closeQuietly(ctChannel);
                closeAllConnections();
                freeBuffers();
                if (error == null && callBack != null && exitNotified.compareAndSet(false, true)) {
                    callBack.onExit();
                }
            }
        }, onLoopStarted);
        Throwable err = loopError.get();
        if (err != null) {
            if (err instanceof Exception) {
                throw (Exception) err;
            }
            throw new IOException("Control loop error", err);
        }
    }

    private void handleDeleteFile() throws Exception {
        ctChannel.writeBoolean(deleteLocalFile(ctChannel.readUTF()));
    }

    protected abstract boolean deleteLocalFile(String path) throws Exception;

    private void handleMkdir() throws Exception {
        String parent = ctChannel.readUTF();
        String child = ctChannel.readUTF();
        ctChannel.writeBoolean(mkdir(parent, child));
    }

    protected abstract boolean mkdir(String parent, String child) throws Exception;

    private void handleShutdown() {
        isRun = false;
        closeQuietly(ctChannel);
        closeAllConnections();
        freeBuffers();
        if (callBack != null && exitNotified.compareAndSet(false, true)) {
            callBack.onExit();
        }
    }

    private void handleListFiles() throws Exception {
        String path = ctChannel.readUTF();
        List<RemoteFile> files = listLocalFilesWithRoots(path);
        if (files != null) {
            ctChannel.writeInt(files.size());
            for (RemoteFile file : files) {
                writeFile(file);
            }
        } else {
            ctChannel.writeInt(-1);
        }
    }

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    private void writeFile(RemoteFile file) throws IOException {
        ctChannel.writeUTF(file.getName());
        ctChannel.writeUTF(file.getPath());
        ctChannel.writeLong(file.lastModified());
        ctChannel.writeLong(file.getSize());
        ctChannel.writeBoolean(file.isDirectory());
    }

    private void handleReceiveFiles() throws IOException {
        if (callBack != null) {
            callBack.onReceiving();
        }
        isRun = receiveFiles(callBack);
    }

    private void handleSendFiles() throws IOException {
        int listSize = ctChannel.readInt();
        List<RemoteFile> fileList = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            fileList.add(new RemoteFile(new File(ctChannel.readUTF())));
        }
        Directory remoteDir = new Directory(ctChannel.readUTF(), ctChannel.readInt());
        Directory localDir = new Directory(ctChannel.readUTF(), Directory.getCurrentFileSystem());
        if (callBack != null) {
            callBack.onSending();
        }
        isRun = sendFiles(fileList, localDir, remoteDir, callBack);
    }

    protected void closeAllConnections() {
        if (connections != null) {
            for (TransferConnection transferConnection : connections) {
                try {
                    transferConnection.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    protected static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    protected void freeBuffers() {
        buffers.clear();
    }

    private static final ClientCallBack NO_OP_CALLBACK = new ClientCallBack() {
        @Override public void onReceiving() {}
        @Override public void onSending() {}
        @Override public void onExit() {}
        @Override public void onFileUploading(String iName, String path, long targetSize, long totalSize) {}
        @Override public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {}
        @Override public void onSpeedInfo(List<top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo> trafficInfoList) {}
        @Override public void onChannelComplete(String iName, long traffic, long time) {}
        @Override public void onChannelError(String iName, int errorType, String message) {}
        @Override public void onReadFileError(String message) {}
        @Override public void onWriteFileError(String message) {}
        @Override public void onComplete(boolean isUpload, long traffic, long time) {}
        @Override public void onIncomplete() {}
    };
}
