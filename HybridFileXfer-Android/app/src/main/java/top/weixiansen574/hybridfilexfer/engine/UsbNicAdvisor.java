package top.weixiansen574.hybridfilexfer.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

/**
 * Pure-Java headless network & USB NIC advisor for the Android engine layer.
 *
 * <p>Architecture red line: this class and the entire {@code engine} package MUST NEVER reference
 * {@code android.app.Activity}, {@code android.view.*}, {@code android.widget.*}, {@code Toast},
 * {@code RecyclerView}, or {@code PrintStream}. All core algorithms accept injectable inputs so
 * they can be verified directly in standard JVM unit tests.</p>
 */
public class UsbNicAdvisor {

    public static final String DEFAULT_UDC_ROOT_PATH = "/sys/class/udc";

    public static final String SPEED_SUPER_PLUS = "super-speed-plus";
    public static final String SPEED_SUPER = "super-speed";
    public static final String SPEED_HIGH = "high-speed";
    public static final String SPEED_FULL = "full-speed";
    public static final String SPEED_LOW = "low-speed";
    public static final String SPEED_UNKNOWN = "unknown";

    public static final String REASON_USB_NIC_ACTIVE_AUTO_DISABLED = "usb_nic_active_auto_disabled";
    public static final String REASON_VPN_DEFAULT_DISABLED = "vpn_default_disabled";
    public static final String CODE_VPN_ACTIVE_WARNING = "ANDROID_VPN_ACTIVE";

    private static final List<String> DEFAULT_NOISE_PREFIXES = Collections.unmodifiableList(
            Arrays.asList("rmnet_data", "dummy", "ifb", "ovnet", "v4-", "p2p"));

    private static final List<String> DEFAULT_VPN_PREFIXES = Collections.unmodifiableList(
            Arrays.asList("tun", "wg"));

    private static final List<String> DEFAULT_USB_NIC_PREFIXES = Collections.unmodifiableList(
            Arrays.asList("ncm", "usb", "rndis"));

    /**
     * Tunable configuration preserving M2 defaults while enabling future user-side settings.
     */
    public static final class AdvisorConfig {
        public boolean autoDisableAdbWhenUsbNicActive = true;
        public boolean includeVpnInterfaces = true;
        public boolean filterLinkLocal = true;
        public File udcRootDir = new File(DEFAULT_UDC_ROOT_PATH);
        public final List<String> extraNoisePrefixes = new ArrayList<>();
        public final List<String> extraVpnPrefixes = new ArrayList<>();

        public AdvisorConfig() {
        }

        public AdvisorConfig copy() {
            AdvisorConfig c = new AdvisorConfig();
            c.autoDisableAdbWhenUsbNicActive = this.autoDisableAdbWhenUsbNicActive;
            c.includeVpnInterfaces = this.includeVpnInterfaces;
            c.filterLinkLocal = this.filterLinkLocal;
            c.udcRootDir = this.udcRootDir != null ? this.udcRootDir : new File(DEFAULT_UDC_ROOT_PATH);
            c.extraNoisePrefixes.addAll(this.extraNoisePrefixes);
            c.extraVpnPrefixes.addAll(this.extraVpnPrefixes);
            return c;
        }
    }

    /**
     * Platform-neutral raw IPv4 interface entry enabling deterministic JVM unit testing.
     */
    public static final class RawInterface {
        public final String name;
        public final String displayName;
        public final Inet4Address address;
        public final int prefixLength;
        public final boolean up;
        public final boolean loopback;

        public RawInterface(String name, String displayName, Inet4Address address,
                            int prefixLength, boolean up, boolean loopback) {
            this.name = name != null ? name : "";
            this.displayName = (displayName != null && !displayName.isEmpty()) ? displayName : this.name;
            this.address = address;
            this.prefixLength = prefixLength;
            this.up = up;
            this.loopback = loopback;
        }
    }

    /**
     * Structured advisory result consumed by engine snapshots, IPC events, and thin UI adapters.
     */
    public static final class AdviceResult {
        public final List<IpcDtos.NicInfo> nics;
        public final IpcPayloads.RailConflictResolvedPayload conflictResolved;
        public final String udcSpeed;
        public final boolean vpnActive;
        public final IpcPayloads.DiagnosticWarningPayload vpnWarning;

        public AdviceResult(List<IpcDtos.NicInfo> nics,
                            IpcPayloads.RailConflictResolvedPayload conflictResolved,
                            String udcSpeed,
                            boolean vpnActive,
                            IpcPayloads.DiagnosticWarningPayload vpnWarning) {
            this.nics = Collections.unmodifiableList(new ArrayList<>(nics));
            this.conflictResolved = conflictResolved;
            this.udcSpeed = udcSpeed != null ? udcSpeed : SPEED_UNKNOWN;
            this.vpnActive = vpnActive;
            this.vpnWarning = vpnWarning;
        }

