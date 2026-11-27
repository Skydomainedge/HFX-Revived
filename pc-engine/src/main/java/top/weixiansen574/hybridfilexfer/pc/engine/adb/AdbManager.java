package top.weixiansen574.hybridfilexfer.pc.engine.adb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Headless PC ADB manager for M2 high-speed wired transfer.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li>Scans candidate {@code adb} binaries ({@code --adb-path}, Android SDK, {@code PATH},
 *       {@code ./adb}) and ranks them by descending semantic version (with configurable explicit-pin
 *       override for future user tuning).</li>
 *   <li>Injects {@code ADB_LIBUSB=1} and {@code ADB_BURST_MODE=1} strictly at child-process scope via
 *       {@link ProcessBuilder#environment()}, never mutating host system environment variables.</li>
 *   <li>Probes {@code adb host-features} and {@code adb [-s serial] features}: recommends 1–2 streams
 *       when both {@code delayed_ack} and {@code libusb} are active, or automatically falls back to
 *       a 4-stream pool when {@code delayed_ack} is absent.</li>
 *   <li>Manages {@code adb forward} rules and cleans them up gracefully via {@link #close()} and an
 *       optional JVM shutdown hook.</li>
 * </ul>
 * Strictly zero UI / {@code System.out} / {@code PrintStream} references.</p>
 */
public class AdbManager implements AutoCloseable {

    public static final AdbVersion DEFAULT_MIN_MODERN_VERSION = new AdbVersion(35, 0, 0, 0, "35.0.0");
    public static final int DEFAULT_MODERN_STREAMS = 2;
    public static final int DEFAULT_FALLBACK_STREAMS = 4;
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_CLEANUP_TIMEOUT_MS = 2_000L;
    public static final long DEFAULT_OFFLINE_RECOVERY_WAIT_MS = 4_000L;
    public static final long DEFAULT_OFFLINE_POLL_INTERVAL_MS = 250L;

    private static final Pattern VERSION_LINE_PATTERN =
            Pattern.compile("(?im)^\\s*Version\\s+(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?");
    private static final Pattern GENERIC_SEMVER_PATTERN =
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?");

    /**
     * Tunable configuration for ADB discovery, process-level environment injection, and stream pooling.
     * Defaults adhere to the M2 standard while exposing knobs for future user/UI configuration.
     */
    public static final class AdbConfig {
        public String explicitAdbPath;
        public boolean forceExplicitAdbPath = false;
        public boolean adbLegacy = false;
        public boolean injectLibusb = true;
        public boolean injectBurstMode = true;
        public AdbVersion minModernVersion = DEFAULT_MIN_MODERN_VERSION;
        public int modernRecommendedStreams = DEFAULT_MODERN_STREAMS;
        public int fallbackRecommendedStreams = DEFAULT_FALLBACK_STREAMS;
        public Integer overrideStreams;
        public long commandTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS;
        public long cleanupTimeoutMs = DEFAULT_CLEANUP_TIMEOUT_MS;
        public boolean autoRecoverOfflineDevice = true;
        public long offlineRecoveryWaitMs = DEFAULT_OFFLINE_RECOVERY_WAIT_MS;
        public long offlinePollIntervalMs = DEFAULT_OFFLINE_POLL_INTERVAL_MS;
        public File workingDirectory = new File(System.getProperty("user.dir", "."));
        public final List<String> extraSearchPaths = new ArrayList<>();

        public AdbConfig() {
        }

        public AdbConfig copy() {
            AdbConfig c = new AdbConfig();
            c.explicitAdbPath = this.explicitAdbPath;
            c.forceExplicitAdbPath = this.forceExplicitAdbPath;
            c.adbLegacy = this.adbLegacy;
            c.injectLibusb = this.injectLibusb;
            c.injectBurstMode = this.injectBurstMode;
            c.minModernVersion = this.minModernVersion != null ? this.minModernVersion : DEFAULT_MIN_MODERN_VERSION;
            c.modernRecommendedStreams = this.modernRecommendedStreams;
            c.fallbackRecommendedStreams = this.fallbackRecommendedStreams;
            c.overrideStreams = this.overrideStreams;
            c.commandTimeoutMs = this.commandTimeoutMs > 0 ? this.commandTimeoutMs : DEFAULT_COMMAND_TIMEOUT_MS;
            c.cleanupTimeoutMs = this.cleanupTimeoutMs > 0 ? this.cleanupTimeoutMs : DEFAULT_CLEANUP_TIMEOUT_MS;
            c.autoRecoverOfflineDevice = this.autoRecoverOfflineDevice;
            c.offlineRecoveryWaitMs = this.offlineRecoveryWaitMs >= 0
                    ? this.offlineRecoveryWaitMs : DEFAULT_OFFLINE_RECOVERY_WAIT_MS;
            c.offlinePollIntervalMs = this.offlinePollIntervalMs >= 0
                    ? this.offlinePollIntervalMs : DEFAULT_OFFLINE_POLL_INTERVAL_MS;
            c.workingDirectory = this.workingDirectory != null
                    ? this.workingDirectory
                    : new File(System.getProperty("user.dir", "."));
            c.extraSearchPaths.addAll(this.extraSearchPaths);
            return c;
        }
    }

    /**
     * Parsed ADB platform-tools semantic version (e.g. {@code 36.0.0-13206524}).
     */
    public static final class AdbVersion implements Comparable<AdbVersion> {
        public static final AdbVersion UNKNOWN = new AdbVersion(0, 0, 0, 0, "unknown");

        public final int major;
        public final int minor;
        public final int patch;
        public final long build;
        public final String raw;

        public AdbVersion(int major, int minor, int patch, long build, String raw) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.build = build;
            this.raw = raw != null ? raw : formatVersion(major, minor, patch, build);
        }

        public static AdbVersion parse(String output) {
            if (output == null || output.isEmpty()) {
                return UNKNOWN;
            }
            Matcher m = VERSION_LINE_PATTERN.matcher(output);
            if (m.find()) {
                return fromMatcher(m);
            }
            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("android debug bridge version")) {
                    continue;
                }
                Matcher gm = GENERIC_SEMVER_PATTERN.matcher(trimmed);
                if (gm.find()) {
                    return fromMatcher(gm);
                }
            }
            return UNKNOWN;
        }

        private static AdbVersion fromMatcher(Matcher m) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = Integer.parseInt(m.group(3));
            long build = 0L;
            String buildStr = m.group(4);
            if (buildStr != null && !buildStr.isEmpty()) {
                try {
                    build = Long.parseLong(buildStr);
                } catch (NumberFormatException ignored) {
                    build = 0L;
                }
            }
            String raw = formatVersion(major, minor, patch, build);
            return new AdbVersion(major, minor, patch, build, raw);
        }

        private static String formatVersion(int major, int minor, int patch, long build) {
            String base = major + "." + minor + "." + patch;
            return build > 0 ? base + "-" + build : base;
        }

        public boolean isKnown() {
            return major > 0 || minor > 0 || patch > 0;
        }

        public boolean isAtLeast(AdbVersion other) {
            return this.compareTo(other) >= 0;
        }

        @Override
        public int compareTo(AdbVersion o) {
            if (o == null) {
                return 1;
            }
            int c = Integer.compare(this.major, o.major);
            if (c != 0) return c;
            c = Integer.compare(this.minor, o.minor);
            if (c != 0) return c;
            c = Integer.compare(this.patch, o.patch);
            if (c != 0) return c;
            return Long.compare(this.build, o.build);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AdbVersion)) return false;
            AdbVersion other = (AdbVersion) obj;
            return major == other.major && minor == other.minor
                    && patch == other.patch && build == other.build;
        }

        @Override
        public int hashCode() {
            return Objects.hash(major, minor, patch, build);
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    /**
     * Candidate adb binary discovered during scan.
     */
    public static final class AdbCandidate {
        public final String path;
        public final int sourcePriority;
        public final AdbVersion version;

        public AdbCandidate(String path, int sourcePriority, AdbVersion version) {
            this.path = path;
            this.sourcePriority = sourcePriority;
            this.version = version != null ? version : AdbVersion.UNKNOWN;
        }
    }

    /**
     * Child-process environment adjustment (never applied to host/system environment).
     */
    public static final class ProcessEnvSpec {
        public final Map<String, String> setVars;
        public final Set<String> removeVars;

        public ProcessEnvSpec(Map<String, String> setVars, Set<String> removeVars) {
            this.setVars = Collections.unmodifiableMap(new LinkedHashMap<>(setVars));
            this.removeVars = Collections.unmodifiableSet(new LinkedHashSet<>(removeVars));
        }
    }

    /**
     * Result of executing an external adb command.
     */
    public static final class CommandResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Result of executing {@code adb forward}.
     */
    public static final class ForwardResult {
        public final boolean success;
        public final int localPort;
        public final int remotePort;
        public final String serial;
        public final String stdout;
        public final String stderr;
        public final IpcDtos.AdbCapabilityInfo capability;

        public ForwardResult(boolean success, int localPort, int remotePort, String serial,
                             String stdout, String stderr, IpcDtos.AdbCapabilityInfo capability) {
            this.success = success;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.serial = serial;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.capability = capability;
        }
    }

    /**
     * Parsed entry from {@code adb devices -l}.
     */
    public static final class AdbDeviceInfo {
        public final String serial;
        public final String state;
        public final String model;
        public final String product;
        public final String device;
        public final String usb;
        public final String transportId;

        public AdbDeviceInfo(String serial, String state, String model, String product,
                             String device, String usb, String transportId) {
            this.serial = serial;
            this.state = state;
            this.model = model;
            this.product = product;
            this.device = device;
            this.usb = usb;
            this.transportId = transportId;
        }

        public boolean isOnline() {
            return "device".equals(state);
        }

        public boolean isOffline() {
            return "offline".equals(state);
        }

        public boolean isPhysicalUsb() {
            if (serial == null || serial.isEmpty()) {
                return false;
            }
            if (usb != null && !usb.isEmpty()) {
                return true;
            }
            String lower = serial.toLowerCase(Locale.ROOT);
            return !lower.startsWith("emulator-")
                    && !serial.contains(":")
                    && !lower.contains("._adb-tls-connect");
        }
    }

    /**
     * Abstraction over OS process invocation for deterministic unit testing.
     */
    @FunctionalInterface
    public interface CommandExecutor {
        CommandResult execute(List<String> command, ProcessEnvSpec envSpec, File workingDir, long timeoutMs)
                throws IOException, InterruptedException;
    }

    /**
     * Abstraction over host environment lookup and filesystem checks for unit testing.
     */
    public interface EnvironmentProvider {
        String getEnv(String name);

        boolean isWindows();

        String getPathSeparator();

        boolean isExecutableFile(File file);

        String canonicalizePath(File file);
    }

    /**
     * Structured event callback (replaces console printing inside the headless engine).
     */
    @FunctionalInterface
    public interface DiagnosticListener {
        void onEvent(String eventName, Object payload);
    }

    private static final class ForwardRule {
        final String adbPath;
        final int localPort;
        final String serial;

        ForwardRule(String adbPath, int localPort, String serial) {
            this.adbPath = adbPath;
            this.localPort = localPort;
            this.serial = (serial == null || serial.isEmpty()) ? null : serial;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ForwardRule)) return false;
            ForwardRule that = (ForwardRule) o;
            return localPort == that.localPort
                    && Objects.equals(adbPath, that.adbPath)
                    && Objects.equals(serial, that.serial);
        }

        @Override
        public int hashCode() {
            return Objects.hash(adbPath, localPort, serial);
        }
    }

    private final AdbConfig config;
    private final CommandExecutor commandExecutor;
    private final EnvironmentProvider envProvider;
    private volatile DiagnosticListener diagnosticListener;
    private volatile IpcDtos.AdbCapabilityInfo lastCapability;
    private final Set<ForwardRule> activeForwards = ConcurrentHashMap.newKeySet();
    private Thread shutdownHook;
    private volatile boolean inShutdownHook = false;

    public AdbManager() {
        this(new AdbConfig(), new DefaultCommandExecutor(), new SystemEnvironmentProvider(), null);
    }

    public AdbManager(AdbConfig config, DiagnosticListener diagnosticListener) {
        this(config, new DefaultCommandExecutor(), new SystemEnvironmentProvider(), diagnosticListener);
    }

    public AdbManager(AdbConfig config,
                      CommandExecutor commandExecutor,
                      EnvironmentProvider envProvider,
                      DiagnosticListener diagnosticListener) {
        this.config = config != null ? config.copy() : new AdbConfig();
        this.commandExecutor = commandExecutor != null ? commandExecutor : new DefaultCommandExecutor();
        this.envProvider = envProvider != null ? envProvider : new SystemEnvironmentProvider();
        this.diagnosticListener = diagnosticListener;
    }

    public void setDiagnosticListener(DiagnosticListener listener) {
        this.diagnosticListener = listener;
    }

    public AdbConfig getConfig() {
        return config.copy();
    }

    public IpcDtos.AdbCapabilityInfo getLastCapability() {
        return lastCapability;
    }

    /**
     * Computes child-process environment overrides without altering the host OS environment.
     */
    public ProcessEnvSpec buildProcessEnvironment() {
        Map<String, String> setVars = new LinkedHashMap<>();
        Set<String> removeVars = new LinkedHashSet<>();
        if (config.adbLegacy) {
            setVars.put("ADB_LIBUSB", "0");
            removeVars.add("ADB_BURST_MODE");
        } else {
            if (config.injectLibusb) {
                setVars.put("ADB_LIBUSB", "1");
            }
            if (config.injectBurstMode) {
                setVars.put("ADB_BURST_MODE", "1");
            }
        }
        return new ProcessEnvSpec(setVars, removeVars);
    }

    /**
     * Discovers candidate {@code adb} executables in priority order:
     * {@code --adb-path} -> Android SDK platform-tools -> {@code PATH} -> {@code ./adb},
     * deduplicated by canonical path.
     */
    public List<AdbCandidate> discoverCandidates() {
        Map<String, CandidatePath> unique = new LinkedHashMap<>();
        String exeName = envProvider.isWindows() ? "adb.exe" : "adb";

        // Priority 0: explicit --adb-path
        if (config.explicitAdbPath != null && !config.explicitAdbPath.trim().isEmpty()) {
            addCandidateIfExists(unique, new File(config.explicitAdbPath.trim()), 0);
        }

        // Priority 1: Android SDK platform-tools & extra configured search paths
        for (String extra : config.extraSearchPaths) {
            if (extra != null && !extra.trim().isEmpty()) {
                File f = new File(extra.trim());
                if (f.isDirectory()) {
                    addCandidateIfExists(unique, new File(f, exeName), 1);
                } else {
                    addCandidateIfExists(unique, f, 1);
                }
            }
        }
        String localAppData = envProvider.getEnv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isEmpty()) {
            addCandidateIfExists(unique,
                    new File(localAppData, "Android" + File.separator + "Sdk" + File.separator
                            + "platform-tools" + File.separator + exeName), 1);
        }
        String sdkRoot = envProvider.getEnv("ANDROID_SDK_ROOT");
        if (sdkRoot != null && !sdkRoot.isEmpty()) {
            addCandidateIfExists(unique,
                    new File(sdkRoot, "platform-tools" + File.separator + exeName), 1);
        }
        String androidHome = envProvider.getEnv("ANDROID_HOME");
        if (androidHome != null && !androidHome.isEmpty()) {
            addCandidateIfExists(unique,
                    new File(androidHome, "platform-tools" + File.separator + exeName), 1);
        }

        // Priority 2: PATH entries
        String pathEnv = envProvider.getEnv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String sep = envProvider.getPathSeparator();
            String[] entries = pathEnv.split(Pattern.quote(sep));
            for (String entry : entries) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                addCandidateIfExists(unique, new File(trimmed, exeName), 2);
            }
        }

        // Priority 3: ./adb(.exe) in working directory
        addCandidateIfExists(unique, new File(config.workingDirectory, exeName), 3);

        List<AdbCandidate> probed = new ArrayList<>();
        ProcessEnvSpec envSpec = buildProcessEnvironment();
        for (CandidatePath cp : unique.values()) {
            AdbVersion ver = probeVersion(cp.path, envSpec);
            if (ver != null) {
                probed.add(new AdbCandidate(cp.path, cp.priority, ver));
            }
        }

        // Sort by descending version, tie-break by source priority ascending
        probed.sort(new Comparator<AdbCandidate>() {
            @Override
            public int compare(AdbCandidate a, AdbCandidate b) {
                int verCmp = b.version.compareTo(a.version);
                if (verCmp != 0) {
                    return verCmp;
                }
                return Integer.compare(a.sourcePriority, b.sourcePriority);
            }
        });
        return probed;
    }

    /**
     * Selects the best candidate according to descending version policy (or explicit pin if configured).
     */
    public AdbCandidate selectBestCandidate() {
        List<AdbCandidate> candidates = discoverCandidates();
        if (candidates.isEmpty()) {
            return null;
        }
        if (config.forceExplicitAdbPath && config.explicitAdbPath != null && !config.explicitAdbPath.trim().isEmpty()) {
            for (AdbCandidate c : candidates) {
                if (c.sourcePriority == 0) {
                    return c;
                }
            }
        }
        return candidates.get(0);
    }

    /**
     * Probes ADB binary version, {@code host-features}, device {@code features}, and computes
     * the recommended concurrent stream count for {@code USB_ADB}.
     */
    public IpcDtos.AdbCapabilityInfo probe(String serial) {
        IpcDtos.AdbCapabilityInfo info = new IpcDtos.AdbCapabilityInfo();
        ProcessEnvSpec envSpec = buildProcessEnvironment();
        info.burstModeEnvInjected = !config.adbLegacy && (config.injectLibusb || config.injectBurstMode);

        AdbCandidate selected = selectBestCandidate();
        if (selected == null) {
            info.available = false;
            info.recommendedStreams = computeRecommendedStreams(false, false);
            info.warning = "No executable adb binary found in --adb-path, Android SDK, PATH, or working directory.";
            emitWarning("ADB_NOT_FOUND", info.warning);
            this.lastCapability = info;
            return info;
        }

        info.available = true;
        info.adbPath = selected.path;
        info.version = selected.version.raw;

        if (!selected.version.isKnown() || !selected.version.isAtLeast(config.minModernVersion)) {
            info.warning = "Detected adb version " + selected.version.raw
                    + " at " + selected.path
                    + " is older than recommended " + config.minModernVersion.raw
                    + "; upgrade Android platform-tools (>= 35.0.0 / 36.0.0+) for async libusb + delayed_ack.";
            emitWarning("ADB_VERSION_OUTDATED", info.warning);
        }

        Set<String> hostFeatures = queryFeatures(selected.path, null, "host-features", envSpec);
        Set<String> deviceFeatures = queryFeatures(selected.path, serial, "features", envSpec);

        boolean hostHasLibusb = hostFeatures.contains("libusb");
        boolean hostHasDelayedAck = hostFeatures.contains("delayed_ack");
        boolean deviceChecked = !deviceFeatures.isEmpty();
        boolean effectiveDelayedAck = hostHasDelayedAck && (!deviceChecked || deviceFeatures.contains("delayed_ack"));

        info.hasLibusb = hostHasLibusb;
        info.hasDelayedAck = effectiveDelayedAck;
        info.recommendedStreams = computeRecommendedStreams(info.hasLibusb, info.hasDelayedAck);

        this.lastCapability = info;
        return info;
    }

    /**
     * Computes recommended ADB stream count from runtime capability flags and tunable config.
     */
    public int computeRecommendedStreams(boolean hasLibusb, boolean hasDelayedAck) {
        if (config.overrideStreams != null) {
            return ProtocolConstants.clampStreamCount(config.overrideStreams);
        }
        if (!config.adbLegacy && hasDelayedAck && hasLibusb) {
            return ProtocolConstants.clampStreamCount(config.modernRecommendedStreams);
        }
        return ProtocolConstants.clampStreamCount(config.fallbackRecommendedStreams);
    }

    /**
     * Parses feature tokens returned by {@code adb host-features} or {@code adb features}.
     */
    public static Set<String> parseFeatureList(String output) {
        Set<String> features = new LinkedHashSet<>();
        if (output == null || output.isEmpty()) {
            return features;
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("*") || lower.startsWith("error:") || lower.startsWith("adb:")
                    || lower.startsWith("adb.exe:") || lower.contains("daemon not running")
                    || lower.contains("daemon started successfully")) {
                continue;
            }
            String[] tokens = trimmed.split("[,\\s]+");
            for (String token : tokens) {
                String cleaned = token.trim().toLowerCase(Locale.ROOT);
                if (!cleaned.isEmpty()) {
                    features.add(cleaned);
                }
            }
        }
        return features;
    }

    /**
     * Parses {@code adb devices} output into an ordered {@code serial -> state} map
     * (e.g. {@code "device"}, {@code "offline"}, {@code "unauthorized"}).
     */
    public static Map<String, String> parseDevicesOutput(String output) {
        Map<String, String> devices = new LinkedHashMap<>();
        if (output == null || output.isEmpty()) {
            return devices;
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("list of devices") || lower.startsWith("*")
                    || lower.startsWith("adb:") || lower.startsWith("adb.exe:")
                    || lower.startsWith("error:")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                devices.put(parts[0], parts[1].toLowerCase(Locale.ROOT));
            }
        }
        return devices;
    }

    /**
     * Parses {@code adb devices -l} (or plain {@code adb devices}) output into structured records.
     */
    public static List<AdbDeviceInfo> parseDevicesLongOutput(String output) {
        List<AdbDeviceInfo> result = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return result;
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("list of devices") || lower.startsWith("*")
                    || lower.startsWith("adb:") || lower.startsWith("adb.exe:")
                    || lower.startsWith("error:")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                continue;
            }
            String serial = parts[0];
            String state = parts[1].toLowerCase(Locale.ROOT);
            String model = null;
            String product = null;
            String device = null;
            String usb = null;
            String transportId = null;
            for (int i = 2; i < parts.length; i++) {
                String token = parts[i];
                int colon = token.indexOf(':');
                if (colon <= 0 || colon >= token.length() - 1) {
                    continue;
                }
                String k = token.substring(0, colon).toLowerCase(Locale.ROOT);
                String v = token.substring(colon + 1);
                if ("model".equals(k)) {
                    model = v;
                } else if ("product".equals(k)) {
                    product = v;
                } else if ("device".equals(k)) {
                    device = v;
                } else if ("usb".equals(k)) {
                    usb = v;
                } else if ("transport_id".equals(k)) {
                    transportId = v;
                }
            }
            result.add(new AdbDeviceInfo(serial, state, model, product, device, usb, transportId));
        }
        return result;
    }

    /**
     * Resolves a single online ADB serial for a peer discovered via mDNS/QR/direct IP when
     * no explicit {@code -s <serial>} was supplied.
     */
    public static String selectBestSerialForPeer(List<AdbDeviceInfo> devices, String preferredDeviceName) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        List<AdbDeviceInfo> online = new ArrayList<>();
        for (AdbDeviceInfo d : devices) {
            if (d != null && d.isOnline() && d.serial != null && !d.serial.isEmpty()) {
                online.add(d);
            }
        }
        if (online.isEmpty()) {
            return null;
        }
        if (online.size() == 1) {
            return online.get(0).serial;
        }

        if (preferredDeviceName != null && !preferredDeviceName.trim().isEmpty()) {
            String targetNorm = normalizeModelToken(preferredDeviceName);
            if (!targetNorm.isEmpty()) {
                List<AdbDeviceInfo> exactMatches = new ArrayList<>();
                List<AdbDeviceInfo> partialMatches = new ArrayList<>();
                for (AdbDeviceInfo d : online) {
                    String modelNorm = normalizeModelToken(d.model);
                    String productNorm = normalizeModelToken(d.product);
                    String deviceNorm = normalizeModelToken(d.device);
                    if (targetNorm.equals(modelNorm) || targetNorm.equals(productNorm) || targetNorm.equals(deviceNorm)) {
                        exactMatches.add(d);
                    } else if ((!modelNorm.isEmpty() && (modelNorm.contains(targetNorm) || targetNorm.contains(modelNorm)))
                            || (!productNorm.isEmpty() && (productNorm.contains(targetNorm) || targetNorm.contains(productNorm)))) {
                        partialMatches.add(d);
                    }
                }
                List<AdbDeviceInfo> matches = !exactMatches.isEmpty() ? exactMatches : partialMatches;
                if (matches.size() == 1) {
                    return matches.get(0).serial;
                }
                if (matches.size() > 1) {
                    List<AdbDeviceInfo> usbMatches = filterPhysicalUsb(matches);
                    if (usbMatches.size() == 1) {
                        return usbMatches.get(0).serial;
                    }
                }
            }
        }

        List<AdbDeviceInfo> usbOnly = filterPhysicalUsb(online);
        if (usbOnly.size() == 1) {
            return usbOnly.get(0).serial;
        }
        return null;
    }

    private static List<AdbDeviceInfo> filterPhysicalUsb(List<AdbDeviceInfo> devices) {
        List<AdbDeviceInfo> out = new ArrayList<>();
        for (AdbDeviceInfo d : devices) {
            if (d.isPhysicalUsb()) {
                out.add(d);
            }
        }
        return out;
    }

    private static String normalizeModelToken(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    static boolean isOfflineError(CommandResult res) {
        if (res == null || res.isSuccess()) {
            return false;
        }
        String combined = (res.stderr + "\n" + res.stdout).toLowerCase(Locale.ROOT);
        return combined.contains("device offline") || combined.contains("offline");
    }

    static boolean isPortRebindError(CommandResult res) {
        if (res == null || res.isSuccess()) {
            return false;
        }
        String combined = (res.stderr + "\n" + res.stdout).toLowerCase(Locale.ROOT);
        return combined.contains("cannot rebind")
                || combined.contains("cannot bind listener")
                || combined.contains("address already in use");
    }

    static boolean isRecoverableTransportError(CommandResult res) {
        if (res == null || res.isSuccess()) {
            return false;
        }
        if (isOfflineError(res)) {
            return true;
        }
        String combined = (res.stderr + "\n" + res.stdout).toLowerCase(Locale.ROOT);
        return combined.contains("protocol fault")
                || combined.contains("closed")
                || combined.contains("timed out")
                || combined.contains("not found");
    }

    /**
     * Resolves an attached ADB device for a peer discovered via mDNS/QR/direct IP and establishes
     * {@code adb -s <serial> forward tcp:<localPort> tcp:<remotePort>}.
     *
     * <p>Avoids bare {@code adb forward} failures when multiple devices/emulators or stale offline
     * entries exist alongside the wired phone, while keeping explicit {@code -s <serial>} intact.</p>
     */
    public ForwardResult forwardPortForPeer(int localPort,
                                            int remotePort,
                                            String explicitSerial,
                                            String preferredDeviceName) {
        if (explicitSerial != null && !explicitSerial.trim().isEmpty()) {
            return forwardPort(localPort, remotePort, explicitSerial.trim());
        }

        AdbCandidate selected = selectBestCandidate();
        if (selected == null) {
            IpcDtos.AdbCapabilityInfo cap = probe(null);
            return new ForwardResult(false, localPort, remotePort, null, "",
                    cap.warning != null ? cap.warning : "adb not available", cap);
        }

        ProcessEnvSpec envSpec = buildProcessEnvironment();
        long queryTimeout = Math.min(config.commandTimeoutMs, 3_000L);
        List<AdbDeviceInfo> devices = listDevicesLong(selected.path, envSpec, queryTimeout);

        boolean hasOnline = false;
        boolean hasOffline = false;
        for (AdbDeviceInfo d : devices) {
            if (d.isOnline()) {
                hasOnline = true;
            } else if (d.isOffline()) {
                hasOffline = true;
            }
        }
        if (!hasOnline && hasOffline && config.autoRecoverOfflineDevice) {
            if (recoverOfflineTransport(selected.path, null, envSpec, true)) {
                devices = listDevicesLong(selected.path, envSpec, queryTimeout);
            }
        }

        List<AdbDeviceInfo> onlineDevices = new ArrayList<>();
        for (AdbDeviceInfo d : devices) {
            if (d.isOnline()) {
                onlineDevices.add(d);
            }
        }
        if (onlineDevices.isEmpty()) {
            IpcDtos.AdbCapabilityInfo cap = new IpcDtos.AdbCapabilityInfo();
            cap.available = true;
            cap.adbPath = selected.path;
            cap.version = selected.version.raw;
            cap.burstModeEnvInjected = !config.adbLegacy && (config.injectLibusb || config.injectBurstMode);
            cap.recommendedStreams = computeRecommendedStreams(false, false);
            return new ForwardResult(false, localPort, remotePort, null, "",
                    "No online ADB device attached", cap);
        }

        String resolvedSerial = selectBestSerialForPeer(onlineDevices, preferredDeviceName);
        if (resolvedSerial == null) {
            StringBuilder serials = new StringBuilder();
            for (int i = 0; i < onlineDevices.size(); i++) {
                if (i > 0) serials.append(", ");
                serials.append(onlineDevices.get(i).serial);
            }
            IpcDtos.AdbCapabilityInfo cap = new IpcDtos.AdbCapabilityInfo();
            cap.available = true;
            cap.adbPath = selected.path;
            cap.version = selected.version.raw;
            cap.burstModeEnvInjected = !config.adbLegacy && (config.injectLibusb || config.injectBurstMode);
            cap.recommendedStreams = computeRecommendedStreams(false, false);
            return new ForwardResult(false, localPort, remotePort, null, "",
                    "Multiple online ADB devices attached (" + serials + "); specify -s <serial> or uncheck USB_ADB",
                    cap);
        }

        return forwardPort(localPort, remotePort, resolvedSerial);
    }

    /**
     * Establishes {@code adb [-s serial] forward tcp:<localPort> tcp:<remotePort>} with process-level env.
     *
     * <p>If the USB transport is stuck in Windows WinUSB {@code offline} loop after an abnormal
     * disconnect/Wi-Fi drop, performs targeted non-destructive recovery ({@code adb reconnect offline})
     * without killing the shared {@code adb} daemon, waits briefly for the device to return online,
     * refreshes device features, and retries {@code forward} once.</p>
     */
    public ForwardResult forwardPort(int localPort, int remotePort, String serial) {
        IpcDtos.AdbCapabilityInfo cap = probe(serial);
        if (!cap.available || cap.adbPath == null) {
            return new ForwardResult(false, localPort, remotePort, serial, "",
                    cap.warning != null ? cap.warning : "adb not available", cap);
        }

        ProcessEnvSpec envSpec = buildProcessEnvironment();
        List<String> cmd = buildBaseCommand(cap.adbPath, serial);
        cmd.add("forward");
        cmd.add("tcp:" + localPort);
        cmd.add("tcp:" + remotePort);

        try {
            CommandResult res = commandExecutor.execute(
                    cmd, envSpec, config.workingDirectory, config.commandTimeoutMs);

            if (!res.isSuccess() && isPortRebindError(res)) {
                executeRemoveForward(cap.adbPath, localPort, serial, config.cleanupTimeoutMs);
                if (serial != null && !serial.isEmpty()) {
                    executeRemoveForward(cap.adbPath, localPort, null, config.cleanupTimeoutMs);
                }
                res = commandExecutor.execute(
                        cmd, envSpec, config.workingDirectory, config.commandTimeoutMs);
            }

            if (!res.isSuccess() && config.autoRecoverOfflineDevice && isRecoverableTransportError(res)) {
                boolean offlineDetected = isOfflineError(res);
                if (!offlineDetected) {
                    Map<String, String> devices = listDevices(cap.adbPath, envSpec,
                            Math.min(config.commandTimeoutMs, config.cleanupTimeoutMs));
                    offlineDetected = hasOfflineDevice(devices, serial);
                }
                if (offlineDetected && recoverOfflineTransport(cap.adbPath, serial, envSpec, true)) {
                    // Clear any stale forward rule left by an interrupted session before re-forwarding
                    executeRemoveForward(cap.adbPath, localPort, serial, config.cleanupTimeoutMs);
                    refreshDeviceFeaturesAfterRecovery(cap, serial, envSpec);
                    res = commandExecutor.execute(
                            cmd, envSpec, config.workingDirectory, config.commandTimeoutMs);
                }
            }

            if (res.isSuccess()) {
                activeForwards.add(new ForwardRule(cap.adbPath, localPort, serial));
            }
            return new ForwardResult(res.isSuccess(), localPort, remotePort, serial, res.stdout, res.stderr, cap);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ForwardResult(false, localPort, remotePort, serial, "", e.getMessage(), cap);
        }
    }

    /**
     * Removes a previously established {@code adb forward} rule.
     */
    public boolean removeForward(int localPort, String serial) {
        String normalizedSerial = (serial == null || serial.isEmpty()) ? null : serial;
        String adbPath = null;
        ForwardRule matched = null;
        for (ForwardRule rule : activeForwards) {
            if (rule.localPort == localPort && Objects.equals(rule.serial, normalizedSerial)) {
                matched = rule;
                adbPath = rule.adbPath;
                break;
            }
        }
        if (adbPath == null && lastCapability != null && lastCapability.available) {
            adbPath = lastCapability.adbPath;
        }
        if (adbPath == null) {
            AdbCandidate candidate = selectBestCandidate();
            if (candidate != null) {
                adbPath = candidate.path;
            }
        }
        if (matched != null) {
            activeForwards.remove(matched);
        }
        if (adbPath == null) {
            return false;
        }
        CommandResult res = executeRemoveForward(adbPath, localPort, normalizedSerial, config.cleanupTimeoutMs);
        return res != null && res.isSuccess();
    }

    /**
     * Cleans up all active port forwards created by this manager using a bounded timeout budget.
     * If removal observes a wedged {@code device offline} USB transport, issues a single bounded
     * non-destructive {@code adb reconnect offline} so the next session can connect immediately.
     */
    public void cleanupForwards() {
        List<ForwardRule> snapshot = new ArrayList<>(activeForwards);
        activeForwards.clear();
        String offlineAdbPath = null;
        String offlineSerial = null;
        for (ForwardRule rule : snapshot) {
            CommandResult res = executeRemoveForward(rule.adbPath, rule.localPort, rule.serial, config.cleanupTimeoutMs);
            if (isOfflineError(res)) {
                offlineAdbPath = rule.adbPath;
                offlineSerial = rule.serial;
            }
        }
        if (offlineAdbPath != null && config.autoRecoverOfflineDevice) {
            recoverOfflineTransport(offlineAdbPath, offlineSerial, buildProcessEnvironment(), false);
        }
    }

    public int getActiveForwardCount() {
        return activeForwards.size();
    }

    public synchronized void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                inShutdownHook = true;
                cleanupForwards();
            }
        }, "hfx-adb-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            shutdownHook = null;
        }
    }

    public synchronized void unregisterShutdownHook() {
        if (shutdownHook != null && !inShutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            shutdownHook = null;
        }
    }

    @Override
    public void close() {
        cleanupForwards();
        unregisterShutdownHook();
    }

    private CommandResult executeRemoveForward(String adbPath, int localPort, String serial, long timeoutMs) {
        List<String> cmd = buildBaseCommand(adbPath, serial);
        cmd.add("forward");
        cmd.add("--remove");
        cmd.add("tcp:" + localPort);
        try {
            return commandExecutor.execute(
                    cmd, buildProcessEnvironment(), config.workingDirectory, timeoutMs);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(-1, "", e.getMessage());
        }
    }

    private List<AdbDeviceInfo> listDevicesLong(String adbPath, ProcessEnvSpec envSpec, long timeoutMs) {
        List<String> cmd = new ArrayList<>(3);
        cmd.add(adbPath);
        cmd.add("devices");
        cmd.add("-l");
        try {
            CommandResult res = commandExecutor.execute(cmd, envSpec, config.workingDirectory, timeoutMs);
            if (!res.isSuccess()) {
                return Collections.emptyList();
            }
            return parseDevicesLongOutput(res.stdout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyList();
        }
    }

    private Map<String, String> listDevices(String adbPath, ProcessEnvSpec envSpec, long timeoutMs) {
        List<String> cmd = new ArrayList<>(2);
        cmd.add(adbPath);
        cmd.add("devices");
        try {
            CommandResult res = commandExecutor.execute(cmd, envSpec, config.workingDirectory, timeoutMs);
            if (!res.isSuccess()) {
                return Collections.emptyMap();
            }
            return parseDevicesOutput(res.stdout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyMap();
        }
    }

    private static boolean hasOfflineDevice(Map<String, String> devices, String serial) {
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        if (serial != null && !serial.isEmpty()) {
            return "offline".equals(devices.get(serial));
        }
        for (String st : devices.values()) {
            if ("offline".equals(st)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOnlineTarget(Map<String, String> devices, String serial) {
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        if (serial != null && !serial.isEmpty()) {
            return "device".equals(devices.get(serial));
        }
        for (String st : devices.values()) {
            if ("device".equals(st)) {
                return true;
            }
        }
        return false;
    }

    private boolean recoverOfflineTransport(String adbPath, String serial, ProcessEnvSpec envSpec, boolean waitForOnline) {
        emitDiagnostic("ADB_OFFLINE_RECOVERY",
                "Detected offline/stalled ADB USB transport"
                        + (serial != null && !serial.isEmpty() ? " (" + serial + ")" : "")
                        + "; issuing targeted 'adb reconnect offline' without restarting shared adb daemon.");

        long cmdTimeout = waitForOnline
                ? Math.min(config.commandTimeoutMs, 4_000L)
                : Math.min(config.cleanupTimeoutMs, 2_000L);
        List<String> reconnectOffline = new ArrayList<>(3);
        reconnectOffline.add(adbPath);
        reconnectOffline.add("reconnect");
        reconnectOffline.add("offline");
        try {
            commandExecutor.execute(reconnectOffline, envSpec, config.workingDirectory, cmdTimeout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        if (!waitForOnline) {
            return false;
        }
        if (awaitDeviceOnline(adbPath, serial, envSpec, config.offlineRecoveryWaitMs)) {
            return true;
        }

        if (serial != null && !serial.isEmpty()) {
            List<String> targetedReconnect = buildBaseCommand(adbPath, serial);
            targetedReconnect.add("reconnect");
            try {
                commandExecutor.execute(targetedReconnect, envSpec, config.workingDirectory, cmdTimeout);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            return awaitDeviceOnline(adbPath, serial, envSpec, Math.min(2_000L, config.offlineRecoveryWaitMs));
        }
        return false;
    }

    private boolean awaitDeviceOnline(String adbPath, String serial, ProcessEnvSpec envSpec, long waitBudgetMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitBudgetMs);
        long queryTimeout = Math.min(config.commandTimeoutMs, 2_000L);
        while (true) {
            Map<String, String> devices = listDevices(adbPath, envSpec, queryTimeout);
            if (hasOnlineTarget(devices, serial)) {
                return true;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return false;
            }
            long sleepMs = Math.min(config.offlinePollIntervalMs, remaining);
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private void refreshDeviceFeaturesAfterRecovery(IpcDtos.AdbCapabilityInfo cap, String serial, ProcessEnvSpec envSpec) {
        if (cap == null || cap.adbPath == null) {
            return;
        }
        Set<String> hostFeatures = queryFeatures(cap.adbPath, null, "host-features", envSpec);
        Set<String> deviceFeatures = queryFeatures(cap.adbPath, serial, "features", envSpec);
        boolean hostHasLibusb = hostFeatures.contains("libusb");
        boolean hostHasDelayedAck = hostFeatures.contains("delayed_ack");
        boolean deviceChecked = !deviceFeatures.isEmpty();
        cap.hasLibusb = hostHasLibusb;
        cap.hasDelayedAck = hostHasDelayedAck && (!deviceChecked || deviceFeatures.contains("delayed_ack"));
        cap.recommendedStreams = computeRecommendedStreams(cap.hasLibusb, cap.hasDelayedAck);
        this.lastCapability = cap;
    }

    private Set<String> queryFeatures(String adbPath, String serial, String subcommand, ProcessEnvSpec envSpec) {
        List<String> cmd = buildBaseCommand(adbPath, serial);
        cmd.add(subcommand);
        try {
            CommandResult res = commandExecutor.execute(cmd, envSpec, config.workingDirectory, config.commandTimeoutMs);
            if (!res.isSuccess()) {
                return Collections.emptySet();
            }
            return parseFeatureList(res.stdout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptySet();
        }
    }

    private AdbVersion probeVersion(String adbPath, ProcessEnvSpec envSpec) {
        List<String> cmd = new ArrayList<>(2);
        cmd.add(adbPath);
        cmd.add("version");
        try {
            CommandResult res = commandExecutor.execute(cmd, envSpec, config.workingDirectory, config.commandTimeoutMs);
            if (!res.isSuccess()) {
                return null;
            }
            String combined = res.stdout + "\n" + res.stderr;
            return AdbVersion.parse(combined);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private void addCandidateIfExists(Map<String, CandidatePath> unique, File file, int priority) {
        if (file == null || !envProvider.isExecutableFile(file)) {
            return;
        }
        String canonical = envProvider.canonicalizePath(file);
        String key = envProvider.isWindows() ? canonical.toLowerCase(Locale.ROOT) : canonical;
        if (!unique.containsKey(key)) {
            unique.put(key, new CandidatePath(canonical, priority));
        }
    }

    private void emitWarning(String code, String message) {
        DiagnosticListener listener = this.diagnosticListener;
        if (listener != null) {
            listener.onEvent(IpcEvents.ADB_VERSION_WARNING,
                    new IpcPayloads.DiagnosticWarningPayload(code, message));
        }
    }

    private void emitDiagnostic(String code, String message) {
        DiagnosticListener listener = this.diagnosticListener;
        if (listener != null) {
            listener.onEvent(IpcEvents.DIAGNOSTIC_WARNING,
                    new IpcPayloads.DiagnosticWarningPayload(code, message));
        }
    }

    private static List<String> buildBaseCommand(String adbPath, String serial) {
        List<String> cmd = new ArrayList<>(6);
        cmd.add(adbPath);
        if (serial != null && !serial.isEmpty()) {
            cmd.add("-s");
            cmd.add(serial);
        }
        return cmd;
    }

    private static final class CandidatePath {
        final String path;
        final int priority;

        CandidatePath(String path, int priority) {
            this.path = path;
            this.priority = priority;
        }
    }

    /**
     * Default OS process executor using {@link ProcessBuilder#environment()} strictly at child-process scope.
     */
    public static final class DefaultCommandExecutor implements CommandExecutor {
        @Override
        public CommandResult execute(List<String> command, ProcessEnvSpec envSpec, File workingDir, long timeoutMs)
                throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null && workingDir.isDirectory()) {
                pb.directory(workingDir);
            }
            if (envSpec != null) {
                Map<String, String> env = pb.environment();
                for (String removeKey : envSpec.removeVars) {
                    env.remove(removeKey);
                }
                env.putAll(envSpec.setVars);
            }
            Process process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            StreamCollector outCollector = new StreamCollector(process.getInputStream());
            StreamCollector errCollector = new StreamCollector(process.getErrorStream());
            Thread outThread = new Thread(outCollector, "hfx-adb-stdout");
            Thread errThread = new Thread(errCollector, "hfx-adb-stderr");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_COMMAND_TIMEOUT_MS;
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                // Only terminate the hung client adb invocation; never kill descendants so a shared
                // adb server daemon used by other tools (e.g. Android Studio) is not collaterally killed.
                process.destroyForcibly();
                closeProcessStreamsQuietly(process);
                outThread.interrupt();
                errThread.interrupt();
                outThread.join(300);
                errThread.join(300);
                return new CommandResult(-1, outCollector.getText(),
                        errCollector.getText() + "\nADB command timed out after " + effectiveTimeout + " ms");
            }
            outThread.join(300);
            errThread.join(300);
            // Guard against Windows handle inheritance when adb spawns a detached background daemon
            // that keeps stdout/stderr pipe ends open after the short-lived adb client exits.
            if (outThread.isAlive() || errThread.isAlive()) {
                closeProcessStreamsQuietly(process);
                outThread.interrupt();
                errThread.interrupt();
                outThread.join(200);
                errThread.join(200);
            }
            return new CommandResult(process.exitValue(), outCollector.getText(), errCollector.getText());
        }

        private static void closeProcessStreamsQuietly(Process process) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getErrorStream().close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
            }
        }

        String getText() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Default host environment provider.
     */
    public static final class SystemEnvironmentProvider implements EnvironmentProvider {
        @Override
        public String getEnv(String name) {
            return System.getenv(name);
        }

        @Override
        public boolean isWindows() {
            String os = System.getProperty("os.name", "");
            return os.toLowerCase(Locale.ROOT).contains("win");
        }

        @Override
        public String getPathSeparator() {
            return File.pathSeparator;
        }

        @Override
        public boolean isExecutableFile(File file) {
            return file != null && file.isFile();
        }

        @Override
        public String canonicalizePath(File file) {
            try {
                return file.getCanonicalPath();
            } catch (IOException e) {
                return file.getAbsolutePath();
            }
        }
    }
}
