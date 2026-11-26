package top.weixiansen574.hybridfilexfer.pc.engine;

import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;
import top.weixiansen574.hybridfilexfer.pc.engine.discovery.NicSelector;
import top.weixiansen574.hybridfilexfer.pc.engine.transport.SubnetBindResolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NicSelectorAndSubnetBindTest {

    private static Inet4Address ipv4(String s) throws Exception {
        return (Inet4Address) InetAddress.getByName(s);
    }

    private static NicSelector.RawNic nic(String name, String display, String ip, int prefix, boolean up, boolean loopback)
            throws Exception {
        return new NicSelector.RawNic(name, display, ipv4(ip), prefix, up, loopback, false, false);
    }

    @Test
    public void testNicSelectorFiltersVirtualVpnAndReservedAndSortsByPriority() throws Exception {
        List<NicSelector.RawNic> raw = Arrays.asList(
                nic("lo", "Software Loopback Interface 1", "127.0.0.1", 8, true, true),
                nic("eth1", "LinkLocal Adapter", "169.254.12.34", 16, true, false),
                nic("meta0", "Meta Tunnel", "198.18.0.1", 30, true, false),
                nic("tun_alt", "Generic Adapter", "198.19.12.5", 16, true, false),
                nic("vmnet8", "VMware Virtual Ethernet Adapter for VMnet8", "192.168.188.1", 24, true, false),
                nic("eth2", "VirtualBox Host-Only Ethernet Adapter", "192.168.56.1", 24, true, false),
                nic("eth3", "vEthernet (Default Switch) Hyper-V", "172.20.16.1", 20, true, false),
                nic("eth4", "vEthernet (WSL)", "172.24.0.1", 20, true, false),
                nic("clash0", "Clash Wintun Adapter", "10.0.0.2", 24, true, false),
                nic("mihomo0", "Mihomo TUN", "10.0.0.3", 24, true, false),
                nic("wg0", "WireGuard Tunnel", "10.8.0.2", 24, true, false),
                nic("ts0", "Tailscale Tunnel", "100.64.0.1", 10, true, false),
                nic("zt0", "ZeroTier One", "10.147.20.5", 24, true, false),
                nic("np0", "NatPierce Virtual NIC", "10.66.0.1", 24, true, false),
                nic("teredo0", "Microsoft Teredo Tunneling Adapter", "10.1.2.3", 24, true, false),
                nic("isatap0", "Microsoft ISATAP Adapter", "10.1.2.4", 24, true, false),
                nic("bt0", "Bluetooth Device (Personal Area Network)", "192.168.44.1", 24, true, false),
                nic("docker0", "DockerNAT", "172.17.0.1", 16, true, false),
                nic("eth_down", "Realtek PCIe 2.5GbE Family Controller", "192.168.50.99", 24, false, false),
                // Valid physical/USB interfaces (intentionally unsorted)
                nic("wlan0", "Intel(R) Wi-Fi 7 BE200 320MHz", "192.168.50.10", 24, true, false),
                nic("wlan1", "Realtek USB Wireless LAN 802.11ax", "192.168.50.11", 24, true, false),
                nic("eth5", "Realtek Gaming 2.5GbE Family Controller", "192.168.50.236", 24, true, false),
                nic("eth6", "Remote NDIS Compatible Device", "10.94.81.203", 24, true, false),
                nic("eth7", "UsbNcm Host Device", "10.95.10.2", 24, true, false)
        );

        NicSelector selector = new NicSelector();
        List<IpcDtos.NicInfo> selected = selector.select(raw);

        assertEquals(5, selected.size());
        // USB NICs first (eth6 RNDIS, eth7 UsbNcm)
        assertEquals(RailType.USB_NIC.name(), selected.get(0).railType);
        assertTrue(selected.get(0).usbNic);
        assertEquals("10.94.81.203", selected.get(0).ip);

        assertEquals(RailType.USB_NIC.name(), selected.get(1).railType);
        assertTrue(selected.get(1).usbNic);
        assertEquals("10.95.10.2", selected.get(1).ip);

        // Wired Ethernet second
        assertEquals(RailType.ETH.name(), selected.get(2).railType);
        assertEquals("192.168.50.236", selected.get(2).ip);

        // Wi-Fi third (including USB Wi-Fi dongle not misclassified as USB NIC!)
        assertEquals(RailType.WIFI.name(), selected.get(3).railType);
        assertEquals("192.168.50.10", selected.get(3).ip);
        assertEquals(RailType.WIFI.name(), selected.get(4).railType);
        assertEquals("192.168.50.11", selected.get(4).ip);
    }

    @Test
    public void testSubnetBindResolverMatchesSameSubnetLongestPrefixAndSkipsLoopback() throws Exception {
        List<NicSelector.RawNic> raw = Arrays.asList(
                nic("meta0", "Meta Tunnel", "198.18.0.1", 30, true, false),
                nic("eth_wide", "Intel Ethernet", "10.0.0.5", 8, true, false),
                nic("eth6", "Remote NDIS Compatible Device", "10.94.81.203", 24, true, false),
                nic("wlan0", "Intel Wi-Fi 7", "192.168.50.10", 24, true, false),
                nic("eth5", "Realtek 2.5GbE Family Controller", "192.168.50.236", 24, true, false)
        );

        NicSelector selector = new NicSelector();
        List<IpcDtos.NicInfo> filtered = selector.select(raw);
        SubnetBindResolver resolver = new SubnetBindResolver(() -> filtered);

        // Target on phone USB RNDIS subnet -> longest prefix /24 (10.94.81.203) beats /8 (10.0.0.5)
        InetAddress usbBind = resolver.resolveLocalBindAddress(ipv4("10.94.81.247"));
        assertNotNull(usbBind);
        assertEquals("10.94.81.203", usbBind.getHostAddress());

        // Target on LAN 192.168.50.130 -> ETH (192.168.50.236) wins tie-break over Wi-Fi (192.168.50.10)
        InetAddress lanBind = resolver.resolveLocalBindAddress(ipv4("192.168.50.130"));
        assertNotNull(lanBind);
        assertEquals("192.168.50.236", lanBind.getHostAddress());

        // Loopback never binds to a physical NIC
        assertNull(resolver.resolveLocalBindAddress(ipv4("127.0.0.1")));

        // Meta TUN fake-IP subnet is filtered out -> null
        assertNull(resolver.resolveLocalBindAddress(ipv4("198.18.0.2")));

        // Unmatched subnet -> null
        assertNull(resolver.resolveLocalBindAddress(ipv4("172.16.99.5")));
    }

    @Test
    public void testJdkHFXClientResolveLocalBindAddressHook() throws Exception {
        IpcDtos.NicInfo usbNic = new IpcDtos.NicInfo();
        usbNic.name = "eth6";
        usbNic.ip = "10.94.81.203";
        usbNic.prefixLength = 24;
        usbNic.railType = RailType.USB_NIC.name();

        SubnetBindResolver resolver = new SubnetBindResolver(() -> Arrays.asList(usbNic));

        TestAccessJdkClient client = new TestAccessJdkClient("10.94.81.247", 5740, "/", resolver);

        // Auto-binds same-subnet IP when server clientBindAddress is null
        ServerNetInterface usbRail = new ServerNetInterface("ncm0", ipv4("10.94.81.247"), null, RailType.USB_NIC, 1);
        assertEquals("10.94.81.203", client.exposeResolveLocalBindAddress(usbRail).getHostAddress());

        // Explicit server clientBindAddress always takes precedence
        ServerNetInterface explicitRail = new ServerNetInterface(
                "ncm0", ipv4("10.94.81.247"), ipv4("10.94.81.99"), RailType.USB_NIC, 1);
        assertEquals("10.94.81.99", client.exposeResolveLocalBindAddress(explicitRail).getHostAddress());

        // ADB / loopback never auto-binds
        ServerNetInterface adbRail = new ServerNetInterface("USB_ADB", ipv4("127.0.0.1"), null, RailType.ADB, 4);
        assertNull(client.exposeResolveLocalBindAddress(adbRail));

        // User-tunable toggle disables auto subnet binding
        client.setAutoSubnetBindEnabled(false);
        assertNull(client.exposeResolveLocalBindAddress(usbRail));
    }

    private static final class TestAccessJdkClient extends JdkHFXClient {
        TestAccessJdkClient(String host, int port, String homeDir, SubnetBindResolver resolver) {
            super(host, port, homeDir, resolver);
        }

        InetAddress exposeResolveLocalBindAddress(ServerNetInterface rail) {
            return resolveLocalBindAddress(rail);
        }
    }
}
