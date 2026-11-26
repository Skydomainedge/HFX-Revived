package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.Inflater;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for M4 PC discovery building blocks:
 * <ul>
 *   <li>{@link QrImageGenerator}: PNG magic, IHDR 480x480 24-bit RGB, pure-Java zlib decode +
 *       ZXing reverse decoding round-trip, quiet-zone white border, and temp file lifecycle cleanup;</li>
 *   <li>{@link TrustedPeerStore}: atomic JSON persistence, stable PC deviceId across restarts, CRUD;</li>
 *   <li>{@link MdnsManager}: per-cleaned-NIC binding (never virtual/VPN), cross-NIC IP merging &amp;
 *       USB_NIC > ETH > Wi-Fi ranking, peer_discovered / peer_lost events.</li>
 * </ul>
 */
public class QrImageGeneratorAndDiscoveryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testQrImageGeneratorValid480PngReverseDecodeAndCleanup() throws Exception {
        byte[] token = HmacAuth.generateRandomBytes(16);
        String pairUri = PairUriCodec.encode(new PairUriCodec.PairPayload(
                1,
                "pc1234567890",
                "OMEN-8-Plus",
                5741,
                token,
                Arrays.asList("10.94.81.203", "192.168.50.236"),
                "OMEN-8-Plus.local"));

        Path qrDir = tempFolder.newFolder("qr_out").toPath();
        Path generatedPath;
        try (QrImageGenerator gen = new QrImageGenerator(qrDir)) {
            generatedPath = gen.generatePngFile(pairUri, 480);
            assertTrue("Generated PNG must exist", Files.isRegularFile(generatedPath));
            assertTrue("Filename must match pair_*.png",
                    generatedPath.getFileName().toString().startsWith("pair_")
                            && generatedPath.getFileName().toString().endsWith(".png"));
            assertEquals(1, gen.getTrackedFileCount());

            byte[] png = Files.readAllBytes(generatedPath);
            DecodedPng decodedPng = decodePureJavaPng(png);
            assertEquals(480, decodedPng.width);
            assertEquals(480, decodedPng.height);
            assertEquals(8, decodedPng.bitDepth);
            assertEquals(2, decodedPng.colorType); // 24-bit RGB

            // Quiet zone corners must be pure white (#FFFFFF)
            assertEquals(0xFFFFFFFF, decodedPng.argbPixels[0]);
            assertEquals(0xFFFFFFFF, decodedPng.argbPixels[479]);
            assertEquals(0xFFFFFFFF, decodedPng.argbPixels[479 * 480]);
            assertEquals(0xFFFFFFFF, decodedPng.argbPixels[480 * 480 - 1]);

            // ZXing reverse decode MUST recover the exact original hfx://pair?... URI 100%
            RGBLuminanceSource source = new RGBLuminanceSource(
                    decodedPng.width, decodedPng.height, decodedPng.argbPixels);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new QRCodeReader().decode(bitmap);
            assertEquals(pairUri, result.getText());

            // Generate a second PNG and verify explicit delete + close cleanup
            Path secondPath = gen.generatePngFile(pairUri, 480);
            assertTrue(Files.isRegularFile(secondPath));
            assertTrue(gen.deleteQrFile(generatedPath));
            assertFalse("Deleted PNG must be removed", Files.exists(generatedPath));
            assertEquals(1, gen.getTrackedFileCount());
        }

