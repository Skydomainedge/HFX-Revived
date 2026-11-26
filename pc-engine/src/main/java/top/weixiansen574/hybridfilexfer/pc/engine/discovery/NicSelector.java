package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

/**
 * PC network interface filter and prioritizer.
 *
 * <p>Filters out loopback, link-local ({@code 169.254.0.0/16}), proxy fake-IP benchmark range
 * ({@code 198.18.0.0/15}), and virtual/VPN/tunnel adapters, then ranks real IPv4 interfaces in
 * priority order: {@code USB NIC > Wired Ethernet > Wi-Fi > Other}.</p>
 */
public class NicSelector {

    public static final String DEFAULT_BLACKLIST_REGEX =
            "vmware|vmnet|virtualbox|vbox|hyper-v|vethernet|meta|clash|mihomo|wintun|"
                    + "wireguard|tailscale|zerotier|natpierce|teredo|isatap|bluetooth|docker|wsl";

    private static final Pattern DEFAULT_BLACKLIST_PATTERN =
            Pattern.compile(DEFAULT_BLACKLIST_REGEX, Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLICIT_USB_NIC_PATTERN =
            Pattern.compile("ncm|rndis|remote\\s*ndis|usbncm|usb\\s*ncm|cdc.*eth|tether|android.*(usb|eth|net)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern WIFI_PATTERN =
            Pattern.compile("wlan|wi-fi|wifi|wireless|802\\.11|wi\\s*fi", Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERIC_USB_ETH_PATTERN =
            Pattern.compile("\\busb\\b.*(eth|lan|gbe|net|nic|adapter|gigabit)|^usb\\d*$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern WIRED_ETH_PATTERN =
            Pattern.compile("\\beth\\d*\\b|\\ben[0-9p]|ethernet|以太网|gbe|gigabit|pcie.*(eth|lan|gbe)|realtek.*family|intel.*ethernet",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Tunable filter configuration allowing future user-side overrides (custom ignore patterns or allowlists).
     */
    public static final class NicSelectorConfig {
        public final List<String> extraBlacklistPatterns = new ArrayList<>();
        public final List<String> allowlistNames = new ArrayList<>();
        public boolean filterLinkLocal = true;
        public boolean filterFakeIpRange = true;
        public boolean requireUp = true;

        public NicSelectorConfig() {
        }

        public NicSelectorConfig copy() {
            NicSelectorConfig c = new NicSelectorConfig();
            c.extraBlacklistPatterns.addAll(this.extraBlacklistPatterns);
            c.allowlistNames.addAll(this.allowlistNames);
            c.filterLinkLocal = this.filterLinkLocal;
            c.filterFakeIpRange = this.filterFakeIpRange;
            c.requireUp = this.requireUp;
            return c;
        }
    }

    /**
     * Platform-neutral raw IPv4 NIC record enabling deterministic unit testing.
     */
    public static final class RawNic {
        public final String name;
        public final String displayName;
        public final Inet4Address address;
        public final int prefixLength;
        public final boolean up;
        public final boolean loopback;
        public final boolean virtual;
        public final boolean pointToPoint;

        public RawNic(String name, String displayName, Inet4Address address, int prefixLength,
                      boolean up, boolean loopback, boolean virtual, boolean pointToPoint) {
            this.name = name != null ? name : "";
            this.displayName = displayName != null ? displayName : this.name;
            this.address = address;
            this.prefixLength = prefixLength;
            this.up = up;
            this.loopback = loopback;
            this.virtual = virtual;
            this.pointToPoint = pointToPoint;
        }
    }

    private final NicSelectorConfig config;
    private final List<Pattern> extraBlacklist;

    public NicSelector() {
        this(new NicSelectorConfig());
    }

    public NicSelector(NicSelectorConfig config) {
        this.config = config != null ? config.copy() : new NicSelectorConfig();
        List<Pattern> compiled = new ArrayList<>();
        for (String regex : this.config.extraBlacklistPatterns) {
            if (regex != null && !regex.isEmpty()) {
                compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            }
        }
        this.extraBlacklist = Collections.unmodifiableList(compiled);
    }

    /**
     * Enumerates local host IPv4 NICs and returns filtered, priority-sorted {@link IpcDtos.NicInfo} list.
     */
    public List<IpcDtos.NicInfo> selectLocalNics() {
        return select(enumerateLocalRawNics());
    }

    /**
     * Enumerates raw local IPv4 interfaces from the host OS.
     */
    public static List<RawNic> enumerateLocalRawNics() {
        List<RawNic> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                return out;
            }
            while (nics.hasMoreElements()) {
                NetworkInterface nif = nics.nextElement();
                boolean up;
                boolean loopback;
                boolean virtual;
                boolean p2p;
                try {
                    up = nif.isUp();
                    loopback = nif.isLoopback();
                    virtual = nif.isVirtual();
                    p2p = nif.isPointToPoint();
                } catch (SocketException ignored) {
                    continue;
                }
                List<InterfaceAddress> ifaceAddrs = nif.getInterfaceAddresses();
                if (ifaceAddrs == null) {
                    continue;
                }
                for (InterfaceAddress ifAddr : ifaceAddrs) {
                    InetAddress addr = ifAddr.getAddress();
                    if (addr instanceof Inet4Address) {
                        int prefix = ifAddr.getNetworkPrefixLength();
                        if (prefix <= 0 || prefix > 32) {
                            prefix = 24;
                        }
                        out.add(new RawNic(
                                nif.getName(),
                                nif.getDisplayName(),
                                (Inet4Address) addr,
                                prefix,
                                up,
                                loopback,
                                virtual,
                                p2p));
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }

    /**
     * Filters and sorts raw IPv4 interfaces according to M2 rules.
     */
    public List<IpcDtos.NicInfo> select(List<RawNic> rawNics) {
        if (rawNics == null || rawNics.isEmpty()) {
            return Collections.emptyList();
        }
        List<ScoredNic> accepted = new ArrayList<>();
        for (RawNic nic : rawNics) {
            if (!isEligible(nic)) {
                continue;
            }
            RailType railType = classifyRailType(nic.name, nic.displayName);
            IpcDtos.NicInfo dto = new IpcDtos.NicInfo();
            dto.name = nic.name;
            dto.displayName = nic.displayName;
            dto.ip = nic.address.getHostAddress();
            dto.prefixLength = nic.prefixLength;
            dto.railType = railType.name();
            dto.usbNic = (railType == RailType.USB_NIC);
            dto.vpn = false;
            dto.recommended = true;
            dto.selected = true;
            accepted.add(new ScoredNic(dto, priorityOf(railType)));
        }

        accepted.sort(new Comparator<ScoredNic>() {
            @Override
            public int compare(ScoredNic a, ScoredNic b) {
                int p = Integer.compare(a.priority, b.priority);
                if (p != 0) return p;
                int n = a.info.name.compareToIgnoreCase(b.info.name);
                if (n != 0) return n;
                return a.info.ip.compareTo(b.info.ip);
            }
        });

        List<IpcDtos.NicInfo> result = new ArrayList<>(accepted.size());
        for (ScoredNic s : accepted) {
            result.add(s.info);
        }
        return result;
    }

    public boolean isEligible(RawNic nic) {
        if (nic == null || nic.address == null) {
            return false;
        }
        if (config.requireUp && !nic.up) {
            return false;
        }
        if (nic.loopback || nic.address.isLoopbackAddress() || nic.address.isAnyLocalAddress()
                || nic.address.isMulticastAddress()) {
            return false;
        }
        byte[] b = nic.address.getAddress();
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        if (b0 == 127) {
            return false;
        }
        if (config.filterLinkLocal && b0 == 169 && b1 == 254) {
            return false;
        }
        // 198.18.0.0/15 (198.18.0.0 - 198.19.255.255): Clash/Mihomo/Meta TUN fake-IP range
        if (config.filterFakeIpRange && b0 == 198 && (b1 == 18 || b1 == 19)) {
            return false;
        }
        if (isExplicitlyAllowlisted(nic.name, nic.displayName)) {
            return true;
        }
        if (nic.virtual) {
            return false;
        }
        if (matchesBlacklist(nic.name, nic.displayName)) {
            return false;
        }
        return true;
    }

    public boolean matchesBlacklist(String name, String displayName) {
        String combined = ((name != null ? name : "") + " " + (displayName != null ? displayName : ""));
        if (DEFAULT_BLACKLIST_PATTERN.matcher(combined).find()) {
            return true;
        }
        for (Pattern extra : extraBlacklist) {
            if (extra.matcher(combined).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitlyAllowlisted(String name, String displayName) {
        for (String allowed : config.allowlistNames) {
            if (allowed == null || allowed.isEmpty()) continue;
            if (allowed.equalsIgnoreCase(name) || allowed.equalsIgnoreCase(displayName)) {
                return true;
            }
        }
        return false;
    }

    public static RailType classifyRailType(String name, String displayName) {
        String safeName = name != null ? name : "";
        String safeDisplay = displayName != null ? displayName : "";
        String lowerName = safeName.toLowerCase(Locale.ROOT);
        String combined = safeName + " " + safeDisplay;

        if (lowerName.startsWith("ncm") || lowerName.startsWith("rndis")
                || EXPLICIT_USB_NIC_PATTERN.matcher(combined).find()) {
            return RailType.USB_NIC;
        }
        if (WIFI_PATTERN.matcher(combined).find()) {
            return RailType.WIFI;
        }
        if (lowerName.startsWith("usb") || GENERIC_USB_ETH_PATTERN.matcher(combined).find()) {
            return RailType.USB_NIC;
        }
        if (WIRED_ETH_PATTERN.matcher(combined).find()) {
            return RailType.ETH;
        }
        return RailType.OTHER;
    }

    public static int priorityOf(RailType type) {
        if (type == null) {
            return 3;
        }
        switch (type) {
            case USB_NIC:
                return 0;
            case ETH:
                return 1;
            case WIFI:
                return 2;
            default:
                return 3;
        }
    }

    private static final class ScoredNic {
        final IpcDtos.NicInfo info;
        final int priority;

        ScoredNic(IpcDtos.NicInfo info, int priority) {
            this.info = info;
            this.priority = priority;
        }
    }
}
