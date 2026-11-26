package top.weixiansen574.hybridfilexfer.pc.engine.adb;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AdbManagerTest {

    @Test
    public void testVersionParsingAndDescendingSelectionAcrossSources() {
        String sdkPath = "C:\\Users\\Dev\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";
        String pathModern = "C:\\Tools\\adb36\\adb.exe";
        String pathLegacy = "C:\\Tools\\adb34\\adb.exe";
        String cwdLegacy = "D:\\work\\adb.exe";
        String explicitPath = "D:\\custom\\adb.exe";

        FakeEnv env = new FakeEnv(true)
                .env("LOCALAPPDATA", "C:\\Users\\Dev\\AppData\\Local")
                .env("PATH", "C:\\Tools\\adb34;C:\\Tools\\adb36")
                .file(sdkPath)
                .file(pathModern)
                .file(pathLegacy)
                .file(cwdLegacy)
                .file(explicitPath);

        Map<String, String> versions = new HashMap<>();
        versions.put(sdkPath.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458\nInstalled as " + sdkPath);
        versions.put(pathModern.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524\nInstalled as " + pathModern);
        versions.put(pathLegacy.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 34.0.5-10900879\nInstalled as " + pathLegacy);
        versions.put(cwdLegacy.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 34.0.5-10900879\nInstalled as " + cwdLegacy);
        versions.put(explicitPath.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458\nInstalled as " + explicitPath);

        RecordingExecutor exec = new RecordingExecutor(versions,
                "shell_v2,delayed_ack,libusb,openscreen_mdns",
                "shell_v2,delayed_ack,sendrecv_v2");

        AdbManager.AdbConfig cfg = new AdbManager.AdbConfig();
        cfg.explicitAdbPath = explicitPath;
        cfg.workingDirectory = new File("D:\\work");

        AdbManager manager = new AdbManager(cfg, exec, env, null);
        List<AdbManager.AdbCandidate> ranked = manager.discoverCandidates();

        assertEquals(5, ranked.size());
        // Highest version (36.0.0-13206524) wins even when found in PATH
        assertEquals(pathModern, ranked.get(0).path);
        assertEquals(new AdbManager.AdbVersion(36, 0, 0, 13206524L, "36.0.0-13206524"), ranked.get(0).version);
        // Tie-break on 35.0.2: explicit --adb-path (priority 0) before SDK (priority 1)
        assertEquals(explicitPath, ranked.get(1).path);
        assertEquals(sdkPath, ranked.get(2).path);

        // User-tunable override: forceExplicitAdbPath pins --adb-path
        cfg.forceExplicitAdbPath = true;
        AdbManager pinnedManager = new AdbManager(cfg, exec, env, null);
        assertEquals(explicitPath, pinnedManager.selectBestCandidate().path);
    }

    @Test
    public void testOutdatedVersionEmitsStructuredWarning() {
        String oldAdb = "D:\\work\\adb.exe";
        FakeEnv env = new FakeEnv(true).file(oldAdb);
        Map<String, String> versions = Collections.singletonMap(
                oldAdb.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 34.0.5-10900879");

        RecordingExecutor exec = new RecordingExecutor(versions,
                "shell_v2,sendrecv_v2,openscreen_mdns",
                "shell_v2,sendrecv_v2");

        List<String> eventNames = new ArrayList<>();
        List<Object> payloads = new ArrayList<>();
        AdbManager.AdbConfig cfg = new AdbManager.AdbConfig();
        cfg.workingDirectory = new File("D:\\work");

        AdbManager manager = new AdbManager(cfg, exec, env, (ev, payload) -> {
            eventNames.add(ev);
            payloads.add(payload);
        });

        IpcDtos.AdbCapabilityInfo cap = manager.probe("SER123");
        assertTrue(cap.available);
        assertEquals("34.0.5-10900879", cap.version);
        assertNotNull(cap.warning);
        assertTrue(cap.warning.contains("34.0.5-10900879"));
        assertFalse(cap.hasDelayedAck);
        assertFalse(cap.hasLibusb);
        assertEquals(4, cap.recommendedStreams);

        assertEquals(1, eventNames.size());
        assertEquals(IpcEvents.ADB_VERSION_WARNING, eventNames.get(0));
        assertTrue(payloads.get(0) instanceof IpcPayloads.DiagnosticWarningPayload);
        assertEquals("ADB_VERSION_OUTDATED", ((IpcPayloads.DiagnosticWarningPayload) payloads.get(0)).code);
    }

    @Test
    public void testFeatureProbingModernVsMissingDelayedAckFallback() {
        String adb36 = "C:\\Tools\\adb36\\adb.exe";
        FakeEnv env = new FakeEnv(true).env("PATH", "C:\\Tools\\adb36").file(adb36);
        Map<String, String> versions = Collections.singletonMap(
                adb36.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524");

        // Case 1: both host & device support delayed_ack + host has libusb -> 1-2 streams (default 2)
        RecordingExecutor modernExec = new RecordingExecutor(versions,
                "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nshell_v2,libusb,delayed_ack,sendrecv_v2",
                "shell_v2,delayed_ack,sendrecv_v2");
        List<String> warnings = new ArrayList<>();
        AdbManager modernMgr = new AdbManager(new AdbManager.AdbConfig(), modernExec, env, (ev, p) -> warnings.add(ev));
        IpcDtos.AdbCapabilityInfo modernCap = modernMgr.probe("3B15B100P8E00000");

        assertTrue(modernCap.available);
        assertNull(modernCap.warning);
        assertTrue(warnings.isEmpty());
        assertTrue(modernCap.hasLibusb);
        assertTrue(modernCap.hasDelayedAck);
        assertTrue(modernCap.burstModeEnvInjected);
        assertTrue("Modern ADB should recommend 1-2 streams",
                modernCap.recommendedStreams >= 1 && modernCap.recommendedStreams <= 2);
        assertEquals(2, modernCap.recommendedStreams);

        // Case 2: host has libusb + delayed_ack, but device features lack delayed_ack -> auto 4 streams
        RecordingExecutor deviceNoDelayedAckExec = new RecordingExecutor(versions,
                "shell_v2,libusb,delayed_ack",
                "shell_v2,sendrecv_v2");
        AdbManager fallbackMgr = new AdbManager(new AdbManager.AdbConfig(), deviceNoDelayedAckExec, env, null);
        IpcDtos.AdbCapabilityInfo fallbackCap = fallbackMgr.probe("OLDDEV");
        assertTrue(fallbackCap.hasLibusb);
        assertFalse(fallbackCap.hasDelayedAck);
        assertEquals(4, fallbackCap.recommendedStreams);

        // Case 3: user explicit overrideStreams
        AdbManager.AdbConfig overrideCfg = new AdbManager.AdbConfig();
        overrideCfg.overrideStreams = 6;
        AdbManager overrideMgr = new AdbManager(overrideCfg, modernExec, env, null);
        assertEquals(6, overrideMgr.probe("3B15B100P8E00000").recommendedStreams);
    }

    @Test
    public void testProcessEnvInjectionAndLegacyFallbackAndForwardCleanup() {
        String adb36 = "C:\\Tools\\adb36\\adb.exe";
        FakeEnv env = new FakeEnv(true).env("PATH", "C:\\Tools\\adb36").file(adb36);
        Map<String, String> versions = Collections.singletonMap(
                adb36.toLowerCase(Locale.ROOT),
                "Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524");

        // Modern mode: ADB_LIBUSB=1 and ADB_BURST_MODE=1 injected strictly at child-process scope
        RecordingExecutor exec = new RecordingExecutor(versions, "libusb,delayed_ack", "delayed_ack");
        AdbManager mgr = new AdbManager(new AdbManager.AdbConfig(), exec, env, null);
        AdbManager.ForwardResult fwd = mgr.forwardPort(5740, 5740, "DEV001");
        assertTrue(fwd.success);
        assertEquals(1, mgr.getActiveForwardCount());

        // Verify forward command and injected child env
        RecordedCall forwardCall = exec.findCallContaining("forward");
        assertNotNull(forwardCall);
        assertEquals("1", forwardCall.envSpec.setVars.get("ADB_LIBUSB"));
        assertEquals("1", forwardCall.envSpec.setVars.get("ADB_BURST_MODE"));
        assertTrue(forwardCall.command.contains("-s"));
        assertTrue(forwardCall.command.contains("DEV001"));
        assertTrue(forwardCall.command.contains("tcp:5740"));

        // Graceful cleanup removes forward rule
        mgr.close();
        assertEquals(0, mgr.getActiveForwardCount());
        RecordedCall removeCall = exec.findCallContaining("--remove");
        assertNotNull(removeCall);
        assertTrue(removeCall.command.contains("tcp:5740"));

        // Legacy fallback mode: disables ADB_LIBUSB and removes ADB_BURST_MODE, falls back to 4 streams
        RecordingExecutor legacyExec = new RecordingExecutor(versions, "shell_v2", "shell_v2");
        AdbManager.AdbConfig legacyCfg = new AdbManager.AdbConfig();
        legacyCfg.adbLegacy = true;
        AdbManager legacyMgr = new AdbManager(legacyCfg, legacyExec, env, null);
        IpcDtos.AdbCapabilityInfo legacyCap = legacyMgr.probe(null);

        assertFalse(legacyCap.burstModeEnvInjected);
        assertEquals(4, legacyCap.recommendedStreams);
        assertFalse(legacyExec.calls.isEmpty());
        AdbManager.ProcessEnvSpec legacyEnv = legacyExec.calls.get(0).envSpec;
        assertEquals("0", legacyEnv.setVars.get("ADB_LIBUSB"));
        assertFalse(legacyEnv.setVars.containsKey("ADB_BURST_MODE"));
        assertTrue(legacyEnv.removeVars.contains("ADB_BURST_MODE"));
    }

    @Test
    public void testRealDefaultCommandExecutorInjectsChildProcessEnvOnly() throws Exception {
        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        AdbManager manager = new AdbManager();
        AdbManager.ProcessEnvSpec spec = manager.buildProcessEnvironment();

        List<String> cmd = new ArrayList<>();
        if (isWin) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("echo %ADB_LIBUSB%_%ADB_BURST_MODE%");
        } else {
            cmd.add("/bin/sh");
            cmd.add("-c");
            cmd.add("echo ${ADB_LIBUSB}_${ADB_BURST_MODE}");
        }

        AdbManager.DefaultCommandExecutor executor = new AdbManager.DefaultCommandExecutor();
        AdbManager.CommandResult result = executor.execute(cmd, spec, new File("."), 5000L);
        assertTrue("Child echo command should succeed: " + result.stderr, result.isSuccess());
        assertEquals("1_1", result.stdout.trim());
    }

    // ---- Test doubles ----

    private static final class FakeEnv implements AdbManager.EnvironmentProvider {
        private final boolean windows;
        private final Map<String, String> envMap = new HashMap<>();
        private final Set<String> files = new HashSet<>();

        FakeEnv(boolean windows) {
            this.windows = windows;
        }

        FakeEnv env(String key, String value) {
            envMap.put(key.toUpperCase(Locale.ROOT), value);
            return this;
        }

        FakeEnv file(String path) {
            files.add(normalize(path));
            return this;
        }

        private String normalize(String path) {
            String p = path.replace('/', File.separatorChar).replace('\\', File.separatorChar);
            return windows ? p.toLowerCase(Locale.ROOT) : p;
        }

        @Override
        public String getEnv(String name) {
            return envMap.get(name.toUpperCase(Locale.ROOT));
        }

        @Override
        public boolean isWindows() {
            return windows;
        }

        @Override
        public String getPathSeparator() {
            return windows ? ";" : ":";
        }

        @Override
        public boolean isExecutableFile(File file) {
            return files.contains(normalize(file.getPath()));
        }

        @Override
        public String canonicalizePath(File file) {
            return file.getPath();
        }
    }

    private static final class RecordedCall {
        final List<String> command;
        final AdbManager.ProcessEnvSpec envSpec;

        RecordedCall(List<String> command, AdbManager.ProcessEnvSpec envSpec) {
            this.command = new ArrayList<>(command);
            this.envSpec = envSpec;
        }
    }

    private static final class RecordingExecutor implements AdbManager.CommandExecutor {
        private final Map<String, String> versionByLowerPath;
        private final String hostFeaturesOut;
        private final String deviceFeaturesOut;
        final List<RecordedCall> calls = new ArrayList<>();

        RecordingExecutor(Map<String, String> versionByLowerPath, String hostFeaturesOut, String deviceFeaturesOut) {
            this.versionByLowerPath = versionByLowerPath;
            this.hostFeaturesOut = hostFeaturesOut;
            this.deviceFeaturesOut = deviceFeaturesOut;
        }

        RecordedCall findCallContaining(String token) {
            for (RecordedCall c : calls) {
                if (c.command.contains(token)) {
                    return c;
                }
            }
            return null;
        }

        @Override
        public AdbManager.CommandResult execute(List<String> command,
                                                AdbManager.ProcessEnvSpec envSpec,
                                                File workingDir,
                                                long timeoutMs) {
            calls.add(new RecordedCall(command, envSpec));
            String bin = command.get(0).toLowerCase(Locale.ROOT);
            if (command.contains("version")) {
                String out = versionByLowerPath.get(bin);
                if (out == null) {
                    return new AdbManager.CommandResult(1, "", "not found");
                }
                return new AdbManager.CommandResult(0, out, "");
            }
            if (command.contains("host-features")) {
                return new AdbManager.CommandResult(0, hostFeaturesOut, "");
            }
            if (command.contains("features")) {
                return new AdbManager.CommandResult(0, deviceFeaturesOut, "");
            }
            if (command.contains("forward")) {
                return new AdbManager.CommandResult(0, "5740\n", "");
            }
            return new AdbManager.CommandResult(0, "", "");
        }
    }
}
