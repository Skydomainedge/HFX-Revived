package top.weixiansen574.hybridfilexfer.engine;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.AbstractHFXServer;
import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.OpenFileHandle;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.PairCallbackMessage;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM unit &amp; loopback integration tests for Android M4 headless pairing components:
 * {@link TrustedPcStore}, {@link PairClient}, and {@link HfxEngineController} pairing dispatch.
 */
public class AndroidPairingAndTrustedStoreTest {

    private static final class InMemoryPersistence implements TrustedPcStore.PersistenceBackend {
        final Map<String, TrustedPcStore.TrustedPcRecord> rows = new LinkedHashMap<>();

        @Override
        public synchronized List<TrustedPcStore.TrustedPcRecord> loadAll() {
            List<TrustedPcStore.TrustedPcRecord> out = new ArrayList<>();
            for (TrustedPcStore.TrustedPcRecord r : rows.values()) {
                out.add(r.copy());
            }
            return out;
        }

        @Override
        public synchronized void upsert(TrustedPcStore.TrustedPcRecord record) {
            rows.put(record.pcId, record.copy());
        }

        @Override
        public synchronized boolean delete(String pcId) {
            return rows.remove(pcId) != null;
        }
    }

    private static final class LoopbackJvmServer extends AbstractHFXServer {
        @Override protected ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new WriteFileCall(buffers, dequeCount) {
                @Override protected void createParentDirIfNotExists(String path) {}
                @Override protected void tryMkdirs(String path) {}
                @Override protected OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception {
                    final RandomAccessFile raf = new RandomAccessFile(path, "rw");
                    final FileChannel ch = raf.getChannel();
                    return new OpenFileHandle() {
                        @Override public FileChannel getChannel() { return ch; }
                        @Override public void close() throws IOException { try { ch.close(); } finally { raf.close(); } }
                    };
                }
                @Override protected boolean setFileLastModified(String path, long time) { return true; }
            };
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new ReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize) {
                @Override protected boolean fileExists(String path) { return false; }
                @Override protected List<RemoteFile> listFiles(String path) { return Collections.emptyList(); }
                @Override protected FileChannel openFile(String path) { return null; }
                @Override protected void closeFile() {}
            };
        }
    }

    private static final class LoopbackJvmClient extends HFXClient {
        LoopbackJvmClient(String host, int port, String homeDir) {
            super(host, port, homeDir);
        }
        @Override public ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override public long getAvailableMemoryMB() { return 256; }
        @Override protected boolean deleteLocalFile(String path) { return false; }
        @Override protected boolean mkdir(String parent, String child) { return false; }
        @Override protected List<RemoteFile> listFiles(String path) { return Collections.emptyList(); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) { return null; }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) { return null; }
    }

    private static HfxEngineController.ServerSessionBackend createLoopbackBackend(
            final AtomicReference<LoopbackJvmServer> serverHolder) {
        return new HfxEngineController.ServerSessionBackend() {
            @Override
            public AbstractHFXServer acquireServer(int ioMode) {
                LoopbackJvmServer s = new LoopbackJvmServer();
                s.setPreferredBlockSize(256 * 1024);
                serverHolder.set(s);
                return s;
            }
            @Override public void releaseServer(AbstractHFXServer server) {}
            @Override public List<RemoteFile> listLocalFiles(AbstractHFXServer server, String path) { return Utils.listRemoteFiles(path); }
            @Override public boolean createLocalDir(AbstractHFXServer server, String parent, String child) { return false; }
            @Override public boolean deleteLocalFile(AbstractHFXServer server, String path) { return false; }
            @Override public RemoteFile resolveLocalFile(AbstractHFXServer server, String path) { return new RemoteFile(new File(path)); }
        };
    }

    @Test
    public void testTrustedPcStoreLegacyModePendingPromotionAndPersistence() {
        InMemoryPersistence db = new InMemoryPersistence();
        TrustedPcStore store = new TrustedPcStore(db);

        // Fresh install with no paired PCs -> authRequired=false (100% compatible with legacy -c adb)
        assertFalse(store.isAuthRequired());
        assertEquals(0, store.size());

        // Register pending pairing -> authRequired=true and lookupPairSecret resolves pending PC
        byte[] secret = HmacAuth.generateRandomBytes(32);
        final AtomicReference<TrustedPcStore.TrustedPcRecord> committedRef = new AtomicReference<>();
        final AtomicReference<Boolean> viaFallbackRef = new AtomicReference<>();
        store.setPendingCommitListener((rec, viaFallback) -> {
            committedRef.set(rec);
            viaFallbackRef.set(viaFallback);
        });

        store.registerPendingPairing("pc-omen-01", "OMEN-8-Plus", secret,
                Collections.singletonList("10.94.81.203"), 60_000L, true);
        assertTrue(store.isAuthRequired());
        assertArrayEquals(secret, store.lookupPairSecret("pc-omen-01"));
        assertNull(store.lookupPairSecret("unknown-pc"));

        // HMAC handshake success promotes pending entry into durable backend
        store.onAuthSucceeded("pc-omen-01");
        assertNotNull(committedRef.get());
        assertEquals("pc-omen-01", committedRef.get().pcId);
        assertEquals(Boolean.TRUE, viaFallbackRef.get());
        assertEquals(1, db.rows.size());

        // Reload into new store simulating service restart
        TrustedPcStore reloaded = new TrustedPcStore(db);
        assertTrue(reloaded.isAuthRequired());
        assertArrayEquals(secret, reloaded.lookupPairSecret("pc-omen-01"));
        assertEquals(1, reloaded.listPeers().size());

        // Remove peer -> back to 0 peers
        assertTrue(reloaded.removePeer("pc-omen-01"));
        assertFalse(reloaded.isAuthRequired());
        assertEquals(0, db.rows.size());
    }

    @Test
    public void testControllerDirectHttpPairingAndPeersManagement() throws Exception {
        final byte[] token = HmacAuth.generateRandomBytes(16);
        final String pcId = "pc-direct-12";
        final String pcName = "OMEN-Direct";
        final String phoneId = "phone-direct-99";

        // Spin up minimal mock PC HTTP pairing server on ephemeral port
        try (ServerSocket pcHttpServer = new ServerSocket(0)) {
            int pcPairPort = pcHttpServer.getLocalPort();
            ExecutorService httpExec = Executors.newSingleThreadExecutor();
            final AtomicReference<PairCallbackMessage.Request> receivedReq = new AtomicReference<>();

            httpExec.submit(() -> {
                try (Socket s = pcHttpServer.accept()) {
                    InputStream in = s.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String reqLine = reader.readLine();
                    int contentLen = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLen = Integer.parseInt(line.substring("content-length:".length()).trim());
                        }
                    }
                    char[] bodyChars = new char[contentLen];
                    int read = 0;
                    while (read < contentLen) {
                        int n = reader.read(bodyChars, read, contentLen - read);
                        if (n < 0) break;
                        read += n;
                    }
                    PairCallbackMessage.Request parsed = IpcJson.fromJson(
                            new String(bodyChars, 0, read), PairCallbackMessage.Request.class);
                    receivedReq.set(parsed);

                    boolean validProof = HmacAuth.verifyPairProof(
                            token, pcId, parsed.deviceId, parsed.port, parsed.nonce, parsed.proof);
                    PairCallbackMessage.Response resp = new PairCallbackMessage.Response();
                    resp.ok = validProof;
                    resp.pcId = pcId;
                    resp.pcName = pcName;
                    resp.ackProof = HmacAuth.computePairAckProof(token, pcId, parsed.deviceId, parsed.nonce);
                    byte[] respBytes = IpcJson.toJson(resp).getBytes(StandardCharsets.UTF_8);

                    OutputStream out = s.getOutputStream();
                    String hdr = "HTTP/1.1 " + (validProof ? "200 OK" : "403 Forbidden") + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + respBytes.length + "\r\n\r\n";
                    out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                    out.write(respBytes);
                    out.flush();
                } catch (Exception ignored) {
                }
            });

            InMemoryPersistence db = new InMemoryPersistence();
            TrustedPcStore store = new TrustedPcStore(db);
            AtomicReference<LoopbackJvmServer> srvRef = new AtomicReference<>();
            CountDownLatch pairSucceeded = new CountDownLatch(1);
            AtomicReference<IpcPayloads.PairSucceededPayload> pairPayload = new AtomicReference<>();

            try (HfxEngineController controller = new HfxEngineController(
                    phoneId, "OnePlus 13", new UsbNicAdvisor(),
                    createLoopbackBackend(srvRef), 250L,
                    store, MdnsAdvertisementBridge.NO_OP, new PairClient(1000))) {

                controller.configurePairDefaults(0, 0, 16, 16);
                controller.applyAdvice(new UsbNicAdvisor().advise(
                        Collections.singletonList(new UsbNicAdvisor.RawInterface(
                                "wlan0", "wlan0", (Inet4Address) InetAddress.getByName("127.0.0.1"), 8, true, false)),
                        "super-speed"));
                controller.addEventListener((event, payload) -> {
                    if (IpcEvents.PAIR_SUCCEEDED.equals(event)) {
                        pairPayload.set((IpcPayloads.PairSucceededPayload) payload);
                        pairSucceeded.countDown();
                    }
                });

                String uri = PairUriCodec.encode(new PairUriCodec.PairPayload(
                        1, pcId, pcName, pcPairPort, token,
                        Collections.singletonList("127.0.0.1"), pcName + ".local"));

                IpcPayloads.PairSubmitUriParams submitParams = new IpcPayloads.PairSubmitUriParams();
                submitParams.uri = uri;
                JsonRpcResponse resp = controller.dispatch(null, IpcMethods.PAIR_SUBMIT_QR_URI,
                        IpcJson.toJsonObject(submitParams));
                assertTrue("pair.submit_qr_uri must succeed", resp.isSuccess());

                assertTrue("pair.succeeded event must arrive", pairSucceeded.await(5, TimeUnit.SECONDS));
                assertEquals(pcId, pairPayload.get().peerId);
                assertFalse(pairPayload.get().viaFallbackMdns);
                assertNotNull(receivedReq.get());
                assertTrue("Mobile server must bind an ephemeral port", receivedReq.get().port > 0);
                assertTrue("Trusted PC must be stored in backend", store.hasTrustedPeers());

                // Verify peers.list and peers.remove IPC commands
                JsonRpcResponse listResp = controller.dispatch(null, IpcMethods.PEERS_LIST, null);
                IpcPayloads.PeersListResult list = IpcJson.fromJson(listResp.result, IpcPayloads.PeersListResult.class);
                assertEquals(1, list.peers.size());
                assertEquals(pcId, list.peers.get(0).deviceId);

                IpcPayloads.PeerRemoveParams rmParams = new IpcPayloads.PeerRemoveParams();
                rmParams.deviceId = pcId;
                JsonRpcResponse rmResp = controller.dispatch(null, IpcMethods.PEERS_REMOVE,
                        IpcJson.toJsonObject(rmParams));
                assertTrue(rmResp.isSuccess());
                assertEquals(0, store.size());
            } finally {
                httpExec.shutdownNow();
            }
        }
    }

    @Test
    public void testControllerFirewallFallbackPublishesPairAckAndAcceptsOutboundHmac() throws Exception {
        final byte[] token = HmacAuth.generateRandomBytes(16);
        final String pcId = "pc-fw-fallback";
        final String pcName = "OMEN-BlockedFW";
        final String phoneId = "phone-fw-88";
        final String expectedAck = HmacAuth.computePairAckHash(token);

        // Allocate and immediately close a port so HTTP callback hits Connection Refused
        int blockedPort;
        try (ServerSocket closed = new ServerSocket(0)) {
            blockedPort = closed.getLocalPort();
        }

        final CountDownLatch pairAckPublished = new CountDownLatch(1);
        final CountDownLatch pairAckCleared = new CountDownLatch(1);
        final AtomicReference<String> publishedAckRef = new AtomicReference<>();

        MdnsAdvertisementBridge spyMdns = new MdnsAdvertisementBridge() {
            @Override
            public void startAdvertising(int port, String deviceId, String deviceName,
                                         List<String> ips, String usbType, String pairAck) {
                if (pairAck != null && !pairAck.isEmpty()) {
                    publishedAckRef.set(pairAck);
                    pairAckPublished.countDown();
                }
            }

            @Override
            public void updatePairAck(String pairAck) {
                if (pairAck == null && publishedAckRef.get() != null) {
                    pairAckCleared.countDown();
                }
            }

            @Override
            public void stopAdvertising() {
            }
        };

        InMemoryPersistence db = new InMemoryPersistence();
        TrustedPcStore store = new TrustedPcStore(db);
        AtomicReference<LoopbackJvmServer> srvRef = new AtomicReference<>();
        CountDownLatch pairSucceeded = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IpcPayloads.PairSucceededPayload> pairPayload = new AtomicReference<>();

        try (HfxEngineController controller = new HfxEngineController(
                phoneId, "OnePlus 13", new UsbNicAdvisor(),
                createLoopbackBackend(srvRef), 250L,
                store, spyMdns, new PairClient(500))) {

            controller.configurePairDefaults(0, 0, 16, 16);
            controller.applyAdvice(new UsbNicAdvisor().advise(
                    Collections.singletonList(new UsbNicAdvisor.RawInterface(
                            "wlan0", "wlan0", (Inet4Address) InetAddress.getByName("127.0.0.1"), 8, true, false)),
                    "super-speed"));
            controller.addEventListener((event, payload) -> {
                if (IpcEvents.PAIR_SUCCEEDED.equals(event)) {
                    pairPayload.set((IpcPayloads.PairSucceededPayload) payload);
                    pairSucceeded.countDown();
                } else if (IpcEvents.SESSION_STATE_CHANGED.equals(event)) {
                    IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
                    if (sc.current == SessionState.CONNECTED) {
                        connected.countDown();
                    }
                }
            });

            String uri = PairUriCodec.encode(new PairUriCodec.PairPayload(
                    1, pcId, pcName, blockedPort, token,
                    Collections.singletonList("127.0.0.1"), pcName + ".local"));
            IpcPayloads.PairSubmitUriParams params = new IpcPayloads.PairSubmitUriParams();
            params.uri = uri;
            assertTrue(controller.dispatch(null, IpcMethods.PAIR_SUBMIT_QR_URI, IpcJson.toJsonObject(params)).isSuccess());

            // Mobile must detect HTTP failure and publish matching pair_ack via mDNS
            assertTrue("Expected mDNS pair_ack fallback publication", pairAckPublished.await(5, TimeUnit.SECONDS));
            assertEquals(expectedAck, publishedAckRef.get());

            // Simulate PC capturing pair_ack and connecting outbound with HFXC v4 + HMAC
            int mobilePort = srvRef.get().getBoundPort();
            assertTrue(mobilePort > 0);
            byte[] derivedSecret = HmacAuth.derivePairSecret(token, pcId, phoneId);

            LoopbackJvmClient pcClient = new LoopbackJvmClient("127.0.0.1", mobilePort, "/");
            pcClient.setClientId(pcId);
            pcClient.setPairSecret(derivedSecret);
            try {
                assertTrue("PC outbound HMAC connect must succeed", pcClient.connect(new ConnectServerCallback() {
                    @Override public void onConnectingControlChannel(String address, int port) {}
                    @Override public void onVersionMismatch(int localVersion, int remoteVersion) {}
                    @Override public void onConnectControlFailed() {}
                    @Override public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {}
                    @Override public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {}
                    @Override public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {}
                    @Override public void onRemoteOOM() {}
                    @Override public void onConnectSuccess(List<String> channelNames) {}
                }));

                assertTrue("pair.succeeded must fire on HMAC promotion", pairSucceeded.await(5, TimeUnit.SECONDS));
                assertTrue("Fallback flag must be true", pairPayload.get().viaFallbackMdns);
                assertTrue("pair_ack TXT must be cleared once paired", pairAckCleared.await(3, TimeUnit.SECONDS));
                assertTrue("Controller must transition to CONNECTED", connected.await(5, TimeUnit.SECONDS));
                assertTrue("PC must be persisted to trusted store", store.hasTrustedPeers());
            } finally {
                pcClient.requestShutdown();
            }
        }
    }

    @Test
    public void testControllerQrPairingHonorsSelectedRailsAndSupportsExcludeAdbFallback() throws Exception {
        final byte[] token = HmacAuth.generateRandomBytes(16);
        final String pcId = "pc-exclude-adb";
        final String pcName = "OMEN-NoAdb";
        final String phoneId = "phone-wired-99";

        int blockedPort;
        try (ServerSocket closed = new ServerSocket(0)) {
            blockedPort = closed.getLocalPort();
        }

        final CountDownLatch pairAckPublished = new CountDownLatch(1);
        MdnsAdvertisementBridge spyMdns = new MdnsAdvertisementBridge() {
            @Override
            public void startAdvertising(int port, String deviceId, String deviceName,
                                         List<String> ips, String usbType, String pairAck) {
                if (pairAck != null && !pairAck.isEmpty()) {
                    pairAckPublished.countDown();
                }
            }
            @Override public void updatePairAck(String pairAck) {}
            @Override public void stopAdvertising() {}
        };

        InMemoryPersistence db = new InMemoryPersistence();
        TrustedPcStore store = new TrustedPcStore(db);
        AtomicReference<LoopbackJvmServer> srvRef = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);

        try (HfxEngineController controller = new HfxEngineController(
                phoneId, "PJZ110", new UsbNicAdvisor(),
                createLoopbackBackend(srvRef), 250L,
                store, spyMdns, new PairClient(300))) {

            controller.configurePairDefaults(0, 0, 16, 16);
            controller.addEventListener((event, payload) -> {
                if (IpcEvents.SESSION_STATE_CHANGED.equals(event)) {
                    IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
                    if (sc.current == SessionState.CONNECTED) {
                        connected.countDown();
                    }
                }
            });

            IpcDtos.RailSelection rndis = new IpcDtos.RailSelection();
            rndis.name = "rndis0";
            rndis.ip = "127.0.0.1";
            rndis.railType = RailType.USB_NIC.name();
            rndis.streamCount = 1;

            IpcDtos.RailSelection wifi = new IpcDtos.RailSelection();
            wifi.name = "wlan0";
            wifi.ip = "127.0.0.1";
            wifi.railType = RailType.WIFI.name();
            wifi.streamCount = 1;

            IpcDtos.RailSelection adb = new IpcDtos.RailSelection();
            adb.name = ProtocolConstants.USB_ADB_RAIL_NAME;
            adb.ip = "127.0.0.1";
            adb.railType = RailType.ADB.name();
            adb.streamCount = 1;

            String uri = PairUriCodec.encode(new PairUriCodec.PairPayload(
                    1, pcId, pcName, blockedPort, token,
                    Collections.singletonList("127.0.0.1"), pcName + ".local"));
            IpcPayloads.PairSubmitUriParams params = new IpcPayloads.PairSubmitUriParams();
            params.uri = uri;
            params.selectedRails = Arrays.asList(rndis, wifi, adb);
            assertTrue(controller.dispatch(null, IpcMethods.PAIR_SUBMIT_QR_URI, IpcJson.toJsonObject(params)).isSuccess());
            assertTrue("Expected mDNS fallback activation", pairAckPublished.await(5, TimeUnit.SECONDS));

            int mobilePort = srvRef.get().getBoundPort();
            byte[] derivedSecret = HmacAuth.derivePairSecret(token, pcId, phoneId);

            // PC connects requesting CAP_EXCLUDE_ADB_RAIL (e.g. after ADB forward failed)
            LoopbackJvmClient pcClient = new LoopbackJvmClient("127.0.0.1", mobilePort, "/");
            pcClient.setClientId(pcId);
            pcClient.setPairSecret(derivedSecret);
            pcClient.setClientCapabilities(ProtocolConstants.ALL_V4_CAPS | ProtocolConstants.CAP_EXCLUDE_ADB_RAIL);
            try {
                assertTrue("PC connect excluding ADB must succeed", pcClient.connect(new ConnectServerCallback() {
                    @Override public void onConnectingControlChannel(String address, int port) {}
                    @Override public void onVersionMismatch(int localVersion, int remoteVersion) {}
                    @Override public void onConnectControlFailed() {}
                    @Override public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {}
                    @Override public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {}
                    @Override public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {}
                    @Override public void onRemoteOOM() {}
                    @Override public void onConnectSuccess(List<String> channelNames) {}
                }));
                assertTrue("Controller must transition to CONNECTED", connected.await(5, TimeUnit.SECONDS));
                assertEquals(Arrays.asList("rndis0", "wlan0"), srvRef.get().getDistinctRailNames());
            } finally {
                pcClient.requestShutdown();
            }
        }
    }
}
