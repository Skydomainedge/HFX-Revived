package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import com.google.gson.JsonPrimitive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import top.weixiansen574.hybridfilexfer.core.AbstractHFXServer;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcRequest;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.Base64Url;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.PairCallbackMessage;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.core.protocol.SessionAuthProvider;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkReadFileCall;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkWriteFileCall;
import top.weixiansen574.hybridfilexfer.pc.engine.PcEngineRuntime;
import top.weixiansen574.hybridfilexfer.pc.engine.adb.AdbManager;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.JsonRpcDispatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end loopback integration tests for M4:
 * <ol>
 *   <li>Normal HTTP callback first-time pairing -> PNG deletion -> trust persistence -> HMAC auto-connect;</li>
 *   <li>Simulated Windows Firewall blocking PC :5741 inbound -> mDNS {@code pair_ack} reverse-fallback
 *       -> PC outbound TCP HFXC v4 + HMAC connect ({@code viaFallbackMdns=true});</li>
 *   <li>Engine restart -> zero-scan mDNS auto-connect with persisted HMAC key (plus rogue PC rejection);</li>
 *   <li>QR pairing cancel &amp; TTL expiry cleanup.</li>
 * </ol>
 */
public class PairingAndAutoConnectE2ETest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final class AuthLoopbackServer extends AbstractHFXServer {
        @Override
        protected ByteBuffer createBuffer(int size) {
            return ByteBuffer.allocateDirect(size);
        }

