package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.protocol.DataChannelHandshake;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.core.protocol.SessionAuthProvider;
import top.weixiansen574.hybridfilexfer.core.transport.SocketTuner;
import top.weixiansen574.nio.DataByteChannel;

/**
 * Pure Java HFX v4 server base (zero Android / UI dependencies).
 *
 * <p>Implements the v400 {@code HFXC} control handshake, optional HMAC-SHA256
 * peer verification, and {@link Selector}-driven parallel {@code HFXD} data channel
 * registration by {@code (sessionToken, railId, streamIdx)}.</p>
 */
public abstract class AbstractHFXServer extends HFXService {
    protected final Object controlLock = new Object();
    protected ServerSocketChannel serverSocketChannel;
    protected int remoteFileSystem;
    protected String remoteHomeDir;
    protected String connectedClientId;
    protected int preferredBlockSize = ProtocolConstants.DEFAULT_BLOCK_SIZE;
    protected SessionAuthProvider authProvider = SessionAuthProvider.NO_AUTH;

    public void setAuthProvider(SessionAuthProvider authProvider) {
        this.authProvider = authProvider != null ? authProvider : SessionAuthProvider.NO_AUTH;
    }

    public void setPreferredBlockSize(int preferredBlockSize) {
        this.preferredBlockSize = ProtocolConstants.clampBlockSize(preferredBlockSize);
    }

