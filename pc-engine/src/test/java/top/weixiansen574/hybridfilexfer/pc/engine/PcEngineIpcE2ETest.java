package top.weixiansen574.hybridfilexfer.pc.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcError;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcNotification;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcRequest;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkReadFileCall;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkWriteFileCall;
import top.weixiansen574.hybridfilexfer.pc.engine.adb.AdbManager;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.JsonRpcDispatcher;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.LocalSocketIpcTransport;
import top.weixiansen574.hybridfilexfer.pc.engine.ipc.StdioIpcTransport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure headless IPC end-to-end integration tests for M3:
 * drives {@link PcEngineRuntime} exclusively via JSON-RPC 2.0 over both
 * {@link StdioIpcTransport} and {@link LocalSocketIpcTransport} (TCP loopback + token & AF_UNIX)
 * against a live loopback {@link AbstractHFXServer}.
 */
public class PcEngineIpcE2ETest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final class LoopbackHfxServer extends AbstractHFXServer {
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

    /**
     * Minimal NDJSON JSON-RPC test client that separates responses from server-push notifications.
     * Supports both plain streams (stdio pipe) and full-duplex {@link SocketChannel} (TCP / AF_UNIX).
     */
    private static final class NdjsonRpcClient implements AutoCloseable {
        private final InputStream rawIn;
        private final OutputStream rawOut;
        private final BufferedWriter streamWriter;
        private final BufferedReader streamReader;
        private final SocketChannel socketChannel;
        private final Object socketWriteLock = new Object();
        private final BlockingQueue<JsonRpcResponse> responses = new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();
        private final BlockingQueue<JsonRpcNotification> notifQueue = new LinkedBlockingQueue<>();
        private final AtomicInteger idGen = new AtomicInteger(1);
        private final Thread readerThread;
        private volatile boolean closed = false;
        private volatile IOException readError;

