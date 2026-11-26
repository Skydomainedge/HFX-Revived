package top.weixiansen574.hybridfilexfer.pc.engine.transport;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Supplier;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.pc.engine.discovery.NicSelector;

/**
 * Resolves the best local IPv4 source bind address matching a remote rail's subnet CIDR.
 *
 * <p>Prevents Windows multi-gateway / proxy TUN (e.g. {@code Meta Tunnel 198.18.0.1/30}) route
 * hijacking by binding each non-loopback data channel socket to the local physical/USB NIC on the
 * exact same subnet (longest-prefix match, tie-broken by {@link NicSelector} priority).</p>
 */
public class SubnetBindResolver {

    private final Supplier<List<IpcDtos.NicInfo>> nicSupplier;

    public SubnetBindResolver() {
        this(new NicSelector());
    }

    public SubnetBindResolver(final NicSelector selector) {
        this(new Supplier<List<IpcDtos.NicInfo>>() {
            @Override
            public List<IpcDtos.NicInfo> get() {
                return selector.selectLocalNics();
            }
        });
    }

    public SubnetBindResolver(Supplier<List<IpcDtos.NicInfo>> nicSupplier) {
        this.nicSupplier = nicSupplier;
    }

    /**
     * Resolves a local same-subnet IPv4 bind address for {@code remoteAddress}, or {@code null}
     * if {@code remoteAddress} is loopback, non-IPv4, or no real local interface shares its subnet.
     */
    public InetAddress resolveLocalBindAddress(InetAddress remoteAddress) {
        if (!(remoteAddress instanceof Inet4Address) || remoteAddress.isLoopbackAddress()) {
            return null;
        }
        List<IpcDtos.NicInfo> candidates = nicSupplier != null ? nicSupplier.get() : null;
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        int remoteInt = ipv4ToInt((Inet4Address) remoteAddress);
        Inet4Address bestAddr = null;
        int bestPrefix = -1;

        for (IpcDtos.NicInfo nic : candidates) {
            if (nic == null || nic.ip == null || nic.prefixLength < 1 || nic.prefixLength > 32) {
                continue;
            }
            Inet4Address localAddr = parseIpv4Literal(nic.ip);
            if (localAddr == null || localAddr.isLoopbackAddress()) {
                continue;
            }
            int localInt = ipv4ToInt(localAddr);
            int mask = prefixLengthToMask(nic.prefixLength);
            if ((remoteInt & mask) == (localInt & mask)) {
                // Longest prefix wins; ties preserve NicSelector order (USB_NIC > ETH > WIFI)
                if (nic.prefixLength > bestPrefix) {
                    bestPrefix = nic.prefixLength;
                    bestAddr = localAddr;
                }
            }
        }
        return bestAddr;
    }

    public static boolean isSameSubnet(Inet4Address a, Inet4Address b, int prefixLength) {
        if (a == null || b == null || prefixLength < 1 || prefixLength > 32) {
            return false;
        }
        int mask = prefixLengthToMask(prefixLength);
        return (ipv4ToInt(a) & mask) == (ipv4ToInt(b) & mask);
    }

    public static int prefixLengthToMask(int prefixLength) {
        if (prefixLength <= 0) {
            return 0;
        }
        if (prefixLength >= 32) {
            return 0xFFFFFFFF;
        }
        return 0xFFFFFFFF << (32 - prefixLength);
    }

    public static int ipv4ToInt(Inet4Address address) {
        byte[] b = address.getAddress();
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    /**
     * Parses a dotted-quad IPv4 literal without triggering any DNS lookup.
     */
    public static Inet4Address parseIpv4Literal(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                bytes[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            InetAddress addr = InetAddress.getByAddress(bytes);
            return (addr instanceof Inet4Address) ? (Inet4Address) addr : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