    public int getBoundPort() {
        if (serverSocketChannel == null || !serverSocketChannel.isOpen()) {
            return -1;
        }
        try {
            return ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    public String getConnectedClientId() {
        return connectedClientId;
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public String getRemoteHomeDir() {
        return remoteHomeDir;
    }

    public void startServer(int port,
                            List<ServerNetInterface> interfaceList,
                            int localBufferCount,
                            int remoteBufferCount,
                            ServerHandshakeCallback callback) throws IOException {
        if (interfaceList == null || interfaceList.isEmpty()) {
            throw new IllegalArgumentException("interfaceList must not be empty");
        }
        ServerSocketChannel ssc;
        try {
            ssc = ServerSocketChannel.open();
            SocketTuner.tuneServerSocket(ssc);
            ssc.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            callback.onBindFailed(port);
            return;
        }
        this.serverSocketChannel = ssc;
        callback.onServerStarted();

        DataByteChannel acceptedControl;
        List<TransferConnection> acceptedConnections;

        while (true) {
            SocketChannel controlSocket = ssc.accept();
            SocketTuner.tune(controlSocket);
            DataByteChannel ctrl = new DataByteChannel(controlSocket);

            try {
                byte[] magic = new byte[4];
                ctrl.readFully(magic);
                if (!Arrays.equals(ProtocolConstants.MAGIC_CONTROL_BYTES, magic)) {
                    ctrl.writeBytes("protocol error\n");
                    closeQuietly(ctrl);
                    continue;
                }

                int clientVersion = ctrl.readInt();
                if (clientVersion != VERSION_CODE) {
                    ctrl.writeBoolean(false);
                    ctrl.writeInt(VERSION_CODE);
                    closeQuietly(ctrl);
                    continue;
                }

                int clientCaps = ctrl.readInt();
                String clientId = ctrl.readUTF();
                byte[] nonceC = new byte[ProtocolConstants.NONCE_LENGTH];
                ctrl.readFully(nonceC);
                int requestedAdbStreams = ctrl.readUnsignedByte();

                int negCaps = clientCaps & ProtocolConstants.ALL_V4_CAPS;
                boolean authRequired = authProvider.isAuthRequired();

                ctrl.writeBoolean(true);
                ctrl.writeInt(VERSION_CODE);
                ctrl.writeInt(negCaps);
                ctrl.writeBoolean(authRequired);

                if (authRequired) {
                    byte[] nonceS = HmacAuth.generateRandomBytes(ProtocolConstants.NONCE_LENGTH);
                    ctrl.write(nonceS);
                    byte[] clientHmac = new byte[ProtocolConstants.HMAC_LENGTH];
                    ctrl.readFully(clientHmac);
                    byte[] secret = authProvider.lookupPairSecret(clientId);
                    boolean authOk = HmacAuth.verifyHandshakeHmac(secret, nonceC, nonceS, clientId, clientHmac);
                    ctrl.writeBoolean(authOk);
                    if (!authOk) {
                        callback.onAuthFailed(clientId, secret == null ? "unknown_client" : "invalid_hmac");
                        closeQuietly(ctrl);
                        continue;
                    }
                }

                byte[] sessionToken = HmacAuth.generateRandomBytes(ProtocolConstants.SESSION_TOKEN_LENGTH);
                int chosenBlockSize = ((negCaps & ProtocolConstants.CAP_DYNAMIC_BLOCK) != 0)
                        ? ProtocolConstants.clampBlockSize(preferredBlockSize)
                        : ProtocolConstants.DEFAULT_BLOCK_SIZE;

                int[] effectiveStreamCounts = new int[interfaceList.size()];
                int totalStreams = 0;
                for (int i = 0; i < interfaceList.size(); i++) {
                    ServerNetInterface rail = interfaceList.get(i);
                    int sc = rail.streamCount;
                    if (rail.railType == RailType.ADB && requestedAdbStreams > 0) {
                        sc = ProtocolConstants.clampStreamCount(requestedAdbStreams);
                    } else {
                        sc = ProtocolConstants.clampStreamCount(sc);
                    }
                    effectiveStreamCounts[i] = sc;
                    totalStreams += sc;
                }

                // Write sessionToken + blockSize + rail list
                ctrl.write(sessionToken);
                ctrl.writeInt(chosenBlockSize);
                ctrl.writeInt(interfaceList.size());
                for (int i = 0; i < interfaceList.size(); i++) {
                    ServerNetInterface netInterface = interfaceList.get(i);
                    byte[] address = netInterface.address.getAddress();
                    ctrl.writeUTF(netInterface.name);
                    ctrl.writeByte(netInterface.railType.code);
                    ctrl.writeByte(effectiveStreamCounts[i]);
                    ctrl.writeByte(address.length);
                    ctrl.write(address);
                    if (netInterface.clientBindAddress == null) {
                        ctrl.writeByte(0);
                    } else {
                        byte[] bAddress = netInterface.clientBindAddress.getAddress();
                        ctrl.writeByte(bAddress.length);
                        ctrl.write(bAddress);
                    }
                }

                List<TransferConnection> dataConns = acceptDataChannelsParallel(
                        ssc, controlSocket, ctrl, interfaceList, effectiveStreamCounts,
                        totalStreams, sessionToken, callback);
                if (dataConns == null) {
                    closeQuietly(ctrl);
                    continue;
                }

                // Confirm handshake completion on control channel
                boolean clientReady = ctrl.readBoolean();
                if (!clientReady) {
                    String failedRail = "unknown";
                    try {
                        failedRail = ctrl.readUTF();
                    } catch (IOException ignored) {
                    }
                    closeConnections(dataConns);
                    closeQuietly(ctrl);
                    callback.onAcceptFailed(failedRail);
                    continue;
                }
                ctrl.writeBoolean(true);

                this.negotiatedCapabilities = negCaps;
                this.negotiatedBlockSize = chosenBlockSize;
                this.connectedClientId = clientId;
                acceptedControl = ctrl;
                acceptedConnections = dataConns;
                break;
            } catch (IOException e) {
                closeQuietly(ctrl);
                if (!ssc.isOpen()) {
                    throw e;
                }
            }
        }

        // Allocate remote and local buffers using negotiatedBlockSize
        acceptedControl.writeInt(remoteBufferCount);
        if (!acceptedControl.readBoolean()) {
            callback.onRemoteOOM();
            closeConnections(acceptedConnections);
            closeQuietly(acceptedControl);
            closeQuietly(ssc);
            return;
        }

        for (int i = 0; i < localBufferCount; i++) {
            ByteBuffer buffer = createBuffer(negotiatedBlockSize);
            if (buffer != null) {
                buffers.add(buffer);
            } else {
                freeBuffers();
                acceptedControl.writeBoolean(false);
                closeConnections(acceptedConnections);
                closeQuietly(acceptedControl);
                closeQuietly(ssc);
                callback.onLocalOOM(i, localBufferCount);
                return;
            }
        }
        acceptedControl.writeBoolean(true);

        this.remoteFileSystem = acceptedControl.readInt();
        this.remoteHomeDir = acceptedControl.readUTF();
        this.ctChannel = acceptedControl;
        this.connections = acceptedConnections;
        callback.onConnectSuccess();
    }

    private List<TransferConnection> acceptDataChannelsParallel(
            ServerSocketChannel ssc,
            SocketChannel controlSocket,
            DataByteChannel ctrl,
            List<ServerNetInterface> interfaceList,
            int[] streamCounts,
            int totalStreams,
            byte[] sessionToken,
            ServerHandshakeCallback callback) throws IOException {

        TransferConnection[][] slots = new TransferConnection[interfaceList.size()][];
        for (int i = 0; i < interfaceList.size(); i++) {
            slots[i] = new TransferConnection[streamCounts[i]];
        }

        int accepted = 0;
        long deadline = System.currentTimeMillis() + ProtocolConstants.DATA_HANDSHAKE_TIMEOUT_MS;
        Selector selector = Selector.open();
        try {
            ssc.configureBlocking(false);
            controlSocket.configureBlocking(false);
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            controlSocket.register(selector, SelectionKey.OP_READ);

            while (accepted < totalStreams) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    closeSlots(slots);
                    callback.onAcceptFailed("timeout");
                    return null;
                }
                selector.select(Math.min(remaining, 250));
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isReadable() && key.channel() == controlSocket) {
                        // Control channel became readable before all data streams registered -> client aborted or dropped
                        closeSlots(slots);
                        restoreBlocking(ssc, controlSocket, selector);
                        String failedName = "aborted";
                        try {
                            boolean ok = ctrl.readBoolean();
                            if (!ok) {
                                failedName = ctrl.readUTF();
                            }
                        } catch (IOException ignored) {
                        }
                        callback.onAcceptFailed(failedName);
                        return null;
                    }
                    if (key.isAcceptable() && key.channel() == ssc) {
                        SocketChannel dataSc = ssc.accept();
                        if (dataSc == null) {
                            continue;
                        }
                        try {
                            DataChannelHandshake.DataHello hello =
                                    DataChannelHandshake.readClientHelloWithTimeout(
                                            dataSc, ProtocolConstants.DATA_HELLO_READ_TIMEOUT_MS);
                            boolean valid = DataChannelHandshake.tokenMatches(sessionToken, hello.sessionToken)
                                    && hello.railId >= 0
                                    && hello.railId < interfaceList.size()
                                    && hello.streamIndex >= 0
                                    && hello.streamIndex < streamCounts[hello.railId]
                                    && slots[hello.railId][hello.streamIndex] == null;
                            if (!valid) {
                                try {
                                    DataChannelHandshake.writeServerAck(dataSc, false);
                                } catch (IOException ignored) {
                                }
                                closeQuietly(dataSc);
                                continue;
                            }
                            dataSc.configureBlocking(true);
                            SocketTuner.tune(dataSc);
                            DataChannelHandshake.writeServerAck(dataSc, true);

                            ServerNetInterface rail = interfaceList.get(hello.railId);
                            slots[hello.railId][hello.streamIndex] = new TransferConnection(
                                    hello.railId,
                                    hello.streamIndex,
                                    rail.name,
                                    rail.railType,
                                    new DataByteChannel(dataSc));
                            accepted++;
                            callback.onRailAccepted(rail.name, hello.railId, hello.streamIndex, streamCounts[hello.railId]);
                        } catch (IOException e) {
                            closeQuietly(dataSc);
                        }
                    }
                }
            }
        } finally {
            restoreBlocking(ssc, controlSocket, selector);
        }

