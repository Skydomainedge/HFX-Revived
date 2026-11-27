package top.weixiansen574.hybridfilexfer.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression suite for mid-transfer Wi-Fi SSID switch (half-open TCP socket) and
 * teardown {@code controlLock} deadlock prevention in {@link HFXService}, {@link HFXClient},
 * and {@link AbstractHFXServer}.
 */
public class WifiDropAndDisconnectRecoveryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * Loopback TCP proxy that forwards initial handshakes transparently and can then either:
     * <ul>
     *   <li>{@link #blackholeBothSides()}: freeze traffic without closing either socket (silent half-open);</li>
     *   <li>{@link #breakOneSideKeepOtherHalfOpen(boolean)}: close one side (triggering {@code IOException})
     *       while leaving the peer side silently open (reproducing Wi-Fi SSID switch).</li>
     * </ul>
     */
    private static final class FaultyWifiProxy implements AutoCloseable {
        private final ServerSocketChannel acceptChannel;
        private final int targetPort;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean frozen = new AtomicBoolean(false);
        private final AtomicBoolean forwardDataToTarget = new AtomicBoolean(true);
        private final List<SocketChannel> clientSockets = new CopyOnWriteArrayList<>();
        private final List<SocketChannel> serverSockets = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstPairEstablished = new CountDownLatch(1);
        private final Thread acceptThread;

        FaultyWifiProxy(int targetPort) throws IOException {
            this.targetPort = targetPort;
            this.acceptChannel = ServerSocketChannel.open();
            this.acceptChannel.bind(new InetSocketAddress("127.0.0.1", 0));
            this.acceptThread = new Thread(this::acceptLoop, "test-wifi-proxy-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int getPort() throws IOException {
            return ((InetSocketAddress) acceptChannel.getLocalAddress()).getPort();
        }

        boolean awaitConnection(long timeoutMs) throws InterruptedException {
            return firstPairEstablished.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void blackholeBothSides() {
            frozen.set(true);
        }

        void breakOneSideKeepOtherHalfOpen(boolean closeServerSide) {
            frozen.set(true);
            List<SocketChannel> toClose = closeServerSide ? serverSockets : clientSockets;
            for (SocketChannel sc : toClose) {
                try {
                    sc.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void acceptLoop() {
            while (!closed.get() && acceptChannel.isOpen()) {
                try {
                    SocketChannel clientSc = acceptChannel.accept();
                    SocketChannel serverSc = SocketChannel.open(new InetSocketAddress("127.0.0.1", targetPort));
                    clientSockets.add(clientSc);
                    serverSockets.add(serverSc);
                    firstPairEstablished.countDown();
                    spawnPump(clientSc, serverSc, "proxy-c2s");
                    spawnPump(serverSc, clientSc, "proxy-s2c");
                } catch (IOException e) {
                    if (closed.get()) {
                        break;
                    }
                }
            }
        }

        private void spawnPump(SocketChannel src, SocketChannel dst, String name) {
            Thread t = new Thread(() -> {
                ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
                try {
                    while (!closed.get() && src.isOpen() && dst.isOpen()) {
                        if (frozen.get()) {
                            Thread.sleep(20L);
                            continue;
                        }
                        buf.clear();
                        int n = src.read(buf);
                        if (n == -1) {
                            if (!frozen.get()) {
                                dst.shutdownOutput();
                            }
                            break;
                        }
                        if (n > 0 && forwardDataToTarget.get()) {
                            buf.flip();
                            while (buf.hasRemaining() && !frozen.get()) {
                                dst.write(buf);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, name);
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                acceptChannel.close();
            } catch (IOException ignored) {
            }
            for (SocketChannel sc : clientSockets) {
                try {
                    sc.close();
                } catch (IOException ignored) {
                }
            }
            for (SocketChannel sc : serverSockets) {
                try {
                    sc.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Subclass of {@link HFXClient} that routes the {@code wlan0} data rail through a local
     * {@link FaultyWifiProxy} while keeping the control channel and {@code USB_ADB} streams direct.
     */
    private static final class ProxyRoutingClient extends HFXClient {
        private final int wifiProxyPort;

        ProxyRoutingClient(String host, int port, String homeDir, int wifiProxyPort) {
            super(host, port, homeDir);
            this.wifiProxyPort = wifiProxyPort;
        }

        @Override
        public boolean connect(ConnectServerCallback callback) throws IOException {
            // Connect using parent logic, except override port routing is not exposed per-rail,
            // so tests use a custom proxy-aware multi-rail setup via connectWithPerRailPort below.
            return super.connect(callback);
        }

        @Override public ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override public long getAvailableMemoryMB() { return 512; }
        @Override protected boolean deleteLocalFile(String path) { return new File(path).delete(); }
        @Override protected boolean mkdir(String parent, String child) { return new File(parent, child).mkdirs(); }
        @Override protected List<RemoteFile> listFiles(String path) { return Utils.listRemoteFiles(path); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new LocalWriteFileCall(buffers, dequeCount);
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new LocalReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }

        void replaceRailConnection(int index, TransferConnection replacement) throws IOException {
            TransferConnection old = connections.set(index, replacement);
            if (old != null) {
                old.close();
            }
        }

        int getWifiProxyPort() {
            return wifiProxyPort;
        }
    }

    private static final class LocalServer extends AbstractHFXServer {
        @Override protected ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new LocalWriteFileCall(buffers, dequeCount);
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new LocalReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }

        void closeRailForFaultInjection(String railName) {
            if (connections == null) return;
            for (TransferConnection tc : connections) {
                if (railName.equals(tc.iName)) {
                    try {
                        tc.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    @Test
    public void testHalfOpenWifiDropOnSenderConvergesReceiverAndSenderWithoutDeadlock() throws Exception {
        verifyWifiDropConvergence(true);
    }

    @Test
    public void testHalfOpenWifiDropOnReceiverConvergesBothSidesWithoutDeadlock() throws Exception {
        verifyWifiDropConvergence(false);
    }

    private void verifyWifiDropConvergence(boolean breakSenderSideWifi) throws Exception {
        File serverRoot = tempFolder.newFolder("srv_" + breakSenderSideWifi);
        File clientRoot = tempFolder.newFolder("cli_" + breakSenderSideWifi);
        File bigFile = new File(serverRoot, "video_8mb.bin");
        writeRandomFile(bigFile, 8 * 1024 * 1024, 4242L);

        LocalServer server = new LocalServer();
        server.setPreferredBlockSize(256 * 1024);
        server.setTransferAbortTimeoutMs(400L);

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        List<ServerNetInterface> rails = Arrays.asList(
                new ServerNetInterface("USB_ADB", loopback, null, RailType.ADB, 2),
                new ServerNetInterface("wlan0", loopback, null, RailType.WIFI, 1)
        );

        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch serverConnected = new CountDownLatch(1);
        ExecutorService exec = Executors.newCachedThreadPool();

        try {
            Future<?> srvFuture = exec.submit(() -> {
                try {
                    server.startServer(0, rails, 16, 16, new NoOpHandshakeCallback() {
                        @Override public void onServerStarted() { listening.countDown(); }
                        @Override public void onConnectSuccess() { serverConnected.countDown(); }
                    });
                } catch (IOException ignored) {
                }
            });
            assertTrue("Server must start", listening.await(5, TimeUnit.SECONDS));
            int port = server.getBoundPort();

            ProxyRoutingClient client = new ProxyRoutingClient("127.0.0.1", port, clientRoot.getAbsolutePath(), 0);
            client.setTransferAbortTimeoutMs(400L);
            assertTrue("Client connect must succeed", client.connect(new NoOpConnectCallback()));
            assertTrue("Server connect must complete", serverConnected.await(5, TimeUnit.SECONDS));

            // Replace the wlan0 TransferConnection pair on client+server with a FaultyWifiProxy pair
            // so we can simulate an authentic half-open Wi-Fi SSID switch (one side resets, peer stays silent).
            int wifiIdx = findRailIndex(server.connections, "wlan0");
            assertTrue("wlan0 connection must exist", wifiIdx >= 0);

            try (ServerSocketChannel pairSrv = ServerSocketChannel.open()) {
                pairSrv.bind(new InetSocketAddress("127.0.0.1", 0));
                int pairPort = ((InetSocketAddress) pairSrv.getLocalAddress()).getPort();
                try (FaultyWifiProxy proxy = new FaultyWifiProxy(pairPort)) {
                    SocketChannel clientWifi = SocketChannel.open(new InetSocketAddress("127.0.0.1", proxy.getPort()));
                    SocketChannel serverWifi = pairSrv.accept();
                    assertTrue("Proxy must link both sides", proxy.awaitConnection(3000L));

                    server.connections.get(wifiIdx).close();
                    server.connections.set(wifiIdx, new TransferConnection(
                            1, 0, "wlan0", RailType.WIFI, new top.weixiansen574.nio.DataByteChannel(serverWifi)));
                    client.replaceRailConnection(wifiIdx, new TransferConnection(
                            1, 0, "wlan0", RailType.WIFI, new top.weixiansen574.nio.DataByteChannel(clientWifi)));

                    // Simulate user switching Wi-Fi SSID BEFORE starting transfer:
                    // one side's Wi-Fi socket resets, the peer side stays half-open without FIN/RST!
                    proxy.breakOneSideKeepOtherHalfOpen(breakSenderSideWifi);

                    CountDownLatch clientIncomplete = new CountDownLatch(1);
                    CountDownLatch clientLoopEnded = new CountDownLatch(1);
                    Future<?> clientLoop = exec.submit(() -> {
                        try {
                            client.start(new NoOpClientCallback() {
                                @Override public void onIncomplete() { clientIncomplete.countDown(); }
                                @Override public void onExit() { clientLoopEnded.countDown(); }
                            });
                        } catch (Exception ignored) {
                        } finally {
                            clientLoopEnded.countDown();
                        }
                    });

                    // Brief pause so client control loop enters selector
                    Thread.sleep(50L);

                    AtomicBoolean serverIncomplete = new AtomicBoolean(false);
                    Future<Boolean> transferFuture = exec.submit(() -> server.sendFilesToRemote(
                            Collections.singletonList(new RemoteFile(bigFile)),
                            new Directory(serverRoot.getAbsolutePath(), Directory.getCurrentFileSystem()),
                            new Directory(clientRoot.getAbsolutePath(), Directory.getCurrentFileSystem()),
                            new NoOpTransferCallback() {
                                @Override public void onIncomplete() { serverIncomplete.set(true); }
                            }));

                    Boolean keepAlive = transferFuture.get(5, TimeUnit.SECONDS);
                    assertFalse("Broken Wi-Fi transfer must return keepAlive=false", keepAlive);
                    assertTrue("Server must report onIncomplete()", serverIncomplete.get());
                    assertTrue("Client must report onIncomplete() without hanging on half-open Wi-Fi",
                            clientIncomplete.await(4, TimeUnit.SECONDS));
                    assertTrue("Client control loop must terminate promptly",
                            clientLoopEnded.await(4, TimeUnit.SECONDS));

                    server.disconnect();
                    clientLoop.get(3, TimeUnit.SECONDS);
                    srvFuture.get(3, TimeUnit.SECONDS);
                }
            }
        } finally {
            server.close();
            exec.shutdownNow();
        }
    }

    @Test
    public void testDisconnectGracefullyNeverDeadlocksWhenTransferIsStuckOnHalfOpenWifi() throws Exception {
        File serverRoot = tempFolder.newFolder("srv_stuck");
        File clientRoot = tempFolder.newFolder("cli_stuck");
        File bigFile = new File(serverRoot, "stuck_6mb.bin");
        writeRandomFile(bigFile, 6 * 1024 * 1024, 9090L);

        LocalServer server = new LocalServer();
        server.setPreferredBlockSize(256 * 1024);
        server.setTransferAbortTimeoutMs(2500L);

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        List<ServerNetInterface> rails = Arrays.asList(
                new ServerNetInterface("USB_ADB", loopback, null, RailType.ADB, 1),
                new ServerNetInterface("wlan0", loopback, null, RailType.WIFI, 1)
        );

        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch serverConnected = new CountDownLatch(1);
        ExecutorService exec = Executors.newCachedThreadPool();

        try {
            exec.submit(() -> {
                try {
                    server.startServer(0, rails, 16, 16, new NoOpHandshakeCallback() {
                        @Override public void onServerStarted() { listening.countDown(); }
                        @Override public void onConnectSuccess() { serverConnected.countDown(); }
                    });
                } catch (IOException ignored) {
                }
            });
            assertTrue(listening.await(5, TimeUnit.SECONDS));

            ProxyRoutingClient client = new ProxyRoutingClient("127.0.0.1", server.getBoundPort(),
                    clientRoot.getAbsolutePath(), 0);
            client.setTransferAbortTimeoutMs(2500L);
            assertTrue(client.connect(new NoOpConnectCallback()));
            assertTrue(serverConnected.await(5, TimeUnit.SECONDS));

            int wifiIdx = findRailIndex(server.connections, "wlan0");
            try (ServerSocketChannel pairSrv = ServerSocketChannel.open()) {
                pairSrv.bind(new InetSocketAddress("127.0.0.1", 0));
                int pairPort = ((InetSocketAddress) pairSrv.getLocalAddress()).getPort();
                try (FaultyWifiProxy proxy = new FaultyWifiProxy(pairPort)) {
                    SocketChannel clientWifi = SocketChannel.open(new InetSocketAddress("127.0.0.1", proxy.getPort()));
                    SocketChannel serverWifi = pairSrv.accept();
                    assertTrue(proxy.awaitConnection(3000L));

                    server.connections.get(wifiIdx).close();
                    server.connections.set(wifiIdx, new TransferConnection(
                            1, 0, "wlan0", RailType.WIFI, new top.weixiansen574.nio.DataByteChannel(serverWifi)));
                    client.replaceRailConnection(wifiIdx, new TransferConnection(
                            1, 0, "wlan0", RailType.WIFI, new top.weixiansen574.nio.DataByteChannel(clientWifi)));

                    // Blackhole BOTH sides (neither gets FIN/RST; transfer will stall holding controlLock)
                    proxy.blackholeBothSides();

                    CountDownLatch clientReceiving = new CountDownLatch(1);
                    CountDownLatch clientLoopEnded = new CountDownLatch(1);
                    exec.submit(() -> {
                        try {
                            client.start(new NoOpClientCallback() {
                                @Override public void onReceiving() { clientReceiving.countDown(); }
                                @Override public void onExit() { clientLoopEnded.countDown(); }
                            });
                        } catch (Exception ignored) {
                        } finally {
                            clientLoopEnded.countDown();
                        }
                    });
                    Thread.sleep(50L);

                    Future<?> transferFuture = exec.submit(() -> {
                        try {
                            server.sendFilesToRemote(
                                    Collections.singletonList(new RemoteFile(bigFile)),
                                    new Directory(serverRoot.getAbsolutePath(), Directory.getCurrentFileSystem()),
                                    new Directory(clientRoot.getAbsolutePath(), Directory.getCurrentFileSystem()),
                                    new NoOpTransferCallback());
                        } catch (IOException ignored) {
                        }
                    });

                    assertTrue("Client must enter receiving state", clientReceiving.await(3, TimeUnit.SECONDS));
                    Thread.sleep(150L);

                    // Previously: calling disconnectGracefully() while control loop held controlLock
                    // inside receiveFiles() DEADLOCKED forever!
                    long startMs = System.currentTimeMillis();
                    Future<?> clientDisc = exec.submit(client::disconnectGracefully);
                    Future<?> serverDisc = exec.submit(server::disconnect);
                    clientDisc.get(3, TimeUnit.SECONDS);
                    serverDisc.get(3, TimeUnit.SECONDS);
                    long elapsedMs = System.currentTimeMillis() - startMs;

                    assertTrue("Disconnect must finish quickly without controlLock deadlock, took " + elapsedMs + "ms",
                            elapsedMs < 3000L);
                    assertTrue("Client loop must exit after disconnect", clientLoopEnded.await(3, TimeUnit.SECONDS));
                    transferFuture.get(3, TimeUnit.SECONDS);
                }
            }
        } finally {
            server.close();
            exec.shutdownNow();
        }
    }

    private static int findRailIndex(List<TransferConnection> conns, String name) {
        for (int i = 0; i < conns.size(); i++) {
            if (name.equals(conns.get(i).iName)) {
                return i;
            }
        }
        return -1;
    }

    private static void writeRandomFile(File file, int size, long seed) throws IOException {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    private static final class LocalWriteFileCall extends WriteFileCall {
        LocalWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            super(buffers, dequeCount);
        }
        @Override protected void createParentDirIfNotExists(String path) {
            File p = new File(path).getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
        }
        @Override protected void tryMkdirs(String path) { new File(path).mkdirs(); }
        @Override protected OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception {
            final RandomAccessFile raf = new RandomAccessFile(path, "rw");
            if (preallocate) raf.setLength(totalSize);
            final FileChannel ch = raf.getChannel();
            return new OpenFileHandle() {
                @Override public FileChannel getChannel() { return ch; }
                @Override public void close() throws IOException {
                    try { ch.close(); } finally { raf.close(); }
                }
            };
        }
        @Override protected boolean setFileLastModified(String path, long time) {
            return new File(path).setLastModified(time);
        }
    }

    private static final class LocalReadFileCall extends ReadFileCall {
        private RandomAccessFile raf;
        private FileChannel ch;

        LocalReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                          Directory localDir, Directory remoteDir, int threads, int blockSize) {
            super(buffers, files, localDir, remoteDir, threads, blockSize);
        }
        @Override protected boolean fileExists(String path) { return new File(path).exists(); }
        @Override protected List<RemoteFile> listFiles(String path) { return Utils.listRemoteFiles(path); }
        @Override protected FileChannel openFile(String path) throws Exception {
            raf = new RandomAccessFile(path, "r");
            ch = raf.getChannel();
            return ch;
        }
        @Override protected void closeFile() throws Exception {
            try { if (ch != null) ch.close(); } finally { if (raf != null) raf.close(); }
        }
    }

    private static class NoOpHandshakeCallback implements ServerHandshakeCallback {
        @Override public void onBindFailed(int port) {}
        @Override public void onServerStarted() {}
        @Override public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {}
        @Override public void onAcceptFailed(String name) {}
        @Override public void onRemoteOOM() {}
        @Override public void onLocalOOM(int created, int localBufferCount) {}
        @Override public void onConnectSuccess() {}
    }

    private static class NoOpConnectCallback implements ConnectServerCallback {
        @Override public void onConnectingControlChannel(String address, int port) {}
        @Override public void onVersionMismatch(int localVersion, int remoteVersion) {}
        @Override public void onConnectControlFailed() {}
        @Override public void onConnectingTransferChannel(String name, InetAddress addr, InetAddress bind) {}
        @Override public void onConnectTransferChannelFailed(String name, InetAddress addr, Exception e) {}
        @Override public void onOOM(int created, int req, long mem, String arch) {}
        @Override public void onRemoteOOM() {}
        @Override public void onConnectSuccess(List<String> names) {}
    }

    private static class NoOpTransferCallback implements TransferFileCallback {
        @Override public void onFileUploading(String iName, String path, long targetSize, long totalSize) {}
        @Override public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {}
        @Override public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {}
        @Override public void onChannelComplete(String iName, long traffic, long time) {}
        @Override public void onChannelError(String iName, int errorType, String message) {}
        @Override public void onReadFileError(String message) {}
        @Override public void onWriteFileError(String message) {}
        @Override public void onComplete(boolean isUpload, long traffic, long time) {}
        @Override public void onIncomplete() {}
    }

    private static class NoOpClientCallback extends NoOpTransferCallback implements ClientCallBack {
        @Override public void onReceiving() {}
        @Override public void onSending() {}
        @Override public void onExit() {}
    }
}