        @Override
        protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new JdkWriteFileCall(buffers, dequeCount);
        }

        @Override
        protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                  List<RemoteFile> files,
                                                  Directory localDir,
                                                  Directory remoteDir,
                                                  int operateThreadCount) {
            return new JdkReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }
    }

    private static final class MapAuthProvider implements SessionAuthProvider {
        final Map<String, byte[]> secrets = new ConcurrentHashMap<>();
        final AtomicBoolean authSucceeded = new AtomicBoolean(false);
        final CopyOnWriteArrayList<String> rejectedClients = new CopyOnWriteArrayList<>();

        @Override
        public boolean isAuthRequired() {
            return true;
        }

        @Override
        public byte[] lookupPairSecret(String clientId) {
            return clientId != null ? secrets.get(clientId) : null;
        }

        @Override
        public void onAuthSucceeded(String clientId) {
            authSucceeded.set(true);
        }
    }

    private static final class RunningAuthMobileServer implements AutoCloseable {
        final AuthLoopbackServer server = new AuthLoopbackServer();
        final MapAuthProvider auth = new MapAuthProvider();
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch authFailed = new CountDownLatch(1);

        RunningAuthMobileServer() throws Exception {
            this(Collections.singletonList(
                    new ServerNetInterface("wlan0", InetAddress.getByName("127.0.0.1"), null, RailType.WIFI, 1)));
        }

        RunningAuthMobileServer(List<ServerNetInterface> rails) throws Exception {
            server.setPreferredBlockSize(256 * 1024);
            server.setAuthProvider(auth);
            exec.submit(() -> {
                try {
                    server.startServer(0, rails, 16, 16, new ServerHandshakeCallback() {
                        @Override public void onBindFailed(int port) {}
                        @Override public void onServerStarted() { started.countDown(); }
                        @Override public void onAuthFailed(String clientId, String reason) {
                            auth.rejectedClients.add(clientId + ":" + reason);
                            authFailed.countDown();
                        }
                        @Override public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {}
                        @Override public void onAcceptFailed(String name) {}
                        @Override public void onRemoteOOM() {}
                        @Override public void onLocalOOM(int created, int localBufferCount) {}
                        @Override public void onConnectSuccess() { connected.countDown(); }
                    });
                    if (server.isSessionActive()) {
                        server.startControlLoop(null);
                    }
                } catch (IOException ignored) {
                }
            });
            assertTrue("Mobile server must bind", started.await(5, TimeUnit.SECONDS));
        }

        int port() {
            return server.getBoundPort();
        }

        @Override
        public void close() {
            server.disconnect();
            exec.shutdownNow();
        }
    }

    private PcEngineRuntime createIsolatedRuntime(Path storeFile, Path qrDir, boolean simulateBlockedHttp) {
        PcEngineRuntime.EngineConfig cfg = new PcEngineRuntime.EngineConfig();
        cfg.trustedPeersPath = storeFile;
        cfg.qrTempDir = qrDir;
        cfg.preferredPairPort = 0; // dynamic port to prevent CI collisions
        cfg.enableMdns = false; // inject deterministic synthetic mDNS events
        cfg.autoConnectOnPair = true;
        cfg.autoConnectDiscoveryWaitMs = 1500L;
        cfg.defaultAutoSubnetBind = false; // loopback test
        cfg.simulateBlockedPairPortForTest = simulateBlockedHttp;
        cfg.overrideQrCandidateIps = Collections.singletonList("127.0.0.1");
        return new PcEngineRuntime(cfg);
    }

    @Test
    public void testDirectHttpPairingPersistenceAndRestartAutoConnectWithHmac() throws Exception {
        Path storeFile = tempFolder.newFolder("direct_store").toPath().resolve("trusted_peers.json");
        Path qrDir = tempFolder.newFolder("direct_qr").toPath();
        final String phoneId = "oneplus13-abc";
        final String phoneName = "OnePlus 13";

        String persistedPcId;
        byte[] derivedSecret;

        // ---- Phase 1: First-time QR pairing over direct HTTP callback ----
        try (RunningAuthMobileServer mobile = new RunningAuthMobileServer();
             PcEngineRuntime pc = createIsolatedRuntime(storeFile, qrDir, false)) {

            persistedPcId = pc.getDeviceId();
            JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(pc, null);

            CountDownLatch pairSucceededLatch = new CountDownLatch(1);
            CountDownLatch connectedLatch = new CountDownLatch(1);
            AtomicReference<IpcPayloads.PairSucceededPayload> pairPayloadRef = new AtomicReference<>();

            pc.addEventListener((event, payload) -> {
                if (IpcEvents.PAIR_SUCCEEDED.equals(event)) {
                    pairPayloadRef.set((IpcPayloads.PairSucceededPayload) payload);
                    pairSucceededLatch.countDown();
                } else if (IpcEvents.SESSION_STATE_CHANGED.equals(event)) {
                    IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
                    if (sc.current == SessionState.CONNECTED) {
                        connectedLatch.countDown();
                    }
                }
            });

            // 1) PC starts QR pairing via JSON-RPC
            JsonRpcResponse qrResp = dispatcher.dispatchRequest(
                    new JsonRpcRequest(new JsonPrimitive(1), IpcMethods.PAIR_START_QR, null)).response;
            assertTrue(qrResp.isSuccess());
            IpcPayloads.PairQrReadyPayload qrReady = IpcJson.fromJson(
                    qrResp.result, IpcPayloads.PairQrReadyPayload.class);
            assertNotNull(qrReady.pairUri);
            Path pngPath = Paths.get(qrReady.qrPngPath);
            assertTrue("QR PNG must exist while pairing", Files.isRegularFile(pngPath));

            // 2) Mobile scans QR, derives shared HMAC secret, pre-registers on server, sends POST /hfx/pair
            PairUriCodec.PairPayload parsed = PairUriCodec.decode(qrReady.pairUri);
            assertEquals(persistedPcId, parsed.pcId);
            derivedSecret = HmacAuth.derivePairSecret(parsed.token, parsed.pcId, phoneId);
            mobile.auth.secrets.put(parsed.pcId, derivedSecret);

            // Verify invalid HMAC proof is rejected with HTTP 403 before sending valid proof
            int badStatus = sendPairHttpCallback(parsed, phoneId, phoneName, mobile.port(), "bad-proof").statusCode;
            assertEquals(403, badStatus);
            assertTrue("PNG must remain until valid pair succeeds", Files.isRegularFile(pngPath));

            // Send valid signed callback
            HttpCallbackResult httpRes = sendPairHttpCallback(parsed, phoneId, phoneName, mobile.port(), null);
            assertEquals(200, httpRes.statusCode);
            assertTrue(httpRes.response.ok);
            assertTrue("Mobile must verify PC ackProof",
                    HmacAuth.verifyPairAckProof(parsed.token, parsed.pcId, phoneId, httpRes.nonce, httpRes.response.ackProof));

            assertTrue("pair.succeeded event must fire", pairSucceededLatch.await(5, TimeUnit.SECONDS));
            assertFalse("Direct HTTP pairing must report viaFallbackMdns=false",
                    pairPayloadRef.get().viaFallbackMdns);
            assertFalse("Temporary QR PNG must be deleted immediately upon pairing success",
                    Files.exists(pngPath));
            assertTrue("PC must auto-connect with HMAC after pairing", connectedLatch.await(5, TimeUnit.SECONDS));
            assertTrue("Mobile server must confirm HMAC verification", mobile.auth.authSucceeded.get());
            assertEquals(SessionState.CONNECTED, pc.getState());

            // Verify peers.list reflects persisted trusted phone
            JsonRpcResponse peersResp = dispatcher.dispatchRequest(
                    new JsonRpcRequest(new JsonPrimitive(2), IpcMethods.PEERS_LIST, null)).response;
            IpcPayloads.PeersListResult peers = IpcJson.fromJson(peersResp.result, IpcPayloads.PeersListResult.class);
            assertEquals(1, peers.peers.size());
            assertEquals(phoneId, peers.peers.get(0).deviceId);
        }

        // ---- Phase 2: Engine restart -> zero-scan mDNS auto-connect with persisted HMAC ----
        try (RunningAuthMobileServer mobile2 = new RunningAuthMobileServer();
             PcEngineRuntime pcRestarted = createIsolatedRuntime(storeFile, qrDir, false)) {

            assertEquals("Restarted PC engine must keep stable pcId", persistedPcId, pcRestarted.getDeviceId());
            mobile2.auth.secrets.put(persistedPcId, derivedSecret);

            // 2a) Rogue unpaired PC with unknown clientId/secret MUST be rejected by mobile HMAC
            Path rogueStore = tempFolder.newFolder("rogue_store").toPath().resolve("trusted.json");
            Path rogueQr = tempFolder.newFolder("rogue_qr").toPath();
            try (PcEngineRuntime roguePc = createIsolatedRuntime(rogueStore, rogueQr, false)) {
                IpcPayloads.SessionConnectParams rogueParams = new IpcPayloads.SessionConnectParams();
                rogueParams.mode = "direct";
                rogueParams.host = "127.0.0.1";
                rogueParams.port = mobile2.port();
                rogueParams.autoSubnetBind = false;
                boolean rogueThrew = false;
                try {
                    roguePc.connect(rogueParams);
                } catch (PcEngineRuntime.EngineException expected) {
                    rogueThrew = true;
                }
                assertTrue("Rogue unpaired PC must be rejected by HMAC", rogueThrew);
                assertTrue("Mobile must log HMAC rejection", mobile2.authFailed.await(3, TimeUnit.SECONDS));
            }

            // 2b) Restarted trusted PC discovers phone via mDNS and connects <1s via mode="auto"
            pcRestarted.getMdnsManager().handleServiceResolved(new MdnsManager.ObservedService(
                    "127.0.0.1", "HFX-op13", phoneId, phoneName,
                    Collections.singletonList("127.0.0.1"), mobile2.port(), "ncm0", null));

            IpcPayloads.SessionConnectParams autoParams = new IpcPayloads.SessionConnectParams();
            autoParams.mode = "auto";
            autoParams.autoSubnetBind = false;
            long t0 = System.currentTimeMillis();
            IpcPayloads.OkResult res = pcRestarted.connect(autoParams);
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(res.ok);
            assertEquals(SessionState.CONNECTED, pcRestarted.getState());
            assertTrue("Mobile server must accept restarted PC via HMAC", mobile2.auth.authSucceeded.get());
            assertTrue("Trusted mDNS auto-connect should be fast (<2s), took " + elapsed + "ms", elapsed < 2000L);

            // Verify peers.remove cleans up trust store
            IpcPayloads.PeerRemoveParams rm = new IpcPayloads.PeerRemoveParams();
            rm.deviceId = phoneId;
            assertTrue(pcRestarted.removeTrustedPeer(rm).ok);
            assertEquals(0, pcRestarted.listTrustedPeers().peers.size());
        }
    }

    @Test
    public void testFirewallBlockedHttpTriggersMdnsPairAckReverseFallbackAndConnects() throws Exception {
        Path storeFile = tempFolder.newFolder("fb_store").toPath().resolve("trusted_peers.json");
        Path qrDir = tempFolder.newFolder("fb_qr").toPath();
        final String phoneId = "oneplus13-fw";
        final String phoneName = "OnePlus 13 (FW)";

        try (RunningAuthMobileServer mobile = new RunningAuthMobileServer();
             PcEngineRuntime pc = createIsolatedRuntime(storeFile, qrDir, true)) {

            CountDownLatch pairSucceededLatch = new CountDownLatch(1);
            CountDownLatch connectedLatch = new CountDownLatch(1);
            AtomicReference<IpcPayloads.PairSucceededPayload> pairPayloadRef = new AtomicReference<>();

            pc.addEventListener((event, payload) -> {
                if (IpcEvents.PAIR_SUCCEEDED.equals(event)) {
                    pairPayloadRef.set((IpcPayloads.PairSucceededPayload) payload);
                    pairSucceededLatch.countDown();
                } else if (IpcEvents.SESSION_STATE_CHANGED.equals(event)) {
                    IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
                    if (sc.current == SessionState.CONNECTED) {
                        connectedLatch.countDown();
                    }
                }
            });

            // 1) PC generates QR while inbound HTTP callback port is simulated as firewall-blocked
            IpcPayloads.PairQrReadyPayload qr = pc.startQrPairing(new IpcPayloads.PairStartParams());
            Path pngPath = Paths.get(qr.qrPngPath);
            assertTrue(Files.isRegularFile(pngPath));

            PairUriCodec.PairPayload parsed = PairUriCodec.decode(qr.pairUri);
            byte[] derivedSecret = HmacAuth.derivePairSecret(parsed.token, parsed.pcId, phoneId);
            mobile.auth.secrets.put(parsed.pcId, derivedSecret);

            // 2) Confirm inbound HTTP callback actually fails (firewall blocked)
            boolean httpBlocked = false;
            try {
                sendPairHttpCallback(parsed, phoneId, phoneName, mobile.port(), null);
            } catch (IOException expected) {
                httpBlocked = true;
            }
            assertTrue("Simulated blocked inbound port must reject mobile HTTP callback", httpBlocked);

            // 3) Mobile publishes mDNS _hfx._tcp.local. with pair_ack = sha256(token)[0:16]
            String pairAck = HmacAuth.computePairAckHash(parsed.token);
            pc.getMdnsManager().handleServiceResolved(new MdnsManager.ObservedService(
                    "127.0.0.1", "HFX-fw", phoneId, phoneName,
                    Collections.singletonList("127.0.0.1"), mobile.port(), "ncm0", pairAck));

            // 4) PC captures pair_ack -> deletes PNG -> connects OUTBOUND TCP HFXC+HMAC -> emits pair.succeeded(viaFallbackMdns=true)
            assertTrue("Outbound fallback connect must succeed", connectedLatch.await(5, TimeUnit.SECONDS));
            assertTrue("pair.succeeded must fire after outbound HMAC verification",
                    pairSucceededLatch.await(5, TimeUnit.SECONDS));
            assertTrue("Must flag viaFallbackMdns=true", pairPayloadRef.get().viaFallbackMdns);
            assertEquals(phoneId, pairPayloadRef.get().peerId);
            assertFalse("Temporary QR PNG must be deleted after mDNS fallback pairing", Files.exists(pngPath));
            assertTrue("Mobile server must verify PC outbound HMAC", mobile.auth.authSucceeded.get());
            assertTrue("Trusted peer must be persisted to disk", pc.getTrustedPeerStore().isTrusted(phoneId));
        }
    }

    @Test
    public void testPairCancelAndExpiryDeleteQrPng() throws Exception {
        Path storeFile = tempFolder.newFolder("cancel_store").toPath().resolve("trusted_peers.json");
        Path qrDir = tempFolder.newFolder("cancel_qr").toPath();

        try (PcEngineRuntime pc = createIsolatedRuntime(storeFile, qrDir, false)) {
            // 1) Explicit pair.cancel removes PNG and returns to IDLE
            IpcPayloads.PairQrReadyPayload qr1 = pc.startQrPairing(new IpcPayloads.PairStartParams());
            Path png1 = Paths.get(qr1.qrPngPath);
            assertTrue(Files.isRegularFile(png1));
            assertEquals(SessionState.PAIRING, pc.getState());

            assertTrue(pc.cancelPairing().ok);
            assertFalse("PNG must be deleted on cancel", Files.exists(png1));
            assertEquals(SessionState.IDLE, pc.getState());

            // 2) Short TTL expiry emits pair.expired and removes PNG
            CountDownLatch expiredLatch = new CountDownLatch(1);
            pc.addEventListener((ev, p) -> {
                if (IpcEvents.PAIR_EXPIRED.equals(ev)) {
                    expiredLatch.countDown();
                }
            });
            IpcPayloads.PairStartParams shortTtl = new IpcPayloads.PairStartParams();
            shortTtl.ttlSeconds = 1;
            shortTtl.pngSize = 480;
            IpcPayloads.PairQrReadyPayload qr2 = pc.startQrPairing(shortTtl);
            Path png2 = Paths.get(qr2.qrPngPath);
            assertTrue(Files.isRegularFile(png2));

            assertTrue("pair.expired event must fire after TTL", expiredLatch.await(4, TimeUnit.SECONDS));
            assertFalse("PNG must be deleted on expiry", Files.exists(png2));
            assertEquals(SessionState.IDLE, pc.getState());
        }
    }

    @Test
    public void testWiredMdnsAndQrPairingWithAdbCheckedSetsUpOnDemandAdbForwardAndCleansUp() throws Exception {
        Path storeFile = tempFolder.newFolder("wired_adb_store").toPath().resolve("trusted_peers.json");
        Path qrDir = tempFolder.newFolder("wired_adb_qr").toPath();
        java.io.File fakeAdb = tempFolder.newFile("adb_wired.exe");
        final String phoneId = "oneplus13-wired-adb";
        final String phoneName = "PJZ110";

        AtomicInteger forwardAddCount = new AtomicInteger(0);
        AtomicInteger forwardRemoveCount = new AtomicInteger(0);
        AtomicReference<String> forwardedSerial = new AtomicReference<>();

        AdbManager.CommandExecutor fakeExec = (cmd, envSpec, wd, timeoutMs) -> {
            if (cmd.contains("version")) {
                return new AdbManager.CommandResult(0, "Version 36.0.0-13206524\n", "");
            }
            if (cmd.contains("devices")) {
                return new AdbManager.CommandResult(0,
                        "List of devices attached\n"
                                + "emulator-5554          device product:sdk model:sdk_gphone64 transport_id:1\n"
                                + "3B15B100P8E00000       device product:PJZ110 model:PJZ110 transport_id:3\n", "");
            }
            if (cmd.contains("host-features") || cmd.contains("features")) {
                return new AdbManager.CommandResult(0, "shell_v2,libusb,delayed_ack\n", "");
            }
            if (cmd.contains("forward") && cmd.contains("--remove")) {
                forwardRemoveCount.incrementAndGet();
                return new AdbManager.CommandResult(0, "", "");
            }
            if (cmd.contains("forward")) {
                int sIdx = cmd.indexOf("-s");
                if (sIdx >= 0 && sIdx + 1 < cmd.size()) {
                    forwardedSerial.set(cmd.get(sIdx + 1));
                }
                forwardAddCount.incrementAndGet();
                return new AdbManager.CommandResult(0, "5740\n", "");
            }
            return new AdbManager.CommandResult(0, "", "");
        };
        AdbManager.EnvironmentProvider fakeEnv = new AdbManager.EnvironmentProvider() {
            @Override public String getEnv(String name) { return null; }
            @Override public boolean isWindows() { return true; }
            @Override public String getPathSeparator() { return ";"; }
            @Override public boolean isExecutableFile(java.io.File file) {
                return file.getAbsolutePath().equals(fakeAdb.getAbsolutePath());
            }
            @Override public String canonicalizePath(java.io.File file) { return file.getAbsolutePath(); }
        };

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        List<ServerNetInterface> mobileRails = Arrays.asList(
                new ServerNetInterface("rndis0", loopback, null, RailType.USB_NIC, 1),
                new ServerNetInterface("wlan0", loopback, null, RailType.WIFI, 1),
                new ServerNetInterface(ProtocolConstants.USB_ADB_RAIL_NAME, loopback, null, RailType.ADB, 1)
        );

        PcEngineRuntime.EngineConfig cfg = new PcEngineRuntime.EngineConfig();
        cfg.trustedPeersPath = storeFile;
        cfg.qrTempDir = qrDir;
        cfg.preferredPairPort = 0;
        cfg.enableMdns = false;
        cfg.autoConnectOnPair = true;
        cfg.autoConnectDiscoveryWaitMs = 1500L;
        cfg.defaultAutoSubnetBind = false;
        cfg.enableOnDemandAdbOnLoopbackForTest = true;
        cfg.adbConfig.explicitAdbPath = fakeAdb.getAbsolutePath();
        cfg.overrideQrCandidateIps = Collections.singletonList("127.0.0.1");

        // 1) QR pairing with USB_ADB + rndis0 + wlan0 all checked on mobile
        byte[] derivedSecret;
        String pcId;
        try (RunningAuthMobileServer mobile = new RunningAuthMobileServer(mobileRails);
             PcEngineRuntime pc = new PcEngineRuntime(cfg, null, null,
                     (adbCfg, listener) -> new AdbManager(adbCfg, fakeExec, fakeEnv, listener))) {
            pcId = pc.getDeviceId();
            CountDownLatch connectedLatch = new CountDownLatch(1);
            pc.addEventListener((ev, payload) -> {
                if (IpcEvents.SESSION_STATE_CHANGED.equals(ev)
                        && ((IpcPayloads.SessionStateChangedPayload) payload).current == SessionState.CONNECTED) {
                    connectedLatch.countDown();
                }
            });

            IpcPayloads.PairQrReadyPayload qr = pc.startQrPairing(new IpcPayloads.PairStartParams());
            PairUriCodec.PairPayload parsed = PairUriCodec.decode(qr.pairUri);
            derivedSecret = HmacAuth.derivePairSecret(parsed.token, parsed.pcId, phoneId);
            mobile.auth.secrets.put(parsed.pcId, derivedSecret);

            HttpCallbackResult httpRes = sendPairHttpCallback(parsed, phoneId, phoneName, mobile.port(), null);
            assertEquals(200, httpRes.statusCode);
            assertTrue("QR auto-connect with USB_ADB + rndis0 + wlan0 must succeed",
                    connectedLatch.await(5, TimeUnit.SECONDS));
            assertEquals(SessionState.CONNECTED, pc.getState());
            assertEquals("Must lazily invoke adb forward for USB_ADB rail", 1, forwardAddCount.get());
            assertEquals("Must match wired phone serial by model PJZ110", "3B15B100P8E00000", forwardedSerial.get());
            assertEquals(Arrays.asList("rndis0", "wlan0", ProtocolConstants.USB_ADB_RAIL_NAME),
                    mobile.server.getDistinctRailNames());

            pc.disconnect();
            assertEquals("Disconnect must clean up lazily created adb forward", 1, forwardRemoveCount.get());
        }

        // 2) Restarted mDNS auto-connect with USB_ADB + rndis0 + wlan0 all checked on mobile
        try (RunningAuthMobileServer mobile2 = new RunningAuthMobileServer(mobileRails);
             PcEngineRuntime pc2 = new PcEngineRuntime(cfg, null, null,
                     (adbCfg, listener) -> new AdbManager(adbCfg, fakeExec, fakeEnv, listener))) {
            mobile2.auth.secrets.put(pcId, derivedSecret);
            pc2.getMdnsManager().handleServiceResolved(new MdnsManager.ObservedService(
                    "127.0.0.1", "HFX-op13", phoneId, phoneName,
                    Collections.singletonList("127.0.0.1"), mobile2.port(), "rndis0", null));

            IpcPayloads.SessionConnectParams autoParams = new IpcPayloads.SessionConnectParams();
            autoParams.mode = "auto";
            autoParams.autoSubnetBind = false;
            assertTrue("mDNS auto-connect with USB_ADB checked must succeed", pc2.connect(autoParams).ok);
            assertEquals(SessionState.CONNECTED, pc2.getState());
            assertEquals(2, forwardAddCount.get());
            assertEquals(Arrays.asList("rndis0", "wlan0", ProtocolConstants.USB_ADB_RAIL_NAME),
                    mobile2.server.getDistinctRailNames());

            pc2.disconnect();
            assertEquals(2, forwardRemoveCount.get());
        }
    }

    @Test
    public void testMdnsAutoConnectWithAdbCheckedFallsBackToRndisAndWifiWhenAdbUnavailable() throws Exception {
        Path storeFile = tempFolder.newFolder("fallback_adb_store").toPath().resolve("trusted_peers.json");
        Path qrDir = tempFolder.newFolder("fallback_adb_qr").toPath();
        java.io.File fakeAdb = tempFolder.newFile("adb_empty.exe");
        final String phoneId = "oneplus13-no-adb";
        final String phoneName = "PJZ110";

        AdbManager.CommandExecutor noDeviceExec = (cmd, envSpec, wd, timeoutMs) -> {
            if (cmd.contains("version")) {
                return new AdbManager.CommandResult(0, "Version 36.0.0-13206524\n", "");
            }
            if (cmd.contains("devices")) {
                return new AdbManager.CommandResult(0, "List of devices attached\n\n", "");
            }
            return new AdbManager.CommandResult(1, "", "error: no devices/emulators found");
        };
        AdbManager.EnvironmentProvider fakeEnv = new AdbManager.EnvironmentProvider() {
            @Override public String getEnv(String name) { return null; }
            @Override public boolean isWindows() { return true; }
            @Override public String getPathSeparator() { return ";"; }
            @Override public boolean isExecutableFile(java.io.File file) {
                return file.getAbsolutePath().equals(fakeAdb.getAbsolutePath());
            }
            @Override public String canonicalizePath(java.io.File file) { return file.getAbsolutePath(); }
        };

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        List<ServerNetInterface> mobileRails = Arrays.asList(
                new ServerNetInterface("rndis0", loopback, null, RailType.USB_NIC, 1),
                new ServerNetInterface("wlan0", loopback, null, RailType.WIFI, 1),
                new ServerNetInterface(ProtocolConstants.USB_ADB_RAIL_NAME, loopback, null, RailType.ADB, 1)
        );

        PcEngineRuntime.EngineConfig cfg = new PcEngineRuntime.EngineConfig();
        cfg.trustedPeersPath = storeFile;
        cfg.qrTempDir = qrDir;
        cfg.preferredPairPort = 0;
        cfg.enableMdns = false;
        cfg.autoConnectDiscoveryWaitMs = 1500L;
        cfg.defaultAutoSubnetBind = false;
        cfg.enableOnDemandAdbOnLoopbackForTest = true;
        cfg.adbConfig.explicitAdbPath = fakeAdb.getAbsolutePath();

        try (RunningAuthMobileServer mobile = new RunningAuthMobileServer(mobileRails);
             PcEngineRuntime pc = new PcEngineRuntime(cfg, null, null,
                     (adbCfg, listener) -> new AdbManager(adbCfg, noDeviceExec, fakeEnv, listener))) {
            byte[] secret = HmacAuth.generateRandomBytes(32);
            pc.getTrustedPeerStore().upsertPeer(phoneId, phoneName, secret,
                    Collections.singletonList("127.0.0.1"), mobile.port(), "rndis0");
            mobile.auth.secrets.put(pc.getDeviceId(), secret);

            CopyOnWriteArrayList<String> warningCodes = new CopyOnWriteArrayList<>();
            pc.addEventListener((ev, payload) -> {
                if (IpcEvents.DIAGNOSTIC_WARNING.equals(ev)
                        && payload instanceof IpcPayloads.DiagnosticWarningPayload) {
                    warningCodes.add(((IpcPayloads.DiagnosticWarningPayload) payload).code);
                }
            });

            pc.getMdnsManager().handleServiceResolved(new MdnsManager.ObservedService(
                    "127.0.0.1", "HFX-no-adb", phoneId, phoneName,
                    Collections.singletonList("127.0.0.1"), mobile.port(), "rndis0", null));

            IpcPayloads.SessionConnectParams autoParams = new IpcPayloads.SessionConnectParams();
            autoParams.mode = "auto";
            autoParams.autoSubnetBind = false;
            assertTrue("Must succeed via automatic CAP_EXCLUDE_ADB_RAIL fallback", pc.connect(autoParams).ok);
            assertEquals(SessionState.CONNECTED, pc.getState());
            assertTrue("Must emit ADB_RAIL_SKIPPED_FALLBACK warning",
                    warningCodes.contains("ADB_RAIL_SKIPPED_FALLBACK"));
            assertEquals(Arrays.asList("rndis0", "wlan0"), mobile.server.getDistinctRailNames());

            // Also verify MdnsManager ranks real IP ahead of loopback 127.0.0.1
            List<String> ranked = pc.getMdnsManager().rankPeerIps(Arrays.asList("127.0.0.1", "10.94.81.247"));
            assertEquals("10.94.81.247", ranked.get(0));
        }
    }

    private static final class HttpCallbackResult {
        final int statusCode;
        final PairCallbackMessage.Response response;
        final String nonce;

        HttpCallbackResult(int statusCode, PairCallbackMessage.Response response, String nonce) {
            this.statusCode = statusCode;
            this.response = response;
            this.nonce = nonce;
        }
    }

    private static HttpCallbackResult sendPairHttpCallback(PairUriCodec.PairPayload parsed,
                                                           String phoneId,
                                                           String phoneName,
                                                           int phonePort,
                                                           String overrideProof) throws IOException {
        String nonce = Base64Url.encode(HmacAuth.generateRandomBytes(16));
        PairCallbackMessage.Request req = new PairCallbackMessage.Request();
        req.version = 1;
        req.deviceId = phoneId;
        req.deviceName = phoneName;
        req.port = phonePort;
        req.ips = Collections.singletonList("127.0.0.1");
        req.usbType = "ncm0";
        req.nonce = nonce;
        req.proof = overrideProof != null
                ? overrideProof
                : HmacAuth.computePairProof(parsed.token, parsed.pcId, phoneId, phonePort, nonce);

        byte[] body = IpcJson.toJson(req).getBytes(StandardCharsets.UTF_8);
        URL url = new URL("http://127.0.0.1:" + parsed.port + ProtocolConstants.PAIR_HTTP_PATH);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody = "";
        if (in != null) {
            try (InputStream s = in) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = s.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                respBody = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        conn.disconnect();
        PairCallbackMessage.Response parsedResp = respBody.isEmpty()
                ? null : IpcJson.fromJson(respBody, PairCallbackMessage.Response.class);
        return new HttpCallbackResult(code, parsedResp, nonce);
    }
}
