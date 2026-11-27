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

    @Test
    public void testPosixDiscoveryAcrossAndroidHomePathAndWorkingDir() {
        String sdkPath = "/opt/android-sdk/platform-tools/adb";
        String pathModern = "/usr/local/bin/adb";
        String cwdLegacy = "/home/dev/work/adb";

        FakeEnv env = new FakeEnv(false)
                .env("ANDROID_HOME", "/opt/android-sdk")
                .env("PATH", "/usr/bin:/usr/local/bin")
                .file(sdkPath)
                .file(pathModern)
                .file(cwdLegacy);

        Map<String, String> versions = new HashMap<>();
        versions.put(sdkPath, "Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458");
        versions.put(pathModern, "Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524");
        versions.put(cwdLegacy, "Android Debug Bridge version 1.0.41\nVersion 34.0.5-10900879");

        RecordingExecutor exec = new RecordingExecutor(versions,
                "shell_v2,delayed_ack,libusb",
                "shell_v2,delayed_ack");

        AdbManager.AdbConfig cfg = new AdbManager.AdbConfig();
        cfg.workingDirectory = new File("/home/dev/work");

        AdbManager manager = new AdbManager(cfg, exec, env, null);
        List<AdbManager.AdbCandidate> ranked = manager.discoverCandidates();

        assertEquals(3, ranked.size());
        assertEquals(pathModern, ranked.get(0).path);
        assertEquals(sdkPath, ranked.get(1).path);
        assertEquals(cwdLegacy, ranked.get(2).path);

        IpcDtos.AdbCapabilityInfo cap = manager.probe("POSIXDEV");
        assertTrue(cap.available);
        assertEquals(pathModern, cap.adbPath);
        assertEquals(2, cap.recommendedStreams);
    }

    @Test
    public void testForwardPortAutoRecoversOfflineDeviceWithoutKillingSharedDaemon() {
        String adb35 = "C:\\Users\\Dev\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";
        FakeEnv env = new FakeEnv(true)
                .env("LOCALAPPDATA", "C:\\Users\\Dev\\AppData\\Local")
                .file(adb35);

        List<List<String>> invokedCommands = new ArrayList<>();
        final boolean[] recovered = new boolean[]{false};
        final int[] forwardAttempts = new int[]{0};

        AdbManager.CommandExecutor offlineExec = (cmd, envSpec, wd, timeoutMs) -> {
            invokedCommands.add(new ArrayList<>(cmd));
            if (cmd.contains("version")) {
                return new AdbManager.CommandResult(0,
                        "Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458\n", "");
            }
            if (cmd.contains("host-features")) {
                return new AdbManager.CommandResult(0, "shell_v2,sendrecv_v2\n", "");
            }
            if (cmd.contains("features")) {
                return recovered[0]
                        ? new AdbManager.CommandResult(0, "shell_v2,sendrecv_v2\n", "")
                        : new AdbManager.CommandResult(1, "", "adb.exe: device offline");
            }
            if (cmd.contains("reconnect") && cmd.contains("offline")) {
                recovered[0] = true;
                return new AdbManager.CommandResult(0, "reconnecting 3B15B100P8E00000\n", "");
            }
            if (cmd.contains("devices")) {
                String state = recovered[0] ? "device" : "offline";
                return new AdbManager.CommandResult(0,
                        "List of devices attached\n3B15B100P8E00000\t" + state + "\n", "");
            }
            if (cmd.contains("forward") && cmd.contains("--remove")) {
                return new AdbManager.CommandResult(0, "", "");
            }
            if (cmd.contains("forward")) {
                forwardAttempts[0]++;
                if (!recovered[0]) {
                    return new AdbManager.CommandResult(1, "", "adb.exe: device offline");
                }
                return new AdbManager.CommandResult(0, "5740\n", "");
            }
            return new AdbManager.CommandResult(0, "", "");
        };

        List<String> eventCodes = new ArrayList<>();
        AdbManager.AdbConfig cfg = new AdbManager.AdbConfig();
        cfg.offlinePollIntervalMs = 10L;
        cfg.offlineRecoveryWaitMs = 1000L;

        AdbManager mgr = new AdbManager(cfg, offlineExec, env, (ev, payload) -> {
            if (payload instanceof IpcPayloads.DiagnosticWarningPayload) {
                eventCodes.add(((IpcPayloads.DiagnosticWarningPayload) payload).code);
            }
        });

        AdbManager.ForwardResult result = mgr.forwardPort(5740, 5740, "3B15B100P8E00000");
        assertTrue("forwardPort must auto-recover after adb reconnect offline", result.success);
        assertEquals(2, forwardAttempts[0]);
        assertEquals(1, mgr.getActiveForwardCount());
        assertTrue("Expected ADB_OFFLINE_RECOVERY diagnostic event",
                eventCodes.contains("ADB_OFFLINE_RECOVERY"));
        assertTrue("Must invoke 'adb reconnect offline'",
                invokedCommands.stream().anyMatch(c -> c.contains("reconnect") && c.contains("offline")));
        assertFalse("Must NEVER invoke destructive 'adb kill-server' on shared daemon",
                invokedCommands.stream().anyMatch(c -> c.contains("kill-server")));
    }

    @Test
    public void testCleanupForwardsUsesBoundedTimeoutAndTriggersOfflineRecoveryOnWedgedTransport() {
        String adb35 = "C:\\Tools\\adb35\\adb.exe";
        FakeEnv env = new FakeEnv(true).env("PATH", "C:\\Tools\\adb35").file(adb35);

        List<List<String>> cmds = new ArrayList<>();
        List<Long> removeTimeouts = new ArrayList<>();

        AdbManager.CommandExecutor exec = (cmd, envSpec, wd, timeoutMs) -> {
            cmds.add(new ArrayList<>(cmd));
            if (cmd.contains("version")) {
                return new AdbManager.CommandResult(0, "Version 35.0.2-12147458\n", "");
            }
            if (cmd.contains("host-features") || cmd.contains("features")) {
                return new AdbManager.CommandResult(0, "shell_v2\n", "");
            }
            if (cmd.contains("forward") && cmd.contains("--remove")) {
                removeTimeouts.add(timeoutMs);
                return new AdbManager.CommandResult(1, "", "adb.exe: device offline");
            }
            if (cmd.contains("forward")) {
                return new AdbManager.CommandResult(0, "5740\n", "");
            }
            return new AdbManager.CommandResult(0, "", "");
        };

        AdbManager.AdbConfig cfg = new AdbManager.AdbConfig();
        cfg.commandTimeoutMs = 10_000L;
        cfg.cleanupTimeoutMs = 1_500L;

        AdbManager mgr = new AdbManager(cfg, exec, env, null);
        assertTrue(mgr.forwardPort(5740, 5740, "DEV999").success);
        assertEquals(1, mgr.getActiveForwardCount());

        mgr.close();
        assertEquals(0, mgr.getActiveForwardCount());
        assertEquals(1, removeTimeouts.size());
        assertEquals(Long.valueOf(1_500L), removeTimeouts.get(0));
        assertTrue("Teardown must issue best-effort 'adb reconnect offline' when remove sees offline",
                cmds.stream().anyMatch(c -> c.contains("reconnect") && c.contains("offline")));
    }

    @Test
    public void testParseDevicesOutputHandlesStatesAndDaemonNoise() {
        String raw = "* daemon not running; starting now at tcp:5037\n"
                + "* daemon started successfully\n"
                + "List of devices attached\n"
                + "3B15B100P8E00000       offline transport_id:137\n"
                + "emulator-5554          device product:sdk_gphone64_x86_64\n"
                + "UNAUTH01               unauthorized\n";
        Map<String, String> parsed = AdbManager.parseDevicesOutput(raw);
        assertEquals(3, parsed.size());
        assertEquals("offline", parsed.get("3B15B100P8E00000"));
        assertEquals("device", parsed.get("emulator-5554"));
        assertEquals("unauthorized", parsed.get("UNAUTH01"));
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
            char targetSep = windows ? '\\' : '/';
            return file.getPath().replace('/', targetSep).replace('\\', targetSep);
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
        private final Map<String, String> versionByNormalizedPath = new HashMap<>();
        private final String hostFeaturesOut;
        private final String deviceFeaturesOut;
        final List<RecordedCall> calls = new ArrayList<>();

        RecordingExecutor(Map<String, String> versionByLowerPath, String hostFeaturesOut, String deviceFeaturesOut) {
            for (Map.Entry<String, String> entry : versionByLowerPath.entrySet()) {
                this.versionByNormalizedPath.put(normalizeLookupKey(entry.getKey()), entry.getValue());
            }
            this.hostFeaturesOut = hostFeaturesOut;
            this.deviceFeaturesOut = deviceFeaturesOut;
        }

        private static String normalizeLookupKey(String path) {
            return path.replace('\\', '/').toLowerCase(Locale.ROOT);
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
            String bin = normalizeLookupKey(command.get(0));
            if (command.contains("version")) {
                String out = versionByNormalizedPath.get(bin);
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
