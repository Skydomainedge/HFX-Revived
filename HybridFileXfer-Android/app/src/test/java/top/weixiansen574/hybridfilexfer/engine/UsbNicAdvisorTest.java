package top.weixiansen574.hybridfilexfer.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UsbNicAdvisorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static Inet4Address ipv4(String ip) throws Exception {
        return (Inet4Address) InetAddress.getByName(ip);
    }

    private static UsbNicAdvisor.RawInterface iface(String name, String ip) throws Exception {
        return new UsbNicAdvisor.RawInterface(name, name, ipv4(ip), 24, true, false);
    }

    private static IpcDtos.NicInfo findByName(List<IpcDtos.NicInfo> list, String name) {
        for (IpcDtos.NicInfo n : list) {
            if (name.equals(n.name)) {
                return n;
            }
        }
        return null;
    }

    @Test
    public void testRecognizesNcmUsbRndisAndAutoDisablesAdbWithConflictPayload() throws Exception {
        for (String usbIfName : Arrays.asList("ncm0", "usb0", "rndis0")) {
            List<UsbNicAdvisor.RawInterface> raws = Arrays.asList(
                    iface("wlan0", "192.168.50.130"),
                    iface(usbIfName, "10.94.81.247")
            );
            UsbNicAdvisor advisor = new UsbNicAdvisor();
            UsbNicAdvisor.AdviceResult res = advisor.advise(raws, "super-speed");

            assertTrue("Expected active USB NIC for " + usbIfName, res.hasActiveUsbNic());
            assertEquals("super-speed", res.udcSpeed);

            // USB NIC must be prioritized first and selected
            IpcDtos.NicInfo first = res.nics.get(0);
            assertEquals(usbIfName, first.name);
            assertEquals(RailType.USB_NIC.name(), first.railType);
            assertTrue(first.usbNic);
            assertTrue(first.recommended);
            assertTrue(first.selected);

            // Wi-Fi remains selected for dual-rail aggregation
            IpcDtos.NicInfo wifi = findByName(res.nics, "wlan0");
            assertNotNull(wifi);
            assertEquals(RailType.WIFI.name(), wifi.railType);
            assertTrue(wifi.selected);

            // USB_ADB must be auto-disabled to prevent same-cable USB contention
            IpcDtos.NicInfo adb = findByName(res.nics, ProtocolConstants.USB_ADB_RAIL_NAME);
            assertNotNull(adb);
            assertEquals(RailType.ADB.name(), adb.railType);
            assertFalse(adb.selected);
            assertFalse(adb.recommended);
            assertEquals(UsbNicAdvisor.REASON_USB_NIC_ACTIVE_AUTO_DISABLED, adb.reason);

            assertNotNull(res.conflictResolved);
            assertEquals(ProtocolConstants.USB_ADB_RAIL_NAME, res.conflictResolved.disabledRail);
            assertEquals(Collections.singletonList(usbIfName), res.conflictResolved.activeUsbNics);
            assertNotNull(res.conflictResolved.reason);
        }
    }

    @Test
    public void testKeepsAdbEnabledWhenNoUsbNicOrWhenAutoDisableToggledOff() throws Exception {
        List<UsbNicAdvisor.RawInterface> wifiOnly = Collections.singletonList(
                iface("wlan0", "192.168.50.130")
        );
        UsbNicAdvisor advisor = new UsbNicAdvisor();
        UsbNicAdvisor.AdviceResult res = advisor.advise(wifiOnly, "super-speed");

        assertFalse(res.hasActiveUsbNic());
        assertNull(res.conflictResolved);
        IpcDtos.NicInfo adb = findByName(res.nics, ProtocolConstants.USB_ADB_RAIL_NAME);
        assertNotNull(adb);
        assertTrue(adb.selected);
        assertTrue(adb.recommended);

        // User-tunable toggle allows keeping USB_ADB selected even when USB NIC is active
        UsbNicAdvisor.AdvisorConfig custom = new UsbNicAdvisor.AdvisorConfig();
        custom.autoDisableAdbWhenUsbNicActive = false;
        UsbNicAdvisor customAdvisor = new UsbNicAdvisor(custom);
        UsbNicAdvisor.AdviceResult customRes = customAdvisor.advise(
                Arrays.asList(iface("ncm0", "10.94.81.247"), iface("wlan0", "192.168.50.130")), "super-speed");
        assertNull(customRes.conflictResolved);
        assertTrue(findByName(customRes.nics, ProtocolConstants.USB_ADB_RAIL_NAME).selected);
    }

    @Test
    public void testFiltersNoiseInterfacesAndMarksVpnInactiveByDefault() throws Exception {
        List<UsbNicAdvisor.RawInterface> raws = Arrays.asList(
                new UsbNicAdvisor.RawInterface("lo", "lo", ipv4("127.0.0.1"), 8, true, true),
                iface("rmnet_data0", "10.10.0.2"),
                iface("rmnet_data4", "10.10.0.6"),
                iface("dummy0", "192.168.200.1"),
                iface("ifb0", "192.168.200.2"),
                iface("ovnet0", "192.168.200.3"),
                iface("v4-rmnet_data0", "192.0.0.4"),
                iface("p2p0", "192.168.49.1"),
                iface("p2p-wlan0-0", "192.168.49.2"),
                iface("tun0", "172.19.0.1"),
                iface("wg0", "10.8.0.2"),
                iface("wlan0", "192.168.50.130")
        );

        UsbNicAdvisor.AdviceResult res = new UsbNicAdvisor().advise(raws, "unknown");

        // All noise interfaces filtered out
        assertNull(findByName(res.nics, "lo"));
        assertNull(findByName(res.nics, "rmnet_data0"));
        assertNull(findByName(res.nics, "rmnet_data4"));
        assertNull(findByName(res.nics, "dummy0"));
        assertNull(findByName(res.nics, "ifb0"));
        assertNull(findByName(res.nics, "ovnet0"));
        assertNull(findByName(res.nics, "v4-rmnet_data0"));
        assertNull(findByName(res.nics, "p2p0"));
        assertNull(findByName(res.nics, "p2p-wlan0-0"));

        // VPN interfaces flagged with vpn=true, selected=false, and vpnActive signal exposed
        IpcDtos.NicInfo tun = findByName(res.nics, "tun0");
        assertNotNull(tun);
        assertTrue(tun.vpn);
        assertFalse(tun.selected);
        assertFalse(tun.recommended);

        IpcDtos.NicInfo wg = findByName(res.nics, "wg0");
        assertNotNull(wg);
        assertTrue(wg.vpn);
        assertFalse(wg.selected);

        assertTrue(res.vpnActive);
        assertNotNull(res.vpnWarning);
        assertEquals(UsbNicAdvisor.CODE_VPN_ACTIVE_WARNING, res.vpnWarning.code);

        // Selected conversion should exclude tun0/wg0 and include wlan0 + USB_ADB
        List<ServerNetInterface> selected = UsbNicAdvisor.toServerNetInterfaces(res.nics);
        assertEquals(2, selected.size());
    }

    @Test
    public void testReadOnlyUdcCurrentSpeedProbing() throws Exception {
        File udcRoot = tempFolder.newFolder("udc");
        File dwc3 = new File(udcRoot, "a600000.dwc3");
        assertTrue(dwc3.mkdirs());
        File speedFile = new File(dwc3, "current_speed");

        writeLine(speedFile, "super-speed\n");
        assertEquals(UsbNicAdvisor.SPEED_SUPER, UsbNicAdvisor.probeUdcSpeed(udcRoot));
        assertEquals("USB 3.0 5G", UsbNicAdvisor.formatUdcSpeedShortLabel(UsbNicAdvisor.probeUdcSpeed(udcRoot)));

        writeLine(speedFile, "super-speed-plus\n");
        assertEquals(UsbNicAdvisor.SPEED_SUPER_PLUS, UsbNicAdvisor.probeUdcSpeed(udcRoot));

        writeLine(speedFile, "high-speed\n");
        assertEquals(UsbNicAdvisor.SPEED_HIGH, UsbNicAdvisor.probeUdcSpeed(udcRoot));

        writeLine(speedFile, "full-speed\n");
        assertEquals(UsbNicAdvisor.SPEED_FULL, UsbNicAdvisor.probeUdcSpeed(udcRoot));

        // Multiple controllers: highest active speed wins over UNKNOWN
        File dummyCtrl = new File(udcRoot, "dummy_udc.0");
        assertTrue(dummyCtrl.mkdirs());
        writeLine(new File(dummyCtrl, "current_speed"), "UNKNOWN\n");
        writeLine(speedFile, "super-speed\n");
        UsbNicAdvisor.AdvisorConfig cfg = new UsbNicAdvisor.AdvisorConfig();
        cfg.udcRootDir = udcRoot;
        UsbNicAdvisor advisor = new UsbNicAdvisor(cfg);
        assertEquals(UsbNicAdvisor.SPEED_SUPER, advisor.probeUdcSpeed());

        // Missing directory returns unknown gracefully
        assertEquals(UsbNicAdvisor.SPEED_UNKNOWN,
                UsbNicAdvisor.probeUdcSpeed(new File(udcRoot, "non_existent_dir")));
    }

    @Test
    public void testAndroidEnginePackageHasZeroUiOrPrintStreamReferences() throws Exception {
        URL location = UsbNicAdvisor.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location);
        File codeSource = new File(location.toURI());

        Map<String, byte[]> engineClasses = new LinkedHashMap<>();
        String enginePrefix = "top/weixiansen574/hybridfilexfer/engine/";

        if (codeSource.isFile() && codeSource.getName().endsWith(".jar")) {
            try (JarFile jar = new JarFile(codeSource)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(enginePrefix) && name.endsWith(".class")) {
                        engineClasses.put(name, readAllBytes(jar.getInputStream(entry)));
                    }
                }
            }
        } else if (codeSource.isDirectory()) {
            File engineDir = new File(codeSource, enginePrefix);
            assertTrue("Expected compiled engine package directory: " + engineDir, engineDir.isDirectory());
            List<File> classFiles = new ArrayList<>();
            collectClassFiles(engineDir, classFiles);
            for (File cf : classFiles) {
                engineClasses.put(cf.getName(), Files.readAllBytes(cf.toPath()));
            }
        }

        assertFalse("Expected compiled class files in Android engine/ package from " + codeSource,
                engineClasses.isEmpty());

        for (Map.Entry<String, byte[]> entry : engineClasses.entrySet()) {
            String className = entry.getKey();
            String content = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
            assertFalse("Android engine/ must NOT reference android.app.Activity: " + className,
                    content.contains("Landroid/app/Activity;"));
            assertFalse("Android engine/ must NOT reference android.view.*: " + className,
                    content.contains("Landroid/view/"));
            assertFalse("Android engine/ must NOT reference android.widget.* (incl. Toast): " + className,
                    content.contains("Landroid/widget/"));
            assertFalse("Android engine/ must NOT reference RecyclerView: " + className,
                    content.contains("Landroidx/recyclerview/"));
            assertFalse("Android engine/ must NOT reference PrintStream: " + className,
                    content.contains("Ljava/io/PrintStream;"));
            if (className.contains("UsbNicAdvisor")) {
                assertFalse("UsbNicAdvisor must remain pure Java with zero android.* references: " + className,
                        content.contains("Landroid/"));
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private static void writeLine(File file, String text) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(text);
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