        List<TransferConnection> ordered = new ArrayList<>(totalStreams);
        for (int r = 0; r < slots.length; r++) {
            for (int s = 0; s < slots[r].length; s++) {
                if (slots[r][s] == null) {
                    closeSlots(slots);
                    return null;
                }
                ordered.add(slots[r][s]);
            }
        }
        return ordered;
    }

    private static void restoreBlocking(ServerSocketChannel ssc, SocketChannel controlSocket, Selector selector) {
        if (selector != null) {
            try {
                selector.close();
            } catch (IOException ignored) {
            }
        }
        if (ssc != null && ssc.isOpen()) {
            try {
                ssc.configureBlocking(true);
            } catch (IOException ignored) {
            }
        }
        if (controlSocket != null && controlSocket.isOpen()) {
            try {
                controlSocket.configureBlocking(true);
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeSlots(TransferConnection[][] slots) {
        for (TransferConnection[] row : slots) {
            if (row == null) continue;
            for (TransferConnection conn : row) {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static void closeConnections(List<TransferConnection> list) {
        if (list == null) return;
        for (TransferConnection conn : list) {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void closeServerSocket() {
        closeQuietly(serverSocketChannel);
    }

    public void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                                  TransferFileCallback callback) throws IOException {
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);
            sendFiles(files, localDir, remoteDir, callback);
        }
    }

    public void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                                 TransferFileCallback callback) throws IOException {
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
            ctChannel.writeInt(files.size());
            for (RemoteFile file : files) {
                ctChannel.writeUTF(file.getPath());
            }
            ctChannel.writeUTF(localDir.path);
            ctChannel.writeInt(localDir.fileSystem);
            ctChannel.writeUTF(remoteDir.path);
            receiveFiles(callback);
        }
    }

    public List<RemoteFile> listClientFiles(String path) throws IOException {
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
            ctChannel.writeUTF(path);
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
    }

    public boolean deleteRemoteFile(String file) throws IOException {
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
            ctChannel.writeUTF(file);
            return ctChannel.readBoolean();
        }
    }

    public boolean createRemoteDir(String parent, String child) throws IOException {
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.MKDIR);
            ctChannel.writeUTF(parent);
            ctChannel.writeUTF(child);
            return ctChannel.readBoolean();
        }
    }

    public List<String> getConnectionListINames() {
        return getDistinctRailNames();
    }

    public void disconnect() {
        freeBuffers();
        if (ctChannel != null) {
            try {
                ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
            } catch (IOException ignored) {
            }
            closeQuietly(ctChannel);
        }
        closeQuietly(serverSocketChannel);
        closeConnections(connections);
    }

    public void close() {
        freeBuffers();
        closeQuietly(serverSocketChannel);
    }

    protected abstract ByteBuffer createBuffer(int size);

    protected void freeBuffers() {
        buffers.clear();
    }

    protected static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