        NdjsonRpcClient(InputStream in, OutputStream out) {
            this.rawIn = in;
            this.rawOut = out;
            this.socketChannel = null;
            this.streamReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            this.streamWriter = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::readLoop, "ipc-test-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        NdjsonRpcClient(SocketChannel channel) {
            this.rawIn = null;
            this.rawOut = null;
            this.socketChannel = channel;
            this.streamReader = null;
            this.streamWriter = null;
            this.readerThread = new Thread(this::readLoop, "ipc-test-sock-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        private void readLoop() {
            try {
                LocalSocketIpcTransport.ChannelLineReader sockReader = socketChannel != null
                        ? new LocalSocketIpcTransport.ChannelLineReader(socketChannel)
                        : null;
                String line;
                while (!closed && (line = (sockReader != null ? sockReader.readLine() : streamReader.readLine())) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    JsonObject obj = IpcJson.parse(trimmed).getAsJsonObject();
                    if (obj.has("id") && !obj.get("id").isJsonNull()) {
                        responses.add(IpcJson.fromJson(obj, JsonRpcResponse.class));
                    } else if (obj.has("method")) {
                        JsonRpcNotification n = IpcJson.fromJson(obj, JsonRpcNotification.class);
                        notifications.add(n);
                        notifQueue.add(n);
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    readError = e;
                }
            }
        }

        IOException getReadError() {
            return readError;
        }

        JsonRpcResponse call(String method, Object paramsObj, String token) throws Exception {
            JsonRpcRequest req = new JsonRpcRequest();
            req.id = new JsonPrimitive(idGen.getAndIncrement());
            req.method = method;
            req.params = paramsObj != null ? IpcJson.toJsonObject(paramsObj) : new JsonObject();
            req.token = token;
            String encoded = IpcJson.toJson(req);
            if (socketChannel != null) {
                LocalSocketIpcTransport.writeNdjsonLine(socketChannel, socketWriteLock, encoded);
            } else {
                streamWriter.write(encoded);
                streamWriter.newLine();
                streamWriter.flush();
            }

            JsonRpcResponse resp = responses.poll(15, TimeUnit.SECONDS);
            assertNotNull("Timed out waiting for JSON-RPC response to " + method, resp);
            return resp;
        }

        JsonRpcNotification awaitNotification(String eventName, long timeoutSeconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                JsonRpcNotification n = notifQueue.poll(remaining, TimeUnit.MILLISECONDS);
                if (n != null && eventName.equals(n.method)) {
                    return n;
                }
            }
            return null;
        }

        List<JsonRpcNotification> getNotificationsByName(String eventName) {
            List<JsonRpcNotification> out = new ArrayList<>();
            for (JsonRpcNotification n : notifications) {
                if (eventName.equals(n.method)) {
                    out.add(n);
                }
            }
            return out;
        }

        @Override
        public void close() {
            closed = true;
            readerThread.interrupt();
            if (rawOut != null) {
                try {
                    rawOut.close();
                } catch (IOException ignored) {
                }
            }
            if (rawIn != null) {
                try {
                    rawIn.close();
                } catch (IOException ignored) {
                }
            }
            if (socketChannel != null) {
                try {
                    socketChannel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class RunningServer implements AutoCloseable {
        final LoopbackHfxServer server = new LoopbackHfxServer();
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch loopStarted = new CountDownLatch(1);
        final Future<?> future;

        RunningServer() throws Exception {
            server.setPreferredBlockSize(256 * 1024);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            List<ServerNetInterface> rails = Collections.singletonList(
                    new ServerNetInterface("USB_ADB", loopback, null, RailType.ADB, 2));
            future = exec.submit(() -> {
                try {
                    server.startServer(0, rails, 16, 16, new ServerHandshakeCallback() {
                        @Override public void onBindFailed(int port) {}
                        @Override public void onServerStarted() { started.countDown(); }
                        @Override public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {}
                        @Override public void onAcceptFailed(String name) {}
                        @Override public void onRemoteOOM() {}
                        @Override public void onLocalOOM(int created, int localBufferCount) {}
                        @Override public void onConnectSuccess() { connected.countDown(); }
                    });
                    if (server.isSessionActive()) {
                        server.startControlLoop(null, loopStarted::countDown);
                    }
                } catch (IOException ignored) {
                } finally {
                    loopStarted.countDown();
                }
            });
            assertTrue("Loopback server failed to bind", started.await(5, TimeUnit.SECONDS));
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

    @Test
    public void testStdioIpcEndToEndConnectBrowseBidirectionalTransferAndSha256() throws Exception {
        File pcRoot = tempFolder.newFolder("pc_stdio");
        File serverRoot = tempFolder.newFolder("server_stdio");
        File pcDownloadDir = tempFolder.newFolder("pc_stdio_dl");

        File uploadFile = new File(pcRoot, "payload_2mb.bin");
        writeRandomFile(uploadFile, 2 * 1024 * 1024 + 4321, 1001L);

        try (RunningServer srv = new RunningServer();
             PcEngineRuntime runtime = new PcEngineRuntime()) {

            JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(runtime, null);
            // Use OS-backed NIO Pipe instead of java.io.PipedInputStream/PipedOutputStream:
            // PipedInputStream binds to the last writer thread and throws "Write end dead"
            // when short-lived transfer/SpeedMonitor worker threads emit transfer.progress and exit.
            Pipe clientToServerPipe = Pipe.open();
            Pipe serverToClientPipe = Pipe.open();
            OutputStream clientToServerOut = Channels.newOutputStream(clientToServerPipe.sink());
            InputStream serverReadIn = Channels.newInputStream(clientToServerPipe.source());
            OutputStream serverWriteOut = Channels.newOutputStream(serverToClientPipe.sink());
            InputStream clientFromServerIn = Channels.newInputStream(serverToClientPipe.source());

            StdioIpcTransport transport = new StdioIpcTransport(dispatcher, serverReadIn, serverWriteOut);
            runtime.setShutdownHandler(transport::close);

            ExecutorService transportExec = Executors.newSingleThreadExecutor();
            Future<?> transportFuture = transportExec.submit(() -> {
                try {
                    transport.runBlocking();
                } catch (IOException ignored) {
                }
            });

            try (NdjsonRpcClient client = new NdjsonRpcClient(clientFromServerIn, clientToServerOut)) {
                runFullHeadlessWorkflow(client, null, srv, pcRoot, serverRoot, pcDownloadDir, uploadFile);
            } finally {
                try {
                    serverWriteOut.close();
                } catch (IOException ignored) {
                }
                try {
                    serverReadIn.close();
                } catch (IOException ignored) {
                }
                transportFuture.get(5, TimeUnit.SECONDS);
                transportExec.shutdownNow();
            }
        }
    }

    @Test
    public void testLocalSocketTcpIpcWithTokenAuthMultiSubscriberAndBidirectionalTransfer() throws Exception {
        File pcRoot = tempFolder.newFolder("pc_tcp");
        File serverRoot = tempFolder.newFolder("server_tcp");
        File pcDownloadDir = tempFolder.newFolder("pc_tcp_dl");
        Path ipcInfoPath = tempFolder.newFolder("ipc_meta").toPath().resolve("engine.ipc.json");

        File uploadFile = new File(pcRoot, "tcp_payload_2mb.bin");
        writeRandomFile(uploadFile, 2 * 1024 * 1024 + 9876, 2002L);

        String token = LocalSocketIpcTransport.generateRandomToken();
        try (RunningServer srv = new RunningServer();
             PcEngineRuntime runtime = new PcEngineRuntime()) {

            JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(runtime, token);
            LocalSocketIpcTransport transport = new LocalSocketIpcTransport(
                    dispatcher, "tcp://127.0.0.1:0", token, ipcInfoPath);
            transport.start();

            // Verify engine.ipc.json written atomically with endpoint, pid, and token
            assertTrue("engine.ipc.json must exist", Files.exists(ipcInfoPath));
            LocalSocketIpcTransport.IpcEndpointInfo meta = IpcJson.fromJson(
                    Files.readString(ipcInfoPath, StandardCharsets.UTF_8),
                    LocalSocketIpcTransport.IpcEndpointInfo.class);
            assertEquals("tcp", meta.transport);
            assertEquals(token, meta.ipcToken);
            assertEquals(ProtocolConstants.VERSION_CODE, meta.protocolVersion);
            assertTrue(meta.pid > 0);
            int port = transport.getBoundTcpPort();
            assertTrue(port > 0);
            assertEquals("tcp://127.0.0.1:" + port, meta.endpoint);

            try (SocketChannel sc1 = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel sc2 = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 NdjsonRpcClient controller = new NdjsonRpcClient(sc1);
                 NdjsonRpcClient observer = new NdjsonRpcClient(sc2)) {

                // 1) Missing token & wrong token MUST be rejected with UNAUTHORIZED (-32001)
                JsonRpcResponse noTokenResp = controller.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, null);
                assertFalse(noTokenResp.isSuccess());
                assertEquals(JsonRpcError.UNAUTHORIZED, noTokenResp.error.code);

                JsonRpcResponse wrongTokenResp = controller.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, "wrong-token");
                assertFalse(wrongTokenResp.isSuccess());
                assertEquals(JsonRpcError.UNAUTHORIZED, wrongTokenResp.error.code);

                // 2) Full workflow with valid token
                runFullHeadlessWorkflow(controller, token, srv, pcRoot, serverRoot, pcDownloadDir, uploadFile);

                // 3) Verify multi-subscriber event broadcast reached observer client as well
                JsonRpcNotification obsCompleted = observer.awaitNotification(IpcEvents.TRANSFER_COMPLETED, 5);
                assertNotNull("Second subscriber must receive broadcast transfer.completed event", obsCompleted);
                assertFalse("Second subscriber must receive session.state_changed events",
                        observer.getNotificationsByName(IpcEvents.SESSION_STATE_CHANGED).isEmpty());
            }

            transport.awaitTermination();
            assertFalse("engine.ipc.json must be cleaned up on shutdown", Files.exists(ipcInfoPath));
        }
    }

    @Test
    public void testLocalSocketUnixDomainIpcAndCleanup() throws Exception {
        Path unixDir = tempFolder.newFolder("unix_ipc").toPath();
        Path sockPath = unixDir.resolve("hfx_engine_test.sock");
        Path ipcInfoPath = unixDir.resolve("engine.ipc.json");
        String token = LocalSocketIpcTransport.generateRandomToken();

        try (PcEngineRuntime runtime = new PcEngineRuntime()) {
            JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(runtime, token);
            LocalSocketIpcTransport transport = new LocalSocketIpcTransport(
                    dispatcher, "unix://" + sockPath.toAbsolutePath(), token, ipcInfoPath);
            transport.start();

            assertTrue(Files.exists(ipcInfoPath));
            assertTrue(Files.exists(sockPath));

            try (SocketChannel clientCh = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                clientCh.connect(UnixDomainSocketAddress.of(sockPath));
                try (NdjsonRpcClient rpc = new NdjsonRpcClient(clientCh)) {
                    JsonRpcResponse unauth = rpc.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, "bad");
                    assertFalse(unauth.isSuccess());
                    assertEquals(JsonRpcError.UNAUTHORIZED, unauth.error.code);

                    JsonRpcResponse snapResp = rpc.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, token);
                    assertTrue(snapResp.isSuccess());
                    IpcDtos.EngineSnapshot snap = IpcJson.fromJson(snapResp.result, IpcDtos.EngineSnapshot.class);
                    assertEquals("pc", snap.engineRole);
                    assertEquals(SessionState.IDLE, snap.state);

                    JsonRpcResponse shutResp = rpc.call(IpcMethods.ENGINE_SHUTDOWN, null, token);
                    assertTrue(shutResp.isSuccess());
                }
            }

            transport.awaitTermination();
            transport.close();
            assertFalse("engine.ipc.json must be removed after unix shutdown", Files.exists(ipcInfoPath));
            assertFalse("unix socket file must be removed after shutdown", Files.exists(sockPath));
        }
    }

    @Test
    public void testOutdatedAdbEmitsVersionWarningEventOverIpc() throws Exception {
        File fakeAdb = tempFolder.newFile("adb.exe");
        AdbManager.CommandExecutor fakeExec = (cmd, envSpec, wd, timeoutMs) -> {
            if (cmd.contains("version")) {
                return new AdbManager.CommandResult(0,
                        "Android Debug Bridge version 1.0.41\nVersion 34.0.5-10900879\n", "");
            }
            if (cmd.contains("host-features") || cmd.contains("features")) {
                return new AdbManager.CommandResult(0, "shell_v2,sendrecv_v2\n", "");
            }
            if (cmd.contains("forward") && !cmd.contains("--remove")) {
                return new AdbManager.CommandResult(1, "", "error: no devices/emulators found");
            }
            return new AdbManager.CommandResult(0, "", "");
        };
        AdbManager.EnvironmentProvider fakeEnv = new AdbManager.EnvironmentProvider() {
            @Override public String getEnv(String name) { return null; }
            @Override public boolean isWindows() { return true; }
            @Override public String getPathSeparator() { return ";"; }
            @Override public boolean isExecutableFile(File file) {
                return file.getAbsolutePath().equals(fakeAdb.getAbsolutePath());
            }
            @Override public String canonicalizePath(File file) { return file.getAbsolutePath(); }
        };

        PcEngineRuntime.EngineConfig cfg = new PcEngineRuntime.EngineConfig();
        cfg.adbConfig.explicitAdbPath = fakeAdb.getAbsolutePath();

        CopyOnWriteArrayList<String> emittedEvents = new CopyOnWriteArrayList<>();
        try (PcEngineRuntime runtime = new PcEngineRuntime(
                cfg, null, null,
                (adbCfg, listener) -> new AdbManager(adbCfg, fakeExec, fakeEnv, listener))) {
            runtime.addEventListener((eventName, payload) -> emittedEvents.add(eventName));
            JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(runtime, null);

            IpcPayloads.SessionConnectParams params = new IpcPayloads.SessionConnectParams();
            params.mode = "adb";
            JsonRpcRequest req = new JsonRpcRequest(
                    new JsonPrimitive(1), IpcMethods.SESSION_CONNECT, IpcJson.toJsonObject(params));
            JsonRpcResponse resp = dispatcher.dispatchRequest(req).response;
            assertFalse("Expected connect failure when no device attached", resp.isSuccess());
            assertEquals(JsonRpcError.CONNECTION_FAILED, resp.error.code);
            assertTrue("Expected adb.version_warning event for adb 34.0.5",
                    emittedEvents.contains(IpcEvents.ADB_VERSION_WARNING));
            assertTrue("Expected session.state_changed events",
                    emittedEvents.contains(IpcEvents.SESSION_STATE_CHANGED));
            assertEquals("34.0.5-10900879", runtime.getSnapshot().adb.version);
            assertEquals(4, runtime.getSnapshot().adb.recommendedStreams);
        }
    }

    private void runFullHeadlessWorkflow(NdjsonRpcClient client,
                                         String token,
                                         RunningServer srv,
                                         File pcRoot,
                                         File serverRoot,
                                         File pcDownloadDir,
                                         File uploadFile) throws Exception {
        // 1) engine.get_snapshot
        JsonRpcResponse snapResp = client.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, token);
        assertTrue(snapResp.isSuccess());
        IpcDtos.EngineSnapshot snap = IpcJson.fromJson(snapResp.result, IpcDtos.EngineSnapshot.class);
        assertEquals("pc", snap.engineRole);
        assertEquals(ProtocolConstants.VERSION_CODE, snap.protocolVersion);
        assertEquals(SessionState.IDLE, snap.state);

        // 2) engine.refresh_nics
        JsonRpcResponse nicsResp = client.call(IpcMethods.ENGINE_REFRESH_NICS, null, token);
        assertTrue(nicsResp.isSuccess());
        IpcPayloads.NicsRefreshResult nicsResult = IpcJson.fromJson(nicsResp.result, IpcPayloads.NicsRefreshResult.class);
        assertNotNull(nicsResult.nics);

        // 3) session.connect
        IpcPayloads.SessionConnectParams connParams = new IpcPayloads.SessionConnectParams();
        connParams.mode = "direct";
        connParams.host = "127.0.0.1";
        connParams.port = srv.port();
        connParams.homeDir = pcRoot.getAbsolutePath();
        connParams.autoSubnetBind = false;
        JsonRpcResponse connResp = client.call(IpcMethods.SESSION_CONNECT, connParams, token);
        assertTrue("session.connect must succeed: " + (connResp.error != null ? connResp.error.message : ""),
                connResp.isSuccess());
        assertTrue("Server did not finish connect", srv.connected.await(5, TimeUnit.SECONDS));
        assertTrue("Server control loop did not start", srv.loopStarted.await(5, TimeUnit.SECONDS));

        IpcDtos.EngineSnapshot connectedSnap = IpcJson.fromJson(
                client.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, token).result,
                IpcDtos.EngineSnapshot.class);
        assertEquals(SessionState.CONNECTED, connectedSnap.state);
        assertFalse(connectedSnap.rails.isEmpty());

        // 4) fs.mkdir (remote) & fs.list (remote + local)
        IpcPayloads.FsMkdirParams mkParams = new IpcPayloads.FsMkdirParams();
        mkParams.side = "remote";
        mkParams.parent = serverRoot.getAbsolutePath();
        mkParams.child = "uploaded_box";
        JsonRpcResponse mkResp = client.call(IpcMethods.FS_MKDIR, mkParams, token);
        assertTrue(mkResp.isSuccess());
        File serverSubDir = new File(serverRoot, "uploaded_box");
        assertTrue(serverSubDir.isDirectory());

        IpcPayloads.FsListParams listRemote = new IpcPayloads.FsListParams();
        listRemote.side = "remote";
        listRemote.path = serverRoot.getAbsolutePath();
        JsonRpcResponse listResp = client.call(IpcMethods.FS_LIST, listRemote, token);
        assertTrue(listResp.isSuccess());
        IpcPayloads.FsListResult listRes = IpcJson.fromJson(listResp.result, IpcPayloads.FsListResult.class);
        assertTrue(listRes.entries.stream().anyMatch(e -> "uploaded_box".equals(e.name) && e.directory));

        IpcPayloads.FsListParams listLocal = new IpcPayloads.FsListParams();
        listLocal.side = "local";
        listLocal.path = pcRoot.getAbsolutePath();
        JsonRpcResponse localListResp = client.call(IpcMethods.FS_LIST, listLocal, token);
        assertTrue(localListResp.isSuccess());
        IpcPayloads.FsListResult localRes = IpcJson.fromJson(localListResp.result, IpcPayloads.FsListResult.class);
        assertTrue(localRes.entries.stream().anyMatch(e -> uploadFile.getName().equals(e.name)));

        // 5) transfer.start (UPLOAD: PC -> Server) + progress & completed events + SHA-256
        IpcPayloads.TransferStartParams upParams = new IpcPayloads.TransferStartParams();
        upParams.direction = "upload";
        upParams.files = Collections.singletonList(uploadFile.getAbsolutePath());
        upParams.srcDir = pcRoot.getAbsolutePath();
        upParams.dstDir = serverSubDir.getAbsolutePath();
        JsonRpcResponse upResp = client.call(IpcMethods.TRANSFER_START, upParams, token);
        assertTrue("upload transfer.start must succeed", upResp.isSuccess());

        JsonRpcNotification upDone = client.awaitNotification(IpcEvents.TRANSFER_COMPLETED, 15);
        assertNotNull("Expected transfer.completed for upload (readError=" + client.getReadError() + ")", upDone);
        IpcPayloads.TransferCompletedPayload upDonePayload = IpcJson.fromJson(
                upDone.params, IpcPayloads.TransferCompletedPayload.class);
        assertEquals("upload", upDonePayload.direction);
        assertEquals(uploadFile.length(), upDonePayload.totalBytes);
        assertFalse("Expected at least one transfer.progress event during upload",
                client.getNotificationsByName(IpcEvents.TRANSFER_PROGRESS).isEmpty());

        File uploadedOnServer = new File(serverSubDir, uploadFile.getName());
        assertFileSha256Equals(uploadFile, uploadedOnServer);

        // Wait briefly for state transition back to CONNECTED
        awaitConnectedState(client, token);

        // 6) transfer.start (DOWNLOAD: Server -> PC) + progress & completed events + SHA-256
        int progressBeforeDownload = client.getNotificationsByName(IpcEvents.TRANSFER_PROGRESS).size();
        IpcPayloads.TransferStartParams dnParams = new IpcPayloads.TransferStartParams();
        dnParams.direction = "download";
        dnParams.files = Collections.singletonList(uploadedOnServer.getAbsolutePath());
        dnParams.srcDir = serverSubDir.getAbsolutePath();
        dnParams.dstDir = pcDownloadDir.getAbsolutePath();
        JsonRpcResponse dnResp = client.call(IpcMethods.TRANSFER_START, dnParams, token);
        assertTrue("download transfer.start must succeed", dnResp.isSuccess());

        JsonRpcNotification dnDone = client.awaitNotification(IpcEvents.TRANSFER_COMPLETED, 15);
        assertNotNull("Expected transfer.completed for download (readError=" + client.getReadError() + ")", dnDone);
        IpcPayloads.TransferCompletedPayload dnDonePayload = IpcJson.fromJson(
                dnDone.params, IpcPayloads.TransferCompletedPayload.class);
        assertEquals("download", dnDonePayload.direction);
        assertEquals(uploadFile.length(), dnDonePayload.totalBytes);
        assertTrue("Expected transfer.progress event during download",
                client.getNotificationsByName(IpcEvents.TRANSFER_PROGRESS).size() > progressBeforeDownload);

        File downloadedOnPc = new File(pcDownloadDir, uploadFile.getName());
        assertFileSha256Equals(uploadFile, downloadedOnPc);

        awaitConnectedState(client, token);

        // 7) fs.delete (remote)
        IpcPayloads.FsDeleteParams delParams = new IpcPayloads.FsDeleteParams();
        delParams.side = "remote";
        delParams.path = uploadedOnServer.getAbsolutePath();
        JsonRpcResponse delResp = client.call(IpcMethods.FS_DELETE, delParams, token);
        assertTrue(delResp.isSuccess());
        assertFalse(uploadedOnServer.exists());

        // 8) session.disconnect & engine.shutdown
        JsonRpcResponse discResp = client.call(IpcMethods.SESSION_DISCONNECT, null, token);
        assertTrue(discResp.isSuccess());
        IpcDtos.EngineSnapshot idleSnap = IpcJson.fromJson(
                client.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, token).result,
                IpcDtos.EngineSnapshot.class);
        assertEquals(SessionState.IDLE, idleSnap.state);

        JsonRpcResponse shutResp = client.call(IpcMethods.ENGINE_SHUTDOWN, null, token);
        assertTrue(shutResp.isSuccess());
        JsonElement okEl = shutResp.result.getAsJsonObject().get("ok");
        assertTrue(okEl != null && okEl.getAsBoolean());
    }

    private static void awaitConnectedState(NdjsonRpcClient client, String token) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            IpcDtos.EngineSnapshot snap = IpcJson.fromJson(
                    client.call(IpcMethods.ENGINE_GET_SNAPSHOT, null, token).result,
                    IpcDtos.EngineSnapshot.class);
            if (snap.state == SessionState.CONNECTED) {
                return;
            }
            Thread.sleep(25L);
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
}
