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
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.protocol.Base64Url;
import top.weixiansen574.hybridfilexfer.core.protocol.DataChannelHandshake;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.core.protocol.SessionAuthProvider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProtocolV4LoopbackTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final class LocalTestWriteFileCall extends WriteFileCall {
        LocalTestWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            super(buffers, dequeCount);
        }

        @Override
        protected void createParentDirIfNotExists(String path) throws Exception {
            File p = new File(path).getParentFile();
            if (p != null && !p.exists()) {
                p.mkdirs();
            }
        }

        @Override
        protected void tryMkdirs(String path) {
            new File(path).mkdirs();
        }

        @Override
        protected OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception {
            final RandomAccessFile raf = new RandomAccessFile(path, "rw");
            if (preallocate) {
                raf.setLength(totalSize);
            }
            final FileChannel ch = raf.getChannel();
            return new OpenFileHandle() {
                @Override public FileChannel getChannel() { return ch; }
                @Override public void close() throws IOException {
                    try { ch.close(); } finally { raf.close(); }
                }
            };
        }

        @Override
        protected boolean setFileLastModified(String path, long time) {
            return new File(path).setLastModified(time);
        }
    }

    private static final class LocalTestReadFileCall extends ReadFileCall {
        private RandomAccessFile raf;
        private FileChannel ch;

        LocalTestReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
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

    private static final class TestServer extends AbstractHFXServer {
        @Override protected ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new LocalTestWriteFileCall(buffers, dequeCount);
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new LocalTestReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }
    }

    private static class TestClient extends HFXClient {
        TestClient(String host, int port, String homeDir) {
            super(host, port, homeDir);
        }

        @Override public ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override public long getAvailableMemoryMB() { return 512; }
        @Override protected boolean deleteLocalFile(String path) { return new File(path).delete(); }
        @Override protected boolean mkdir(String parent, String child) { return new File(parent, child).mkdirs(); }
        @Override protected List<RemoteFile> listFiles(String path) { return Utils.listRemoteFiles(path); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new LocalTestWriteFileCall(buffers, dequeCount);
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new LocalTestReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }
    }

    @Test
    public void testPairUriCodecAndHmacAuth() {
        byte[] token = HmacAuth.generateRandomBytes(16);
        PairUriCodec.PairPayload payload = new PairUriCodec.PairPayload(
                1, "pc1234567890", "暗影精灵 8 Plus", 5741,
                token, Arrays.asList("10.94.81.203", "192.168.50.236"), "OMEN-8P");

        String uri = PairUriCodec.encode(payload);
        assertTrue(uri.startsWith("hfx://pair?v=1&"));

        PairUriCodec.PairPayload decoded = PairUriCodec.decode(uri);
        assertEquals(1, decoded.version);
        assertEquals("pc1234567890", decoded.pcId);
        assertEquals("暗影精灵 8 Plus", decoded.hostName);
        assertEquals(5741, decoded.port);
        assertArrayEquals(token, decoded.token);
        assertEquals(Arrays.asList("10.94.81.203", "192.168.50.236"), decoded.ips);
        assertEquals("OMEN-8P", decoded.mdnsName);

        String ack = HmacAuth.computePairAckHash(token);
        assertEquals(16, ack.length());
        assertArrayEquals(token, Base64Url.decode(Base64Url.encode(token)));

        byte[] secret = HmacAuth.generateRandomBytes(32);
        byte[] nC = HmacAuth.generateRandomBytes(16);
        byte[] nS = HmacAuth.generateRandomBytes(16);
        byte[] mac = HmacAuth.computeHandshakeHmac(secret, nC, nS, "client-A");
        assertTrue(HmacAuth.verifyHandshakeHmac(secret, nC, nS, "client-A", mac));
        assertFalse(HmacAuth.verifyHandshakeHmac(secret, nC, nS, "client-B", mac));
        mac[0] ^= 0x01;
        assertFalse(HmacAuth.verifyHandshakeHmac(secret, nC, nS, "client-A", mac));
    }

    @Test
    public void testV400MultiStreamHmacHandshakeAndBidirectionalTransfer() throws Exception {
        final byte[] sharedSecret = HmacAuth.generateRandomBytes(32);
        final String trustedClientId = "pc-trusted-001";

        final TestServer server = new TestServer();
        server.setPreferredBlockSize(256 * 1024); // 256 KB to test dynamic block negotiation
        server.setAuthProvider(new SessionAuthProvider() {
            @Override public boolean isAuthRequired() { return true; }
            @Override public byte[] lookupPairSecret(String clientId) {
                return trustedClientId.equals(clientId) ? sharedSecret : null;
            }
        });

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        // 2 physical rails: USB_ADB (client will negotiate 4 streams!) + ncm0 (2 streams) => 6 concurrent data streams
        final List<ServerNetInterface> rails = Arrays.asList(
                new ServerNetInterface("USB_ADB", loopback, null, RailType.ADB, 1),
                new ServerNetInterface("ncm0", loopback, null, RailType.USB_NIC, 2)
        );

        final CountDownLatch serverListening = new CountDownLatch(1);
        final CountDownLatch serverConnected = new CountDownLatch(1);
        final AtomicInteger authFailures = new AtomicInteger();
        final AtomicInteger acceptedStreams = new AtomicInteger();

        ExecutorService exec = Executors.newCachedThreadPool();
        Future<?> serverFuture = exec.submit(() -> {
            try {
                server.startServer(0, rails, 16, 16, new ServerHandshakeCallback() {
                    @Override public void onBindFailed(int port) {}
                    @Override public void onServerStarted() { serverListening.countDown(); }
                    @Override public void onAuthFailed(String clientId, String reason) { authFailures.incrementAndGet(); }
                    @Override public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {
                        acceptedStreams.incrementAndGet();
                    }
                    @Override public void onAcceptFailed(String name) {}
                    @Override public void onRemoteOOM() {}
                    @Override public void onLocalOOM(int created, int localBufferCount) {}
                    @Override public void onConnectSuccess() { serverConnected.countDown(); }
                });
            } catch (IOException ignored) {
            }
        });

        assertTrue("Server failed to start", serverListening.await(5, TimeUnit.SECONDS));
        int port = server.getBoundPort();
        assertTrue(port > 0);

        // 1) Wrong HMAC secret must be rejected cleanly and server must stay alive
        TestClient badClient = new TestClient("127.0.0.1", port, tempFolder.getRoot().getAbsolutePath());
        badClient.setClientId(trustedClientId);
        badClient.setPairSecret(HmacAuth.generateRandomBytes(32)); // wrong secret
        final AtomicBoolean authRejected = new AtomicBoolean(false);
        assertFalse(badClient.connect(new RecordingConnectCallback() {
            @Override public void onAuthFailed(String reason) { authRejected.set(true); }
        }));
        assertTrue("Server must reject wrong HMAC", authRejected.get());
        assertEquals(1, authFailures.get());

        // 2) Valid trusted client requesting 4 ADB streams + 2 ncm0 streams = 6 concurrent streams!
        File clientRoot = tempFolder.newFolder("client_fs");
        File serverRoot = tempFolder.newFolder("server_fs");

        TestClient goodClient = new TestClient("127.0.0.1", port, clientRoot.getAbsolutePath());
        goodClient.setClientId(trustedClientId);
        goodClient.setPairSecret(sharedSecret);
        goodClient.setRequestedAdbStreams(4);

        final AtomicReference<List<String>> reportedRails = new AtomicReference<>();
        assertTrue("Trusted client handshake must succeed", goodClient.connect(new RecordingConnectCallback() {
            @Override public void onConnectSuccess(List<String> channelNames) {
                reportedRails.set(channelNames);
            }
        }));
        assertTrue("Server did not finish connect", serverConnected.await(5, TimeUnit.SECONDS));

        assertEquals(256 * 1024, server.getNegotiatedBlockSize());
        assertEquals(256 * 1024, goodClient.getNegotiatedBlockSize());
        assertEquals("Expected 4 (USB_ADB) + 2 (ncm0) = 6 accepted data streams", 6, acceptedStreams.get());
        assertEquals(Arrays.asList("USB_ADB", "ncm0"), reportedRails.get());
        assertEquals(Arrays.asList("USB_ADB", "ncm0"), server.getDistinctRailNames());

        // Start client loop in background
        final CountDownLatch clientExited = new CountDownLatch(1);
        Future<?> clientLoop = exec.submit(() -> {
            try {
                goodClient.start(new RecordingClientCallback() {
                    @Override public void onExit() { clientExited.countDown(); }
                });
            } catch (Exception ignored) {
            }
        });

        // 3) Create test dataset on server (multi-MB binary + nested dir + empty file) and send Server -> Client
        File serverBatch = new File(serverRoot, "batch_s2c");
        File nestedDir = new File(serverBatch, "nested");
        assertTrue(nestedDir.mkdirs());
        File bigFile = new File(serverBatch, "large_3mb.bin");
        File emptyFile = new File(nestedDir, "empty.txt");
        File smallFile = new File(nestedDir, "small.bin");
        writeRandomFile(bigFile, 3 * 1024 * 1024 + 12345, 777L);
        writeRandomFile(emptyFile, 0, 888L);
        writeRandomFile(smallFile, 77777, 999L);

        Directory serverDir = new Directory(serverRoot.getAbsolutePath(), Directory.getCurrentFileSystem());
        Directory clientDir = new Directory(clientRoot.getAbsolutePath(), Directory.getCurrentFileSystem());
        final AtomicBoolean s2cDone = new AtomicBoolean(false);
        server.sendFilesToRemote(
                Collections.singletonList(new RemoteFile(serverBatch)),
                serverDir, clientDir,
                new RecordingTransferCallback() {
                    @Override public void onComplete(boolean isUpload, long traffic, long time) {
                        s2cDone.set(isUpload);
                    }
                });
        assertTrue("Server -> Client transfer must complete", s2cDone.get());

        // Verify SHA-256 of every transferred file
        assertFileSha256Equals(bigFile, new File(clientRoot, "batch_s2c/large_3mb.bin"));
        assertFileSha256Equals(emptyFile, new File(clientRoot, "batch_s2c/nested/empty.txt"));
        assertFileSha256Equals(smallFile, new File(clientRoot, "batch_s2c/nested/small.bin"));

        // 4) Send Client -> Server (download direction) and verify SHA-256!
        File serverDownloadDest = tempFolder.newFolder("server_download");
        Directory downloadDestDir = new Directory(serverDownloadDest.getAbsolutePath(), Directory.getCurrentFileSystem());
        final AtomicBoolean c2sDone = new AtomicBoolean(false);
        server.sendFilesToShelf(
                Collections.singletonList(new RemoteFile(new File(clientRoot, "batch_s2c"))),
                downloadDestDir, clientDir,
                new RecordingTransferCallback() {
                    @Override public void onComplete(boolean isUpload, long traffic, long time) {
                        c2sDone.set(!isUpload);
                    }
                });
        assertTrue("Client -> Server transfer must complete", c2sDone.get());

        assertFileSha256Equals(bigFile, new File(serverDownloadDest, "batch_s2c/large_3mb.bin"));
        assertFileSha256Equals(emptyFile, new File(serverDownloadDest, "batch_s2c/nested/empty.txt"));
        assertFileSha256Equals(smallFile, new File(serverDownloadDest, "batch_s2c/nested/small.bin"));

        // 5) Verify remote listFiles / mkdir / delete over v400 control channel
        assertTrue(server.createRemoteDir(clientRoot.getAbsolutePath(), "created_via_v4"));
        assertTrue(new File(clientRoot, "created_via_v4").isDirectory());
        List<RemoteFile> listed = server.listClientFiles(clientRoot.getAbsolutePath());
        assertTrue(listed != null && listed.size() >= 2);
        assertTrue(server.deleteRemoteFile(new File(clientRoot, "created_via_v4").getAbsolutePath()));

        // Clean disconnect
        server.disconnect();
        assertTrue("Client did not exit cleanly on SHUTDOWN", clientExited.await(5, TimeUnit.SECONDS));
        serverFuture.get(5, TimeUnit.SECONDS);
        clientLoop.get(5, TimeUnit.SECONDS);
        exec.shutdownNow();
    }

    @Test
    public void testDataChannelHandshakeCodecAndSpeedAggregation() throws Exception {
        byte[] token = HmacAuth.generateRandomBytes(ProtocolConstants.SESSION_TOKEN_LENGTH);
        ByteBuffer encoded = DataChannelHandshake.encodeClientHello(token, 3, 2);
        assertEquals(ProtocolConstants.DATA_HELLO_LENGTH, encoded.remaining());

        DataChannelHandshake.DataHello decoded = DataChannelHandshake.decodeClientHello(encoded);
        assertArrayEquals(token, decoded.sessionToken);
        assertEquals(3, decoded.railId);
        assertEquals(2, decoded.streamIndex);

        // Verify SpeedMonitorThread aggregates multiple streams of the same physical rail
        SocketChannel dummy1 = SocketChannel.open();
        SocketChannel dummy2 = SocketChannel.open();
        SocketChannel dummy3 = SocketChannel.open();
        try {
            TransferConnection adb0 = new TransferConnection(0, 0, "USB_ADB", RailType.ADB,
                    new top.weixiansen574.nio.DataByteChannel(dummy1));
            TransferConnection adb1 = new TransferConnection(0, 1, "USB_ADB", RailType.ADB,
                    new top.weixiansen574.nio.DataByteChannel(dummy2));
            TransferConnection ncm0 = new TransferConnection(1, 0, "ncm0", RailType.USB_NIC,
                    new top.weixiansen574.nio.DataByteChannel(dummy3));

            adb0.addUploadedBytes(500_000);
            adb1.addUploadedBytes(750_000);
            ncm0.addUploadedBytes(2_000_000);

            List<TrafficInfo> agg = SpeedMonitorThread.aggregateByRail(Arrays.asList(adb0, adb1, ncm0), 500L);
            assertEquals(2, agg.size());
            assertEquals("USB_ADB", agg.get(0).iName);
            assertEquals(2_500_000L, agg.get(0).uploadTraffic); // (1.25 MB / 0.5s) = 2.5 MB/s
            assertEquals("ncm0", agg.get(1).iName);
            assertEquals(4_000_000L, agg.get(1).uploadTraffic); // (2.0 MB / 0.5s) = 4.0 MB/s
        } finally {
            dummy1.close();
            dummy2.close();
            dummy3.close();
        }
    }

    @Test
    public void testExcludeAdbRailCapabilityDropsAdbAndPreservesSoleAdbRail() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        final List<ServerNetInterface> mixedRails = Arrays.asList(
                new ServerNetInterface("rndis0", loopback, null, RailType.USB_NIC, 1),
                new ServerNetInterface("wlan0", loopback, null, RailType.WIFI, 1),
                new ServerNetInterface(ProtocolConstants.USB_ADB_RAIL_NAME, loopback, null, RailType.ADB, 1)
        );

        final TestServer server = new TestServer();
        server.setPreferredBlockSize(256 * 1024);
        final CountDownLatch serverListening = new CountDownLatch(1);
        final CountDownLatch serverConnected = new CountDownLatch(1);
        final AtomicInteger acceptFailures = new AtomicInteger(0);

        ExecutorService exec = Executors.newCachedThreadPool();
        try {
            Future<?> serverFuture = exec.submit(() -> {
                try {
                    server.startServer(0, mixedRails, 16, 16, new ServerHandshakeCallback() {
                        @Override public void onBindFailed(int port) {}
                        @Override public void onServerStarted() { serverListening.countDown(); }
                        @Override public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {}
                        @Override public void onAcceptFailed(String name) { acceptFailures.incrementAndGet(); }
                        @Override public void onRemoteOOM() {}
                        @Override public void onLocalOOM(int created, int localBufferCount) {}
                        @Override public void onConnectSuccess() { serverConnected.countDown(); }
                    });
                } catch (IOException ignored) {
                }
            });

            assertTrue("Server must start", serverListening.await(5, TimeUnit.SECONDS));
            int port = server.getBoundPort();

            // Attempt 1: client fails ADB rail preparation -> aborts cleanly while flagging non-ADB alternatives
            TestClient attempt1 = new TestClient("127.0.0.1", port, tempFolder.getRoot().getAbsolutePath()) {
                @Override
                protected AdbRailPrepareResult prepareAdbRailBeforeDataConnect(List<ServerNetInterface> rails) {
                    return AdbRailPrepareResult.failed("No online ADB device", true);
                }
            };
            assertFalse("Attempt 1 should abort when ADB prep fails",
                    attempt1.connect(new RecordingConnectCallback()));
            assertTrue("Must flag ADB failure with non-ADB alternatives",
                    attempt1.isAdbRailFailedWithNonAdbAlternatives());
            assertEquals("No online ADB device", attempt1.getLastAdbRailFailureReason());

            // Attempt 2: client retries with CAP_EXCLUDE_ADB_RAIL -> server drops USB_ADB and connects rndis0 + wlan0
            TestClient attempt2 = new TestClient("127.0.0.1", port, tempFolder.getRoot().getAbsolutePath());
            attempt2.setClientCapabilities(ProtocolConstants.ALL_V4_CAPS | ProtocolConstants.CAP_EXCLUDE_ADB_RAIL);
            final AtomicReference<List<String>> connectedNames = new AtomicReference<>();
            assertTrue("Attempt 2 with CAP_EXCLUDE_ADB_RAIL must succeed",
                    attempt2.connect(new RecordingConnectCallback() {
                        @Override public void onConnectSuccess(List<String> channelNames) {
                            connectedNames.set(channelNames);
                        }
                    }));
            assertTrue("Server must complete handshake on fallback attempt", serverConnected.await(5, TimeUnit.SECONDS));
            assertEquals(1, acceptFailures.get());
            assertEquals(Arrays.asList("rndis0", "wlan0"), connectedNames.get());
            assertEquals(Arrays.asList("rndis0", "wlan0"), server.getDistinctRailNames());

            attempt2.disconnectGracefully();
            server.disconnect();
            serverFuture.get(5, TimeUnit.SECONDS);

            // Verify sole USB_ADB rail is NEVER dropped even if CAP_EXCLUDE_ADB_RAIL is set
            final TestServer adbOnlyServer = new TestServer();
            adbOnlyServer.setPreferredBlockSize(256 * 1024);
            final CountDownLatch adbOnlyListening = new CountDownLatch(1);
            final CountDownLatch adbOnlyConnected = new CountDownLatch(1);
            Future<?> adbOnlyFuture = exec.submit(() -> {
                try {
                    adbOnlyServer.startServer(0,
                            Collections.singletonList(new ServerNetInterface(
                                    ProtocolConstants.USB_ADB_RAIL_NAME, loopback, null, RailType.ADB, 1)),
                            16, 16, new ServerHandshakeCallback() {
                                @Override public void onBindFailed(int port) {}
                                @Override public void onServerStarted() { adbOnlyListening.countDown(); }
                                @Override public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {}
                                @Override public void onAcceptFailed(String name) {}
                                @Override public void onRemoteOOM() {}
                                @Override public void onLocalOOM(int created, int localBufferCount) {}
                                @Override public void onConnectSuccess() { adbOnlyConnected.countDown(); }
                            });
                } catch (IOException ignored) {
                }
            });
            assertTrue(adbOnlyListening.await(5, TimeUnit.SECONDS));
            TestClient adbOnlyClient = new TestClient("127.0.0.1", adbOnlyServer.getBoundPort(),
                    tempFolder.getRoot().getAbsolutePath());
            adbOnlyClient.setClientCapabilities(ProtocolConstants.ALL_V4_CAPS | ProtocolConstants.CAP_EXCLUDE_ADB_RAIL);
            assertTrue("Sole ADB rail must be preserved", adbOnlyClient.connect(new RecordingConnectCallback()));
            assertTrue(adbOnlyConnected.await(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList(ProtocolConstants.USB_ADB_RAIL_NAME),
                    adbOnlyServer.getDistinctRailNames());
            adbOnlyClient.disconnectGracefully();
            adbOnlyServer.disconnect();
            adbOnlyFuture.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }

    private static void writeRandomFile(File file, int size, long seed) throws IOException {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    private static void assertFileSha256Equals(File expected, File actual) throws Exception {
        assertTrue("Expected file exists: " + expected, expected.exists());
        assertTrue("Actual file exists: " + actual, actual.exists());
        assertEquals("Size mismatch for " + actual.getName(), expected.length(), actual.length());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h1 = md.digest(Files.readAllBytes(expected.toPath()));
        byte[] h2 = md.digest(Files.readAllBytes(actual.toPath()));
        assertArrayEquals("SHA-256 mismatch for " + actual.getName(), h1, h2);
    }

    private static class RecordingConnectCallback implements ConnectServerCallback {
        @Override public void onConnectingControlChannel(String address, int port) {}
        @Override public void onVersionMismatch(int localVersion, int remoteVersion) {}
        @Override public void onConnectControlFailed() {}
        @Override public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {}
        @Override public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {}
        @Override public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {}
        @Override public void onRemoteOOM() {}
        @Override public void onConnectSuccess(List<String> channelNames) {}
    }

    private static class RecordingTransferCallback implements TransferFileCallback {
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

    private static class RecordingClientCallback extends RecordingTransferCallback implements ClientCallBack {
        @Override public void onReceiving() {}
        @Override public void onSending() {}
        @Override public void onExit() {}
    }
}
