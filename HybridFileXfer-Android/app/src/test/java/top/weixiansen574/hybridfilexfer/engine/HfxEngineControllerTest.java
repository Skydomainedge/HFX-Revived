package top.weixiansen574.hybridfilexfer.engine;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcError;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.listadapter.RemoteFileSelectAdapter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit & architecture verification suite for M3 Android headless engine layer:
 * <ul>
 *   <li>Verifies {@link HfxEngineController} state machine, command dispatch, and loopback bidirectional transfer;</li>
 *   <li>Verifies permanent removal of static {@code HFXServer.instance} across source & compiled classes;</li>
 *   <li>Verifies Android {@code engine/} package has zero UI / {@code PrintStream} bytecode references;</li>
 *   <li>Verifies PC file panel default initial directory is drive root {@code "/"}.</li>
 * </ul>
 */
public class HfxEngineControllerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final class JvmWriteFileCall extends WriteFileCall {
        JvmWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            super(buffers, dequeCount);
        }

        @Override
        protected void createParentDirIfNotExists(String path) {
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

    private static final class JvmReadFileCall extends ReadFileCall {
        private RandomAccessFile raf;
        private FileChannel ch;

        JvmReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
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

    private static final class LoopbackServer extends AbstractHFXServer {
        @Override protected ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new JvmWriteFileCall(buffers, dequeCount);
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new JvmReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }
    }

    private static final class LoopbackClient extends HFXClient {
        LoopbackClient(String host, int port, String homeDir) {
            super(host, port, homeDir);
        }

        @Override public ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override public long getAvailableMemoryMB() { return 512; }
        @Override protected boolean deleteLocalFile(String path) { return new File(path).delete(); }
        @Override protected boolean mkdir(String parent, String child) { return new File(parent, child).mkdirs(); }
        @Override protected List<RemoteFile> listFiles(String path) { return Utils.listRemoteFiles(path); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
            return new JvmWriteFileCall(buffers, dequeCount);
        }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                            List<RemoteFile> files, Directory localDir,
                                                            Directory remoteDir, int operateThreadCount) {
            return new JvmReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
        }
    }

    @Test
    public void testControllerStateMachineCommandDispatchAndLoopbackTransfer() throws Exception {
        final File phoneRoot = tempFolder.newFolder("phone_fs");
        final File pcRoot = tempFolder.newFolder("pc_fs");
        final File phoneDownloadDir = tempFolder.newFolder("phone_dl");

        File phoneFile = new File(phoneRoot, "mobile_video_1mb.bin");
        writeRandomFile(phoneFile, 1024 * 1024 + 5432, 3003L);

        final LoopbackServer[] createdServer = new LoopbackServer[1];
        final CountDownLatch serverBoundLatch = new CountDownLatch(1);
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final CountDownLatch uploadCompletedLatch = new CountDownLatch(1);
        final CountDownLatch downloadCompletedLatch = new CountDownLatch(1);

        HfxEngineController.ServerSessionBackend backend = new HfxEngineController.ServerSessionBackend() {
            @Override
            public AbstractHFXServer acquireServer(int ioMode) {
                LoopbackServer s = new LoopbackServer();
                s.setPreferredBlockSize(256 * 1024);
                createdServer[0] = s;
                return s;
            }

            @Override
            public void releaseServer(AbstractHFXServer server) {
            }

            @Override
            public List<RemoteFile> listLocalFiles(AbstractHFXServer server, String path) {
                return Utils.listRemoteFiles(path);
            }

            @Override
            public boolean createLocalDir(AbstractHFXServer server, String parent, String child) {
                return new File(parent, child).mkdirs();
            }

            @Override
            public boolean deleteLocalFile(AbstractHFXServer server, String path) {
                return new File(path).delete();
            }

            @Override
            public RemoteFile resolveLocalFile(AbstractHFXServer server, String path) {
                return new RemoteFile(new File(path));
            }
        };

        List<SessionState> observedStates = new CopyOnWriteArrayList<>();
        List<String> observedEvents = new CopyOnWriteArrayList<>();

        try (HfxEngineController controller = new HfxEngineController("android-test-1", "TestPhone",
                new UsbNicAdvisor(), backend, 250L)) {
            controller.addEventListener((eventName, payload) -> {
                observedEvents.add(eventName);
                if (IpcEvents.RAIL_STATUS.equals(eventName) && payload instanceof IpcPayloads.RailStatusPayload) {
                    IpcPayloads.RailStatusPayload rp = (IpcPayloads.RailStatusPayload) payload;
                    for (IpcDtos.RailStatusInfo r : rp.rails) {
                        if ("WAITING".equals(r.state)) {
                            serverBoundLatch.countDown();
                        }
                    }
                } else if (IpcEvents.SESSION_STATE_CHANGED.equals(eventName)
                        && payload instanceof IpcPayloads.SessionStateChangedPayload) {
                    IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
                    observedStates.add(sc.current);
                    if (sc.current == SessionState.CONNECTED) {
                        connectedLatch.countDown();
                    }
                } else if (IpcEvents.TRANSFER_COMPLETED.equals(eventName)
                        && payload instanceof IpcPayloads.TransferCompletedPayload) {
                    IpcPayloads.TransferCompletedPayload done = (IpcPayloads.TransferCompletedPayload) payload;
                    if ("upload".equals(done.direction)) {
                        uploadCompletedLatch.countDown();
                    } else if ("download".equals(done.direction)) {
                        downloadCompletedLatch.countDown();
                    }
                }
            });

            // 1) Initial snapshot & M4 method placeholder check
            IpcDtos.EngineSnapshot initSnap = controller.getSnapshot();
            assertEquals("android", initSnap.engineRole);
            assertEquals(ProtocolConstants.VERSION_CODE, initSnap.protocolVersion);
            assertEquals(SessionState.IDLE, initSnap.state);

            JsonRpcResponse m4Resp = controller.dispatch(null, IpcMethods.PAIR_SUBMIT_QR_URI, new JsonObject());
            assertFalse(m4Resp.isSuccess());
            assertEquals(JsonRpcError.METHOD_NOT_FOUND, m4Resp.error.code);

            // 2) Advice conflict event broadcast
            UsbNicAdvisor.AdviceResult syntheticAdvice = new UsbNicAdvisor().advise(
                    Arrays.asList(
                            new UsbNicAdvisor.RawInterface("ncm0", "ncm0",
                                    (Inet4Address) InetAddress.getByName("10.94.81.247"), 24, true, false),
                            new UsbNicAdvisor.RawInterface("wlan0", "wlan0",
                                    (Inet4Address) InetAddress.getByName("192.168.50.130"), 24, true, false)),
                    "super-speed");
            controller.applyAdvice(syntheticAdvice);
            assertTrue(observedEvents.contains(IpcEvents.RAIL_CONFLICT_RESOLVED));
            assertEquals("super-speed", controller.getSnapshot().udcSpeed);

            // 3) Start server on dynamic loopback port via JSON command dispatch
            int freePort;
            try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
                freePort = probe.getLocalPort();
            }
            IpcPayloads.ServerStartParams startParams = new IpcPayloads.ServerStartParams();
            startParams.port = freePort;
            startParams.localBufferCount = 16;
            startParams.remoteBufferCount = 16;
            IpcDtos.RailSelection sel = new IpcDtos.RailSelection();
            sel.name = "USB_ADB";
            sel.ip = "127.0.0.1";
            sel.railType = RailType.ADB.name();
            sel.streamCount = 2;
            startParams.selectedRails.add(sel);

            String startJson = controller.dispatchCommandJson(IpcMethods.SERVER_START, IpcJson.toJson(startParams));
            assertTrue(IpcJson.fromJson(startJson, JsonRpcResponse.class).isSuccess());
            assertTrue("Server must start listening", serverBoundLatch.await(5, TimeUnit.SECONDS));

            // 4) Connect loopback PC client with -d pcRoot metadata
            LoopbackClient client = new LoopbackClient("127.0.0.1", freePort, pcRoot.getAbsolutePath());
            ExecutorService clientExec = Executors.newSingleThreadExecutor();
            try {
                assertTrue(client.connect(new NoOpConnectCallback()));
                assertTrue("Controller must transition to CONNECTED", connectedLatch.await(5, TimeUnit.SECONDS));
                clientExec.submit(() -> {
                    try {
                        client.start(null);
                    } catch (Exception ignored) {
                    }
                });

                // Verify snapshot retains PC -d metadata while RemoteFileSelectAdapter defaults to "/"
                IpcDtos.EngineSnapshot connSnap = controller.getSnapshot();
                assertEquals(SessionState.CONNECTED, connSnap.state);
                assertEquals(pcRoot.getAbsolutePath(), connSnap.remoteHomeDir);
                assertEquals("/", RemoteFileSelectAdapter.DEFAULT_REMOTE_ROOT_DIR);

                // 5) Remote mkdir & list via controller command dispatch
                IpcPayloads.FsMkdirParams mk = new IpcPayloads.FsMkdirParams();
                mk.side = "remote";
                mk.parent = pcRoot.getAbsolutePath();
                mk.child = "from_phone";
                JsonRpcResponse mkResp = controller.dispatch(null, IpcMethods.FS_MKDIR, IpcJson.toJsonObject(mk));
                assertTrue(mkResp.isSuccess());
                File pcSubDir = new File(pcRoot, "from_phone");
                assertTrue(pcSubDir.isDirectory());

                // 6) Upload (Phone -> PC) + SHA-256 verification
                IpcPayloads.TransferStartParams up = new IpcPayloads.TransferStartParams();
                up.direction = "upload";
                up.files = Collections.singletonList(phoneFile.getAbsolutePath());
                up.srcDir = phoneRoot.getAbsolutePath();
                up.dstDir = pcSubDir.getAbsolutePath();
                assertTrue(controller.dispatch(null, IpcMethods.TRANSFER_START, IpcJson.toJsonObject(up)).isSuccess());
                assertTrue("Upload must complete", uploadCompletedLatch.await(15, TimeUnit.SECONDS));
                File uploadedOnPc = new File(pcSubDir, phoneFile.getName());
                assertFileSha256Equals(phoneFile, uploadedOnPc);

                waitUntilState(controller, SessionState.CONNECTED);

                // 7) Download (PC -> Phone) + SHA-256 verification
                IpcPayloads.TransferStartParams dn = new IpcPayloads.TransferStartParams();
                dn.direction = "download";
                dn.files = Collections.singletonList(uploadedOnPc.getAbsolutePath());
                dn.srcDir = pcSubDir.getAbsolutePath();
                dn.dstDir = phoneDownloadDir.getAbsolutePath();
                assertTrue(controller.dispatch(null, IpcMethods.TRANSFER_START, IpcJson.toJsonObject(dn)).isSuccess());
                assertTrue("Download must complete", downloadCompletedLatch.await(15, TimeUnit.SECONDS));
                assertFileSha256Equals(phoneFile, new File(phoneDownloadDir, phoneFile.getName()));

                assertTrue("Expected transfer.progress events", observedEvents.contains(IpcEvents.TRANSFER_PROGRESS));
                assertTrue(observedStates.contains(SessionState.DISCOVERING));
                assertTrue(observedStates.contains(SessionState.CONNECTED));
                assertTrue(observedStates.contains(SessionState.TRANSFERRING));

                // 8) Stop server -> IDLE
                assertTrue(controller.dispatch(null, IpcMethods.SERVER_STOP, null).isSuccess());
                assertEquals(SessionState.IDLE, controller.getState());
            } finally {
                client.requestShutdown();
                clientExec.shutdownNow();
            }
        }
    }

    @Test
    public void testHfxServerStaticInstanceCompletelyRemovedFromBytecodeAndSources() throws Exception {
        // 1) Reflection check: HFXServer must NOT declare any 'instance' field
        for (Field f : HFXServer.class.getDeclaredFields()) {
            assertFalse("HFXServer must NOT have static singleton field 'instance'",
                    "instance".equals(f.getName()));
        }

        // 2) Source tree scan: zero references to 'HFXServer.instance' in Android main sources
        File mainJavaDir = locateAndroidMainJavaDir();
        if (mainJavaDir != null && mainJavaDir.isDirectory()) {
            List<File> javaFiles = new ArrayList<>();
            collectFilesByExt(mainJavaDir, ".java", javaFiles);
            assertFalse("Expected Android Java sources under " + mainJavaDir, javaFiles.isEmpty());
            for (File jf : javaFiles) {
                String src = new String(Files.readAllBytes(jf.toPath()), StandardCharsets.UTF_8);
                assertFalse("Forbidden legacy reference 'HFXServer.instance' found in " + jf.getName(),
                        src.contains("HFXServer.instance"));
            }
        }
    }

    @Test
    public void testAllEngineClassesHaveZeroUiOrPrintStreamBytecodeReferences() throws Exception {
        URL location = HfxEngineController.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location);
        File codeSource = new File(location.toURI());

        Map<String, byte[]> engineClasses = new LinkedHashMap<>();
        String enginePrefix = "top/weixiansen574/hybridfilexfer/engine/";

        if (codeSource.isFile() && codeSource.getName().endsWith(".jar")) {
            try (JarFile jar = new JarFile(codeSource)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(enginePrefix) && name.endsWith(".class")) {
                        engineClasses.put(name, readAllBytes(jar.getInputStream(entry)));
                    }
                }
            }
        } else if (codeSource.isDirectory()) {
            File engineDir = new File(codeSource, enginePrefix);
            assertTrue("Expected compiled engine dir: " + engineDir, engineDir.isDirectory());
            List<File> classFiles = new ArrayList<>();
            collectFilesByExt(engineDir, ".class", classFiles);
            for (File cf : classFiles) {
                engineClasses.put(cf.getName(), Files.readAllBytes(cf.toPath()));
            }
        }

        assertTrue("Expected UsbNicAdvisor, HfxEngineController, and HfxEngineService classes",
                engineClasses.size() >= 3);

        for (Map.Entry<String, byte[]> entry : engineClasses.entrySet()) {
            String className = entry.getKey();
            String content = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
            assertFalse("engine/ must NOT reference android.app.Activity: " + className,
                    content.contains("Landroid/app/Activity;"));
            assertFalse("engine/ must NOT reference MainActivity: " + className,
                    content.contains("Ltop/weixiansen574/hybridfilexfer/MainActivity;"));
            assertFalse("engine/ must NOT reference TransferActivity: " + className,
                    content.contains("Ltop/weixiansen574/hybridfilexfer/TransferActivity;"));
            assertFalse("engine/ must NOT reference android.view.*: " + className,
                    content.contains("Landroid/view/"));
            assertFalse("engine/ must NOT reference android.widget.* (incl. Toast): " + className,
                    content.contains("Landroid/widget/"));
            assertFalse("engine/ must NOT reference RecyclerView: " + className,
                    content.contains("Landroidx/recyclerview/"));
            assertFalse("engine/ must NOT reference PrintStream: " + className,
                    content.contains("Ljava/io/PrintStream;"));
            if (className.contains("UsbNicAdvisor") || className.contains("HfxEngineController")) {
                assertFalse("Pure-Java engine controller/advisor must NOT reference android.*: " + className,
                        content.contains("Landroid/"));
            }
        }
    }

    private static void waitUntilState(HfxEngineController controller, SessionState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (controller.getState() == expected) {
                return;
            }
            Thread.sleep(25L);
        }
        assertEquals(expected, controller.getState());
    }

    private static File locateAndroidMainJavaDir() {
        File cwd = new File(System.getProperty("user.dir", "."));
        File candidate1 = new File(cwd, "src/main/java");
        if (candidate1.isDirectory()) return candidate1;
        File candidate2 = new File(cwd, "HybridFileXfer-Android/app/src/main/java");
        if (candidate2.isDirectory()) return candidate2;
        return null;
    }

    private static void collectFilesByExt(File dir, String ext, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectFilesByExt(f, ext, out);
            } else if (f.getName().endsWith(ext)) {
                out.add(f);
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
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
        assertTrue(expected.exists());
        assertTrue(actual.exists());
        assertEquals(expected.length(), actual.length());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h1 = md.digest(Files.readAllBytes(expected.toPath()));
        byte[] h2 = md.digest(Files.readAllBytes(actual.toPath()));
        assertArrayEquals(h1, h2);
    }

    private static final class NoOpConnectCallback implements ConnectServerCallback {
        @Override public void onConnectingControlChannel(String address, int port) {}
        @Override public void onVersionMismatch(int localVersion, int remoteVersion) {}
        @Override public void onConnectControlFailed() {}
        @Override public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {}
        @Override public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {}
        @Override public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {}
        @Override public void onRemoteOOM() {}
        @Override public void onConnectSuccess(List<String> channelNames) {}
    }
}