        public boolean hasActiveUsbNic() {
            for (IpcDtos.NicInfo nic : nics) {
                if (nic.usbNic) {
                    return true;
                }
            }
            return false;
        }
    }

    private final AdvisorConfig config;

    public UsbNicAdvisor() {
        this(new AdvisorConfig());
    }

    public UsbNicAdvisor(AdvisorConfig config) {
        this.config = config != null ? config.copy() : new AdvisorConfig();
    }

    public AdvisorConfig getConfig() {
        return config.copy();
    }

    /**
     * Enumerates local Android interfaces and probes UDC physical speed.
     */
    public AdviceResult advise() throws SocketException {
        return advise(enumerateSystemInterfaces(), probeUdcSpeed(config.udcRootDir));
    }

    /**
     * Pure-Java core recommendation engine: filters noise interfaces, promotes USB NICs
     * ({@code ncm*}, {@code usb*}, {@code rndis*}), flags VPN interfaces ({@code tun*}, {@code wg*}),
     * and enforces same-cable mutual exclusion between active USB NICs and {@code USB_ADB}.
     */
    public AdviceResult advise(List<RawInterface> rawInterfaces) {
        return advise(rawInterfaces, probeUdcSpeed(config.udcRootDir));
    }

    public AdviceResult advise(List<RawInterface> rawInterfaces, String udcSpeed) {
        List<ScoredNic> scored = new ArrayList<>();
        List<String> activeUsbNicNames = new ArrayList<>();
        List<String> activeVpnNames = new ArrayList<>();

        if (rawInterfaces != null) {
            for (RawInterface raw : rawInterfaces) {
                if (raw == null || raw.address == null) {
                    continue;
                }
                if (!raw.up || raw.loopback || raw.address.isLoopbackAddress()
                        || raw.address.isAnyLocalAddress() || raw.address.isMulticastAddress()) {
                    continue;
                }
                String ifName = chooseInterfaceName(raw);
                if (ifName.isEmpty() || isNoiseInterface(ifName)) {
                    continue;
                }
                byte[] addrBytes = raw.address.getAddress();
                if (config.filterLinkLocal && (addrBytes[0] & 0xFF) == 169 && (addrBytes[1] & 0xFF) == 254) {
                    continue;
                }

                boolean isVpn = isVpnInterface(ifName);
                if (isVpn) {
                    activeVpnNames.add(ifName);
                    if (!config.includeVpnInterfaces) {
                        continue;
                    }
                    IpcDtos.NicInfo vpnDto = createNicDto(raw, ifName, RailType.OTHER, false, true, false, false);
                    vpnDto.reason = REASON_VPN_DEFAULT_DISABLED;
                    scored.add(new ScoredNic(vpnDto, 6));
                    continue;
                }

                boolean isUsbNic = isUsbNicInterface(ifName);
                RailType railType = isUsbNic ? RailType.USB_NIC : RailType.inferFromInterfaceName(ifName);
                if (isUsbNic) {
                    activeUsbNicNames.add(ifName);
                }

                IpcDtos.NicInfo dto = createNicDto(raw, ifName, railType, isUsbNic, false, true, true);
                scored.add(new ScoredNic(dto, rankOfRail(railType, true)));
            }
        }

        boolean usbNicActive = !activeUsbNicNames.isEmpty();
        boolean disableAdb = usbNicActive && config.autoDisableAdbWhenUsbNicActive;

        IpcDtos.NicInfo adbDto = new IpcDtos.NicInfo();
        adbDto.name = ProtocolConstants.USB_ADB_RAIL_NAME;
        adbDto.displayName = ProtocolConstants.USB_ADB_RAIL_NAME;
        adbDto.ip = "127.0.0.1";
        adbDto.prefixLength = 8;
        adbDto.railType = RailType.ADB.name();
        adbDto.usbNic = false;
        adbDto.vpn = false;
        adbDto.recommended = !disableAdb;
        adbDto.selected = !disableAdb;

        IpcPayloads.RailConflictResolvedPayload conflictPayload = null;
        if (disableAdb) {
            adbDto.reason = REASON_USB_NIC_ACTIVE_AUTO_DISABLED;
            conflictPayload = new IpcPayloads.RailConflictResolvedPayload();
            conflictPayload.disabledRail = ProtocolConstants.USB_ADB_RAIL_NAME;
            conflictPayload.activeUsbNics = new ArrayList<>(activeUsbNicNames);
            conflictPayload.reason = "Active USB NIC (" + joinStrings(activeUsbNicNames)
                    + ") detected; USB_ADB auto-disabled to prevent same-cable USB contention.";
        }
        scored.add(new ScoredNic(adbDto, disableAdb ? 5 : 1));

        Collections.sort(scored, new Comparator<ScoredNic>() {
            @Override
            public int compare(ScoredNic a, ScoredNic b) {
                int r = Integer.compare(a.rank, b.rank);
                if (r != 0) return r;
                int n = a.info.name.compareToIgnoreCase(b.info.name);
                if (n != 0) return n;
                return a.info.ip.compareTo(b.info.ip);
            }
        });

        List<IpcDtos.NicInfo> orderedNics = new ArrayList<>(scored.size());
        for (ScoredNic s : scored) {
            orderedNics.add(s.info);
        }

        boolean vpnActive = !activeVpnNames.isEmpty();
        IpcPayloads.DiagnosticWarningPayload vpnWarning = null;
        if (vpnActive) {
            vpnWarning = new IpcPayloads.DiagnosticWarningPayload(
                    CODE_VPN_ACTIVE_WARNING,
                    "Active VPN interface (" + joinStrings(activeVpnNames)
                            + ") detected; consider excluding HybridFileXfer in VPN split-tunnel rules for direct LAN/USB callbacks.");
        }

        return new AdviceResult(orderedNics, conflictPayload, normalizeUdcSpeed(udcSpeed), vpnActive, vpnWarning);
    }

