package top.weixiansen574.hybridfilexfer.pc.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkWriteFileCall;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PcEngineBootstrapTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testInitialSnapshotAndJdkWriteFileCallLru() throws Exception {
        IpcDtos.EngineSnapshot snapshot = PcEngineMain.createInitialSnapshot("test-pc-1");
        assertEquals("pc", snapshot.engineRole);
        assertEquals(ProtocolConstants.VERSION_CODE, snapshot.protocolVersion);
        assertEquals(SessionState.IDLE, snapshot.state);
        assertEquals("test-pc-1", snapshot.deviceId);

        File out = new File(tempFolder.newFolder("jdk_write"), "hello.txt");
        byte[] content = "HybridFileXfer v4 LRU write check".getBytes(StandardCharsets.UTF_8);
        LinkedBlockingDeque<ByteBuffer> pool = new LinkedBlockingDeque<>();
        pool.add(ByteBuffer.allocateDirect(1024));

        ByteBuffer buf = pool.take();
        buf.put(content);
        JdkWriteFileCall writer = new JdkWriteFileCall(pool, 1, 8);
        writer.putBlock(new FileBlock(true, 0, out.getAbsolutePath(), 1730000000000L,
                content.length, 0, 1024, buf), 0);
        writer.finishChannel(0);
        writer.call();

        assertTrue(out.exists());
        assertArrayEquals(content, Files.readAllBytes(out.toPath()));
        assertEquals(1, pool.size());
    }

    @Test
    public void testPcEngineHasZeroUiReferences() throws Exception {
        URL location = PcEngineMain.class.getProtectionDomain().getCodeSource().getLocation();
        File classesRoot = new File(location.toURI());
        List<File> classFiles = new ArrayList<>();
        collectClassFiles(classesRoot, classFiles);
        assertTrue(classFiles.size() >= 4);

        for (File cf : classFiles) {
            String content = new String(Files.readAllBytes(cf.toPath()), StandardCharsets.ISO_8859_1);
            assertFalse(":pc-engine must NOT reference javax.swing.*: " + cf.getName(),
                    content.contains("Ljavax/swing/"));
            assertFalse(":pc-engine must NOT reference java.awt.*: " + cf.getName(),
                    content.contains("Ljava/awt/"));
            assertFalse(":pc-engine must NOT reference android.*: " + cf.getName(),
                    content.contains("Landroid/"));
            // Only PcEngineMain (stdio IPC transport bootstrap) may touch System.out;
            // all engine business classes (adb, discovery, transport, jdkcore) must have zero PrintStream references.
            if (!"PcEngineMain.class".equals(cf.getName())) {
                assertFalse(":pc-engine business classes must NOT use System.out / PrintStream: " + cf.getName(),
                        content.contains("Ljava/io/PrintStream;"));
            }
        }
    }

    private static void collectClassFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectClassFiles(f, out);
            } else if (f.getName().endsWith(".class")) {
                out.add(f);
            }
        }
    }
}
