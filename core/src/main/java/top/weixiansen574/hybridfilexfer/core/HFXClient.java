package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
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
            List<TransferConnection> established = connectDataChannelsParallel(
                    rails, totalStreams, sessionToken, callback);
            if (established == null) {
                closeQuietly(ctChannel);
                return false;
            }
            connections = established;

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
            int totalStreams,
            final byte[] sessionToken,
            final ConnectServerCallback callback) throws IOException {
        for (ServerNetInterface rail : rails) {
            InetAddress effectiveBind = resolveLocalBindAddress(rail);
            callback.onConnectingTransferChannel(rail.name, rail.address, effectiveBind);
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
            final InetAddress effectiveBind = resolveLocalBindAddress(rail);
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

    public void requestShutdown() {
        isRun = false;
        closeAllConnections();
        closeQuietly(ctChannel);
        freeBuffers();
    }

    public abstract ByteBuffer createBuffer(int size);

    public abstract long getAvailableMemoryMB();

    public void start(ClientCallBack transferFileCallback) throws Exception {
        this.callBack = transferFileCallback;
        while (isRun) {
            short id = ctChannel.readShort();
            switch (id) {
                case ControllerIdentifiers.LIST_FILES:
                    handleListFiles();
                    break;
                case ControllerIdentifiers.DELETE_FILE:
                    handleDeleteFile();
                    break;
                case ControllerIdentifiers.MKDIR:
                    handleMkdir();
                    break;
                case ControllerIdentifiers.REQUEST_RECEIVE:
                    handleReceiveFiles();
                    break;
                case ControllerIdentifiers.REQUEST_SEND:
                    handleSendFiles();
                    break;
                case ControllerIdentifiers.SHUTDOWN:
                    handleShutdown();
                    break;
                default:
                    throw new IOException("Unknown controller command id: " + id);
            }
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
        if (callBack != null) {
            callBack.onExit();
        }
    }

    private void handleListFiles() throws Exception {
        String path = ctChannel.readUTF();
        if (!"/".equals(path)) {
            List<RemoteFile> files = listFiles(path);
            if (files != null) {
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {
                ctChannel.writeInt(-1);
            }
        } else {
            File[] roots = File.listRoots();
            if (roots != null && roots.length == 1 && "/".equals(roots[0].getAbsolutePath())) {
                List<RemoteFile> files = listFiles(roots[0].getPath());
                if (files == null) {
                    ctChannel.writeInt(-1);
                    return;
                }
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else if (roots != null) {
                ctChannel.writeInt(roots.length);
                for (File file : roots) {
                    ctChannel.writeUTF(file.getPath());
                    ctChannel.writeUTF(file.getPath());
                    ctChannel.writeLong(file.lastModified());
                    ctChannel.writeLong(file.length());
                    ctChannel.writeBoolean(file.isDirectory());
                }
            } else {
                ctChannel.writeInt(-1);
            }
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
        callBack.onReceiving();
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
        callBack.onSending();
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
}