    /**
     * Enumerates system IPv4 network interfaces via {@link NetworkInterface}.
     */
    public static List<RawInterface> enumerateSystemInterfaces() throws SocketException {
        List<RawInterface> result = new ArrayList<>();
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        if (nics == null) {
            return result;
        }
        while (nics.hasMoreElements()) {
            NetworkInterface nif = nics.nextElement();
            boolean up;
            boolean loopback;
            try {
                up = nif.isUp();
                loopback = nif.isLoopback();
            } catch (SocketException ignored) {
                continue;
            }
            List<InterfaceAddress> ifaceAddrs = nif.getInterfaceAddresses();
            if (ifaceAddrs != null && !ifaceAddrs.isEmpty()) {
                for (InterfaceAddress ia : ifaceAddrs) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address) {
                        int prefix = ia.getNetworkPrefixLength();
                        if (prefix <= 0 || prefix > 32) {
                            prefix = 24;
                        }
                        result.add(new RawInterface(
                                nif.getName(),
                                nif.getDisplayName(),
                                (Inet4Address) addr,
                                prefix,
                                up,
                                loopback));
                    }
                }
            } else {
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        result.add(new RawInterface(
                                nif.getName(),
                                nif.getDisplayName(),
                                (Inet4Address) addr,
                                24,
                                up,
                                loopback));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Converts selected {@link IpcDtos.NicInfo} items into protocol {@link ServerNetInterface} models.
     */
    public static List<ServerNetInterface> toServerNetInterfaces(List<IpcDtos.NicInfo> nicInfos)
            throws UnknownHostException {
        if (nicInfos == null || nicInfos.isEmpty()) {
            return Collections.emptyList();
        }
        List<ServerNetInterface> out = new ArrayList<>();
        for (IpcDtos.NicInfo nic : nicInfos) {
            if (nic == null || !nic.selected || nic.ip == null || nic.ip.trim().isEmpty()) {
                continue;
            }
            InetAddress remoteAddr = InetAddress.getByName(nic.ip.trim());
            InetAddress bindAddr = null;
            if (nic.clientBindIp != null && !nic.clientBindIp.trim().isEmpty()) {
                bindAddr = InetAddress.getByName(nic.clientBindIp.trim());
            }
            RailType railType = parseRailType(nic.railType, nic.name);
            out.add(new ServerNetInterface(nic.name, remoteAddr, bindAddr, railType, 1));
        }
        return out;
    }

    /**
     * Read-only probe of {@code /sys/class/udc/<controller>/current_speed}.
     * Safe when directory is absent or unreadable (returns {@link #SPEED_UNKNOWN}).
     */
    public String probeUdcSpeed() {
        return probeUdcSpeed(config.udcRootDir);
    }

    public static String probeUdcSpeed(File udcRootDir) {
        if (udcRootDir == null) {
            return SPEED_UNKNOWN;
        }
        File[] controllers;
        try {
            if (!udcRootDir.isDirectory()) {
                return SPEED_UNKNOWN;
            }
            controllers = udcRootDir.listFiles();
        } catch (SecurityException e) {
            return SPEED_UNKNOWN;
        }
        if (controllers == null || controllers.length == 0) {
            return SPEED_UNKNOWN;
        }

        String bestSpeed = SPEED_UNKNOWN;
        int bestRank = 0;
        for (File controller : controllers) {
            if (controller == null) {
                continue;
            }
            File speedFile = new File(controller, "current_speed");
            String raw = readFirstLineQuietly(speedFile);
            String normalized = normalizeUdcSpeed(raw);
            int rank = udcSpeedRank(normalized);
            if (rank > bestRank) {
                bestRank = rank;
                bestSpeed = normalized;
            }
        }
        return bestSpeed;
    }

    public static String normalizeUdcSpeed(String rawSpeed) {
        if (rawSpeed == null) {
            return SPEED_UNKNOWN;
        }
        String s = rawSpeed.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "unknown".equals(s)) {
            return SPEED_UNKNOWN;
        }
        if (s.startsWith("super-speed-plus") || "superspeed-plus".equals(s)) {
            return SPEED_SUPER_PLUS;
        }
        if (s.startsWith("super-speed") || "superspeed".equals(s)) {
            return SPEED_SUPER;
        }
        if (s.startsWith("high-speed") || "highspeed".equals(s)) {
            return SPEED_HIGH;
        }
        if (s.startsWith("full-speed") || "fullspeed".equals(s)) {
            return SPEED_FULL;
        }
        if (s.startsWith("low-speed") || "lowspeed".equals(s)) {
            return SPEED_LOW;
        }
        return SPEED_UNKNOWN;
    }

