package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.JsonRpcResponse;
import top.weixiansen574.hybridfilexfer.pc.engine.PcEngineMain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression suite for P0-BUG-01:
 * verifies that real JmDNS-enabled {@code hfx-engine --ipc=stdio} subprocesses terminate
 * deterministically within {@code <= 5s} with {@code exitCode == 0} on both
 * {@code engine.shutdown} and raw {@code stdin} EOF, leaving zero OS file locks on the engine JAR,
 * and that {@link MdnsManager#close()} closes bindings in parallel with a bounded timeout.
 */
public class MdnsExitRegressionTest {

    private static final long MAX_EXIT_WAIT_SECONDS = 5L;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testMdnsManagerCloseRunsInParallelWithBoundedTimeout() throws Exception {
        NicSelector selector = new NicSelector();
        List<NicSelector.RawNic> rawNics = Arrays.asList(
                new NicSelector.RawNic("usb0", "USB NCM Host Device",
                        (Inet4Address) InetAddress.getByName("10.94.81.203"), 24, true, false, false, false),
                new NicSelector.RawNic("eth0", "Realtek 2.5GbE",
                        (Inet4Address) InetAddress.getByName("192.168.50.10"), 24, true, false, false, false),
                new NicSelector.RawNic("wlan0", "Intel Wi-Fi 7 BE200",
                        (Inet4Address) InetAddress.getByName("192.168.50.236"), 24, true, false, false, false)
        );
        List<IpcDtos.NicInfo> cleaned = selector.select(rawNics);
        assertEquals(3, cleaned.size());

        CountDownLatch allClosesStarted = new CountDownLatch(3);
        CountDownLatch unblockStuckCloses = new CountDownLatch(1);
        MdnsManager.MdnsBindingFactory slowFactory = (nic, hostName, sink) -> new MdnsManager.MdnsNicBinding() {
            @Override
            public String getBoundIp() {
                return nic.ip;
            }

            @Override
            public void close() {
                allClosesStarted.countDown();
                try {
                    unblockStuckCloses.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        Path storeFile = tempFolder.newFolder("parallel_close_store").toPath().resolve("trusted_peers.json");
        TrustedPeerStore peerStore = new TrustedPeerStore(storeFile);

        long startMs;
        long elapsedMs;
        try {
            MdnsManager mdns = new MdnsManager(selector, peerStore, "OMEN", slowFactory, 400L);
            mdns.start();
            mdns.refreshBindings(cleaned);
            assertEquals(3, mdns.getBoundNicCount());

            startMs = System.currentTimeMillis();
            mdns.close();
            elapsedMs = System.currentTimeMillis() - startMs;

            assertTrue("All 3 binding close() calls should have started in parallel",
                    allClosesStarted.await(1, TimeUnit.SECONDS));
            assertTrue("MdnsManager.close() must return near bounded timeout (400ms), took " + elapsedMs + "ms",
                    elapsedMs < 1800L);
            assertEquals(0, mdns.getBoundNicCount());
        } finally {
            unblockStuckCloses.countDown();
        }
    }

    @Test
    public void testRealJmDnsStdioProcessExitsOnShutdownWithin5sAndReleasesJarLock() throws Exception {
        runSubprocessRealMdnsExitCheck("shutdown", true);
    }

    @Test
    public void testRealJmDnsStdioProcessExitsOnStdinEofWithin5sAndReleasesJarLock() throws Exception {
        runSubprocessRealMdnsExitCheck("stdin_eof", false);
    }

    private void runSubprocessRealMdnsExitCheck(String label, boolean sendShutdownRpc) throws Exception {
        Path workDir = tempFolder.newFolder("mdns_exit_" + label).toPath();
        Path engineJar = workDir.resolve("hfx-engine-lockcheck-" + label + ".jar");
        Path trustedPeers = workDir.resolve("trusted_peers.json");
        Path qrDir = workDir.resolve("qr");

        Path pcEngineClassesRoot = Paths.get(PcEngineMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        assertTrue("Expected compiled :pc-engine classes directory: " + pcEngineClassesRoot,
                Files.isDirectory(pcEngineClassesRoot));

        buildClassesJar(pcEngineClassesRoot, engineJar);
        assertTrue("Temporary engine JAR must exist", Files.isRegularFile(engineJar));

        String childClasspath = buildChildClasspathWithTempJar(engineJar, pcEngineClassesRoot);
        String javaBin = resolveJavaExecutable();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(childClasspath);
        cmd.add(PcEngineMain.class.getName());
        cmd.add("--ipc=stdio");
        // IMPORTANT: intentionally do NOT pass --no-mdns so real JmDNS 3.5.9 starts!
        cmd.add("--trusted-peers=" + trustedPeers.toAbsolutePath());
        cmd.add("--qr-dir=" + qrDir.toAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        Process proc = pb.start();

        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    synchronized (stderrBuf) {
                        stderrBuf.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        }, "hfx-test-stderr-" + label);
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        try {
            BufferedWriter stdin = new BufferedWriter(
                    new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));

            // 1) Wait for engine (with real JmDNS started) to respond to engine.get_snapshot
            stdin.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"engine.get_snapshot\"}");
            stdin.newLine();
            stdin.flush();

            String snapLine = readResponseLineWithTimeout(stdout, 1, 20_000L);
            assertNotNull("Expected engine.get_snapshot response from child JVM (stderr=" + stderrBuf + ")", snapLine);
            JsonRpcResponse snapResp = IpcJson.fromJson(snapLine, JsonRpcResponse.class);
            assertTrue("engine.get_snapshot must succeed", snapResp.isSuccess());
            IpcDtos.EngineSnapshot snapshot = IpcJson.fromJson(snapResp.result, IpcDtos.EngineSnapshot.class);
            assertEquals("pc", snapshot.engineRole);

            // 2) Trigger exit via either engine.shutdown RPC or raw stdin EOF
            long exitTriggerStartMs;
            if (sendShutdownRpc) {
                exitTriggerStartMs = System.currentTimeMillis();
                stdin.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"engine.shutdown\"}");
                stdin.newLine();
                stdin.flush();

                String shutLine = readResponseLineWithTimeout(stdout, 2, 5_000L);
                assertNotNull("Expected engine.shutdown response (stderr=" + stderrBuf + ")", shutLine);
                JsonRpcResponse shutResp = IpcJson.fromJson(shutLine, JsonRpcResponse.class);
                assertTrue("engine.shutdown must succeed", shutResp.isSuccess());
                JsonObject resObj = shutResp.result.getAsJsonObject();
                assertTrue("engine.shutdown ok must be true", resObj.get("ok").getAsBoolean());
            } else {
                exitTriggerStartMs = System.currentTimeMillis();
                stdin.close();
            }

            // 3) Assert child JVM exits completely within <= 5s with exitCode == 0
            long remainingMs = Math.max(100L, TimeUnit.SECONDS.toMillis(MAX_EXIT_WAIT_SECONDS)
                    - (System.currentTimeMillis() - exitTriggerStartMs));
            boolean exited = proc.waitFor(remainingMs, TimeUnit.MILLISECONDS);
            long totalExitMs = System.currentTimeMillis() - exitTriggerStartMs;
            assertTrue("Child JVM with real JmDNS must exit within <= 5s (" + label + "), elapsed="
                    + totalExitMs + "ms, stderr=" + stderrBuf, exited);
            assertEquals("Child JVM must exit cleanly with code 0 (stderr=" + stderrBuf + ")",
                    0, proc.exitValue());

            // 4) Verify ZERO OS file lock residue on the engine JAR:
            //    exclusive rename + zip compression + delete must succeed immediately.
            Path renamedJar = workDir.resolve("hfx-engine-lockcheck-" + label + "-renamed.jar");
            Files.move(engineJar, renamedJar, StandardCopyOption.REPLACE_EXISTING);
            assertFalse("Original JAR path must no longer exist after rename", Files.exists(engineJar));
            assertTrue("Renamed JAR must exist", Files.isRegularFile(renamedJar));

            Path zipPath = workDir.resolve("hfx-engine-lockcheck-" + label + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                zos.putNextEntry(new ZipEntry(renamedJar.getFileName().toString()));
                Files.copy(renamedJar, zos);
                zos.closeEntry();
            }
            assertTrue("Zipped JAR archive must be non-empty", Files.size(zipPath) > 0L);
            Files.delete(renamedJar);
            assertFalse("Renamed JAR must be cleanly deletable", Files.exists(renamedJar));
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
                proc.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    private static String readResponseLineWithTimeout(BufferedReader reader,
                                                      int expectedId,
                                                      long timeoutMs) throws Exception {
        final String[] found = new String[1];
        final IOException[] error = new IOException[1];
        CountDownLatch done = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                        continue;
                    }
                    JsonObject obj = IpcJson.parse(trimmed).getAsJsonObject();
                    if (obj.has("id") && !obj.get("id").isJsonNull()
                            && obj.get("id").getAsInt() == expectedId) {
                        found[0] = trimmed;
                        return;
                    }
                }
            } catch (IOException e) {
                error[0] = e;
            } finally {
                done.countDown();
            }
        }, "hfx-test-stdout-reader-" + expectedId);
        t.setDaemon(true);
        t.start();

        boolean completed = done.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            return null;
        }
        if (error[0] != null) {
            throw error[0];
        }
        return found[0];
    }

    private static void buildClassesJar(Path classesDir, Path outputJar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, PcEngineMain.class.getName());

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outputJar), manifest)) {
            Files.walk(classesDir).forEach(path -> {
                if (!Files.isRegularFile(path)) {
                    return;
                }
                String rel = classesDir.relativize(path).toString().replace('\\', '/');
                try {
                    JarEntry entry = new JarEntry(rel);
                    jos.putNextEntry(entry);
                    Files.copy(path, jos);
                    jos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static String buildChildClasspathWithTempJar(Path tempJar, Path excludedClassesRoot) {
        String currentCp = System.getProperty("java.class.path", "");
        String[] parts = currentCp.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<String> filtered = new ArrayList<>();
        filtered.add(tempJar.toAbsolutePath().toString());
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            Path normalized = Paths.get(p).toAbsolutePath().normalize();
            if (normalized.equals(excludedClassesRoot)) {
                continue;
            }
            filtered.add(p);
        }
        return String.join(File.pathSeparator, filtered);
    }

    private static String resolveJavaExecutable() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path winExe = javaHome.resolve("bin").resolve("java.exe");
        if (Files.isRegularFile(winExe)) {
            return winExe.toAbsolutePath().toString();
        }
        return javaHome.resolve("bin").resolve("java").toAbsolutePath().toString();
    }
}
