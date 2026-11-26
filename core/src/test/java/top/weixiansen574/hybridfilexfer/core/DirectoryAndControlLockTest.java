package top.weixiansen574.hybridfilexfer.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DirectoryAndControlLockTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testWindowsDirectoryParentPreservesDriveRoot() {
        Directory sub = new Directory("D:\\HfxBench\\sub", Directory.FILE_SYSTEM_WINDOWS);
        assertEquals("D:\\HfxBench\\sub\\", sub.path);

        Directory firstLevel = sub.parent();
        assertNotNull(firstLevel);
        assertEquals("D:\\HfxBench\\", firstLevel.path);

        Directory driveRoot = firstLevel.parent();
        assertNotNull(driveRoot);
        assertEquals("D:\\", driveRoot.path);

        Directory virtualRoot = driveRoot.parent();
        assertNotNull(virtualRoot);
        assertEquals("/", virtualRoot.path);
        assertNull(virtualRoot.parent());
    }

    @Test
    public void testConcurrentListClientFilesSerializedWithoutBufferOverflow() throws Exception {
        File dir = tempFolder.newFolder("list_dir");
        for (int i = 0; i < 10; i++) {
            Files.write(new File(dir, "file_" + i + ".txt").toPath(), new byte[]{(byte) i});
        }

        final CountDownLatch serverStarted = new CountDownLatch(1);
        final CountDownLatch connected = new CountDownLatch(1);
        final TestServer server = new TestServer();
        List<ServerNetInterface> rails = Collections.singletonList(
                new ServerNetInterface("USB_ADB", InetAddress.getByName("127.0.0.1"), null, RailType.ADB, 2));

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server.startServer(0, rails, 4, 4, new ServerHandshakeCallback() {
                        @Override
                        public void onBindFailed(int port) {}
                        @Override
                        public void onServerStarted() {
                            serverStarted.countDown();
                        }
                        @Override
                        public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {}
                        @Override
                        public void onAcceptFailed(String name) {}
                        @Override
                        public void onRemoteOOM() {}
                        @Override
                        public void onLocalOOM(int created, int localCount) {}
                        @Override
                        public void onConnectSuccess() {
                            connected.countDown();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }, "test-server");
        serverThread.start();
        assertTrue(serverStarted.await(5, TimeUnit.SECONDS));

        final TestClient client = new TestClient("127.0.0.1", server.getBoundPort(), dir.getAbsolutePath());
        Thread clientThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = client.connect(new ConnectServerCallback() {
                        @Override public void onConnectingControlChannel(String address, int port) {}
                        @Override public void onVersionMismatch(int localVersion, int remoteVersion) {}
                        @Override public void onConnectControlFailed() {}
                        @Override public void onConnectingTransferChannel(String name, InetAddress addr, InetAddress bind) {}
                        @Override public void onConnectTransferChannelFailed(String name, InetAddress addr, Exception e) {}
                        @Override public void onOOM(int created, int req, long mem, String arch) {}
                        @Override public void onRemoteOOM() {}
                        @Override public void onConnectSuccess(List<String> names) {}
                    });
                    if (ok) {
                        client.start(new ClientCallBack() {
                            @Override public void onReceiving() {}
                            @Override public void onSending() {}
                            @Override public void onExit() {}
                            @Override public void onFileUploading(String i, String p, long t, long tot) {}
                            @Override public void onFileDownloading(String i, String p, long t, long tot) {}
                            @Override public void onSpeedInfo(List<top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo> l) {}
                            @Override public void onChannelComplete(String i, long tr, long ti) {}
                            @Override public void onChannelError(String i, int e, String m) {}
                            @Override public void onReadFileError(String m) {}
                            @Override public void onWriteFileError(String m) {}
                            @Override public void onComplete(boolean u, long tr, long ti) {}
                            @Override public void onIncomplete() {}
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }, "test-client");
        clientThread.start();
        assertTrue(connected.await(5, TimeUnit.SECONDS));

        // Fire 16 concurrent listClientFiles() calls across 8 threads -> must serialize cleanly with 0 exceptions
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<List<RemoteFile>>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(new Callable<List<RemoteFile>>() {
                @Override
                public List<RemoteFile> call() throws Exception {
                    return server.listClientFiles(dir.getAbsolutePath());
                }
            }));
        }
        for (Future<List<RemoteFile>> f : futures) {
            List<RemoteFile> res = f.get(5, TimeUnit.SECONDS);
            assertNotNull(res);
            assertEquals(10, res.size());
        }
        pool.shutdownNow();
        server.disconnect();
        clientThread.join(3000);
    }

    private static final class TestServer extends AbstractHFXServer {
        @Override protected ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> b, int d) { return null; }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> b, List<RemoteFile> f,
                                                            Directory l, Directory r, int c) { return null; }
    }

    private static final class TestClient extends HFXClient {
        TestClient(String host, int port, String homeDir) { super(host, port, homeDir); }
        @Override public ByteBuffer createBuffer(int size) { return ByteBuffer.allocateDirect(size); }
        @Override public long getAvailableMemoryMB() { return 1024; }
        @Override protected boolean deleteLocalFile(String path) { return false; }
        @Override protected boolean mkdir(String parent, String child) { return false; }
        @Override protected List<RemoteFile> listFiles(String path) { return Utils.listRemoteFiles(path); }
        @Override protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> b, int d) { return null; }
        @Override protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> b, List<RemoteFile> f,
                                                            Directory l, Directory r, int c) { return null; }
    }
}