        // After close(), all remaining tracked PNGs must be forcibly deleted
        assertEquals(0, Files.list(qrDir).count());
    }

    @Test
    public void testTrustedPeerStorePersistenceAndStablePcId() throws Exception {
        Path storeFile = tempFolder.newFolder("store").toPath().resolve("trusted_peers.json");
        TrustedPeerStore store1 = new TrustedPeerStore(storeFile);
        String pcId = store1.getOrCreatePcDeviceId(null);
        assertNotNull(pcId);
        assertEquals(12, pcId.length());

        byte[] secret = HmacAuth.generateRandomBytes(32);
        store1.upsertPeer("phone-oneplus13", "OnePlus 13", secret,
                Arrays.asList("10.94.81.247", "192.168.50.130"), 5740, "ncm0");

        // Reload in fresh instance simulating engine restart
        TrustedPeerStore store2 = new TrustedPeerStore(storeFile);
        assertEquals("PC deviceId must persist across restarts", pcId, store2.getOrCreatePcDeviceId(null));
        assertTrue(store2.isTrusted("phone-oneplus13"));
        assertArrayEquals(secret, store2.getPairSecret("phone-oneplus13"));
        assertNotNull(store2.findByIp("10.94.81.247"));
        assertEquals(1, store2.listPeers().size());
        assertEquals("ncm0", store2.listPeers().get(0).usbType);

        assertTrue(store2.removePeer("phone-oneplus13"));
        assertFalse(store2.isTrusted("phone-oneplus13"));
        assertNull(store2.getPairSecret("phone-oneplus13"));

        TrustedPeerStore store3 = new TrustedPeerStore(storeFile);
        assertEquals(0, store3.size());
    }

    @Test
    public void testMdnsManagerBindsOnlyCleanedNicsAndRanksUsbNicFirst() throws Exception {
        NicSelector selector = new NicSelector();
        List<NicSelector.RawNic> rawNics = Arrays.asList(
                new NicSelector.RawNic("usb_ncm", "USB NCM Host Device",
                        (Inet4Address) InetAddress.getByName("10.94.81.203"), 24, true, false, false, false),
                new NicSelector.RawNic("wlan0", "Intel Wi-Fi 7 BE200",
                        (Inet4Address) InetAddress.getByName("192.168.50.236"), 24, true, false, false, false),
                new NicSelector.RawNic("Meta", "Meta Tunnel",
                        (Inet4Address) InetAddress.getByName("198.18.0.1"), 30, true, false, false, false),
                new NicSelector.RawNic("vmnet8", "VMware Virtual Ethernet Adapter",
                        (Inet4Address) InetAddress.getByName("192.168.122.1"), 24, true, false, false, false)
        );
        List<IpcDtos.NicInfo> cleaned = selector.select(rawNics);
        assertEquals(2, cleaned.size());

        Path storeFile = tempFolder.newFolder("mdns_store").toPath().resolve("trusted_peers.json");
        TrustedPeerStore peerStore = new TrustedPeerStore(storeFile);
        peerStore.upsertPeer("op13", "OnePlus 13", HmacAuth.generateRandomBytes(32),
                Collections.singletonList("192.168.50.130"), 5740, "ncm0");

        List<String> boundIps = new CopyOnWriteArrayList<>();
        List<IpcDtos.PeerInfo> discoveredEvents = new CopyOnWriteArrayList<>();
        List<IpcDtos.PeerInfo> lostEvents = new CopyOnWriteArrayList<>();

        MdnsManager.MdnsBindingFactory fakeFactory = (nic, hostName, sink) -> {
            boundIps.add(nic.ip);
            return new MdnsManager.MdnsNicBinding() {
                @Override public String getBoundIp() { return nic.ip; }
                @Override public void close() {}
            };
        };

        try (MdnsManager mdns = new MdnsManager(selector, peerStore, "OMEN", fakeFactory)) {
            mdns.addListener(new MdnsManager.PeerDiscoveryListener() {
                @Override
                public void onPeerDiscovered(IpcDtos.PeerInfo peer, String pairAck) {
                    discoveredEvents.add(peer);
                }

                @Override
                public void onPeerLost(IpcDtos.PeerInfo peer) {
                    lostEvents.add(peer);
                }
            });
            mdns.start();
            mdns.refreshBindings(cleaned);

            assertEquals(2, mdns.getBoundNicCount());
            assertTrue(boundIps.contains("10.94.81.203"));
            assertTrue(boundIps.contains("192.168.50.236"));
            assertFalse("Must never bind Meta TUN", boundIps.contains("198.18.0.1"));
            assertFalse("Must never bind VMware virtual NIC", boundIps.contains("192.168.122.1"));

            // Simulate discovering the same device over Wi-Fi first, then USB NCM
            mdns.handleServiceResolved(new MdnsManager.ObservedService(
                    "192.168.50.236", "HFX-op13", "op13", "OnePlus 13",
                    Collections.singletonList("192.168.50.130"), 5740, "ncm0", null));
            mdns.handleServiceResolved(new MdnsManager.ObservedService(
                    "10.94.81.203", "HFX-op13", "op13", "OnePlus 13",
                    Collections.singletonList("10.94.81.247"), 5740, "ncm0", null));

            IpcDtos.PeerInfo best = mdns.findBestTrustedPeer(null);
            assertNotNull(best);
            assertTrue(best.trusted);
            assertEquals("USB NIC IP must rank ahead of Wi-Fi IP",
                    Arrays.asList("10.94.81.247", "192.168.50.130"), best.ips);

            // Losing one NIC keeps peer alive with remaining IP; losing both emits peer_lost
            mdns.handleServiceRemoved("10.94.81.203", "HFX-op13");
            assertEquals(0, lostEvents.size());
            assertEquals(Collections.singletonList("192.168.50.130"), mdns.findBestTrustedPeer(null).ips);

            mdns.handleServiceRemoved("192.168.50.236", "HFX-op13");
            assertEquals(1, lostEvents.size());
            assertEquals("op13", lostEvents.get(0).deviceId);
        }
    }

    // ---- Pure-Java PNG decoder (zero java.awt) for verifying QrImageGenerator output ----

    private static final class DecodedPng {
        final int width;
        final int height;
        final int bitDepth;
        final int colorType;
        final int[] argbPixels;

        DecodedPng(int width, int height, int bitDepth, int colorType, int[] argbPixels) {
            this.width = width;
            this.height = height;
            this.bitDepth = bitDepth;
            this.colorType = colorType;
            this.argbPixels = argbPixels;
        }
    }

    private static DecodedPng decodePureJavaPng(byte[] png) throws Exception {
        assertTrue("PNG too short", png.length > 33);
        byte[] expectedSig = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertArrayEquals("Invalid PNG magic signature", expectedSig, Arrays.copyOfRange(png, 0, 8));

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(png, 8, png.length - 8));
        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        ByteArrayOutputStream idatData = new ByteArrayOutputStream();

        while (dis.available() > 0) {
            int length = dis.readInt();
            byte[] typeBytes = new byte[4];
            dis.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            byte[] chunkData = new byte[length];
            if (length > 0) {
                dis.readFully(chunkData);
            }
            dis.readInt(); // CRC

            if ("IHDR".equals(type)) {
                DataInputStream ihdr = new DataInputStream(new ByteArrayInputStream(chunkData));
                width = ihdr.readInt();
                height = ihdr.readInt();
                bitDepth = ihdr.readUnsignedByte();
                colorType = ihdr.readUnsignedByte();
            } else if ("IDAT".equals(type)) {
                idatData.write(chunkData);
            } else if ("IEND".equals(type)) {
                break;
            }
        }

        Inflater inflater = new Inflater();
        byte[] raw;
        try {
            byte[] compressed = idatData.toByteArray();
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n > 0) out.write(buf, 0, n);
                else if (inflater.needsInput()) break;
            }
            raw = out.toByteArray();
        } finally {
            inflater.end();
        }

        int stride = 1 + width * 3;
        assertEquals(stride * height, raw.length);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            assertEquals("Expected filter byte 0", 0, raw[y * stride]);
            for (int x = 0; x < width; x++) {
                int off = y * stride + 1 + x * 3;
                int r = raw[off] & 0xFF;
                int g = raw[off + 1] & 0xFF;
                int b = raw[off + 2] & 0xFF;
                pixels[y * width + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return new DecodedPng(width, height, bitDepth, colorType, pixels);
    }
}