    public static String formatUdcSpeedShortLabel(String udcSpeed) {
        String normalized = normalizeUdcSpeed(udcSpeed);
        if (SPEED_SUPER_PLUS.equals(normalized)) {
            return "USB 3.x 10G+";
        }
        if (SPEED_SUPER.equals(normalized)) {
            return "USB 3.0 5G";
        }
        if (SPEED_HIGH.equals(normalized)) {
            return "USB 2.0 480M";
        }
        if (SPEED_FULL.equals(normalized)) {
            return "USB 1.1 12M";
        }
        if (SPEED_LOW.equals(normalized)) {
            return "USB 1.0";
        }
        return "";
    }

    public boolean isNoiseInterface(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("lo".equals(lower) || lower.startsWith("lo:")) {
            return true;
        }
        for (String prefix : DEFAULT_NOISE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : config.extraNoisePrefixes) {
            if (prefix != null && !prefix.isEmpty() && lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public boolean isVpnInterface(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : DEFAULT_VPN_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : config.extraVpnPrefixes) {
            if (prefix != null && !prefix.isEmpty() && lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUsbNicInterface(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : DEFAULT_USB_NIC_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static IpcDtos.NicInfo createNicDto(RawInterface raw, String ifName, RailType railType,
                                                boolean usbNic, boolean vpn,
                                                boolean recommended, boolean selected) {
        IpcDtos.NicInfo dto = new IpcDtos.NicInfo();
        dto.name = ifName;
        dto.displayName = raw.displayName;
        dto.ip = raw.address.getHostAddress();
        dto.prefixLength = raw.prefixLength;
        dto.railType = railType.name();
        dto.usbNic = usbNic;
        dto.vpn = vpn;
        dto.recommended = recommended;
        dto.selected = selected;
        return dto;
    }

    private static String chooseInterfaceName(RawInterface raw) {
        if (raw.name != null && !raw.name.trim().isEmpty()) {
            return raw.name.trim();
        }
        return raw.displayName != null ? raw.displayName.trim() : "";
    }

    private static int rankOfRail(RailType type, boolean selected) {
        if (type == null) {
            return 4;
        }
        switch (type) {
            case USB_NIC:
                return 0;
            case ADB:
                return selected ? 1 : 5;
            case WIFI:
                return 2;
            case ETH:
                return 3;
            default:
                return 4;
        }
    }

    private static int udcSpeedRank(String normalizedSpeed) {
        if (SPEED_SUPER_PLUS.equals(normalizedSpeed)) return 5;
        if (SPEED_SUPER.equals(normalizedSpeed)) return 4;
        if (SPEED_HIGH.equals(normalizedSpeed)) return 3;
        if (SPEED_FULL.equals(normalizedSpeed)) return 2;
        if (SPEED_LOW.equals(normalizedSpeed)) return 1;
        return 0;
    }

    private static RailType parseRailType(String railTypeName, String ifName) {
        if (railTypeName != null && !railTypeName.isEmpty()) {
            try {
                return RailType.valueOf(railTypeName);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return RailType.inferFromInterfaceName(ifName);
    }

    private static String readFirstLineQuietly(File file) {
        if (file == null) {
            return null;
        }
        try {
            if (!file.isFile() || !file.canRead()) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                return reader.readLine();
            }
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static String joinStrings(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static final class ScoredNic {
        final IpcDtos.NicInfo info;
        final int rank;

        ScoredNic(IpcDtos.NicInfo info, int rank) {
            this.info = info;
            this.rank = rank;
        }
    }
}
