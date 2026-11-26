package top.weixiansen574.hybridfilexfer.core.ipc;

import com.google.gson.JsonPrimitive;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IpcContractAndArchitectureTest {

    @Test
    public void testJsonRpcEnvelopesAndAllCoreDtosRoundTrip() {
        // 1) Request round-trip
        IpcPayloads.PairStartParams params = new IpcPayloads.PairStartParams();
        params.ttlSeconds = 120;
        params.pngSize = 480;
        JsonRpcRequest req = new JsonRpcRequest(
                new JsonPrimitive(1),
                IpcMethods.PAIR_START_QR,
                IpcJson.toJsonObject(params));
        String reqJson = IpcJson.toJson(req);
        assertFalse("NDJSON frame must be single-line", reqJson.contains("\n"));

        JsonRpcRequest parsedReq = IpcJson.fromJson(reqJson, JsonRpcRequest.class);
        assertEquals("2.0", parsedReq.jsonrpc);
        assertEquals(IpcMethods.PAIR_START_QR, parsedReq.method);
        assertEquals(480, IpcJson.fromJson(parsedReq.params, IpcPayloads.PairStartParams.class).pngSize);

        // 2) pair.qr_ready notification must NOT HTML-escape '&' or '=' in pairUri
        IpcPayloads.PairQrReadyPayload qr = new IpcPayloads.PairQrReadyPayload();
        qr.pairUri = "hfx://pair?v=1&id=abc123456789&p=5741&t=deadbeef&i=10.94.81.203,192.168.50.236";
        qr.qrPngPath = "C:\\Temp\\hybridfilexfer\\qr\\pair_a1b2.png";
        qr.hostName = "OMEN-8P";
        qr.ips = Arrays.asList("10.94.81.203", "192.168.50.236");
        qr.port = 5741;
        qr.expiresAt = 1800000000000L;

        String eventJson = IpcJson.encodeEvent(IpcEvents.PAIR_QR_READY, qr);
        assertTrue("pairUri must keep raw '&' without HTML escaping", eventJson.contains("v=1&id=abc123456789"));
        JsonRpcNotification notif = IpcJson.fromJson(eventJson, JsonRpcNotification.class);
        assertEquals(IpcEvents.PAIR_QR_READY, notif.method);
        IpcPayloads.PairQrReadyPayload decodedQr =
                IpcJson.fromJson(notif.params, IpcPayloads.PairQrReadyPayload.class);
        assertEquals(qr.pairUri, decodedQr.pairUri);
        assertEquals(qr.qrPngPath, decodedQr.qrPngPath);

        // 3) EngineSnapshot + TransferProgress + Error response round-trip
        IpcDtos.EngineSnapshot snap = new IpcDtos.EngineSnapshot();
        snap.engineRole = "pc";
        snap.protocolVersion = ProtocolConstants.VERSION_CODE;
        snap.engineVersion = ProtocolConstants.ENGINE_VERSION;
        snap.state = SessionState.CONNECTED;
        snap.udcSpeed = "super-speed";
        snap.adb = new IpcDtos.AdbCapabilityInfo();
        snap.adb.available = true;
        snap.adb.version = "36.0.0";
        snap.adb.hasLibusb = true;
        snap.adb.hasDelayedAck = true;
        snap.adb.recommendedStreams = 2;

        JsonRpcResponse okResp = JsonRpcResponse.success(new JsonPrimitive("req-2"), IpcJson.toJsonTree(snap));
        JsonRpcResponse decodedResp = IpcJson.fromJson(IpcJson.toJson(okResp), JsonRpcResponse.class);
        assertTrue(decodedResp.isSuccess());
        IpcDtos.EngineSnapshot decodedSnap = IpcJson.fromJson(decodedResp.result, IpcDtos.EngineSnapshot.class);
        assertEquals(400, decodedSnap.protocolVersion);
        assertEquals(SessionState.CONNECTED, decodedSnap.state);
        assertEquals("36.0.0", decodedSnap.adb.version);

        IpcPayloads.TransferProgressPayload progress = new IpcPayloads.TransferProgressPayload();
        progress.direction = "upload";
        progress.totalBytes = 10_000_000L;
        progress.transferredBytes = 5_000_000L;
        progress.aggregateBps = 550_000_000L;
        progress.perRailBps.put("ncm0", 300_000_000L);
        progress.perRailBps.put("wlan0", 250_000_000L);
        progress.currentFile = "/sdcard/video.mp4";
        IpcPayloads.TransferProgressPayload decodedProgress = IpcJson.fromJson(
                IpcJson.toJson(progress), IpcPayloads.TransferProgressPayload.class);
        assertEquals(Long.valueOf(300_000_000L), decodedProgress.perRailBps.get("ncm0"));

        JsonRpcResponse errResp = JsonRpcResponse.error(
                new JsonPrimitive(3), JsonRpcError.UNAUTHORIZED, "Invalid IPC token");
        JsonRpcResponse decodedErr = IpcJson.fromJson(IpcJson.toJson(errResp), JsonRpcResponse.class);
        assertFalse(decodedErr.isSuccess());
        assertEquals(JsonRpcError.UNAUTHORIZED, decodedErr.error.code);
    }

    @Test
    public void testCoreBytecodeIsJava8AndZeroUiOrSystemOutDependencies() throws Exception {
        URL location = HFXService.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location);
        File classesRoot = new File(location.toURI());
        assertTrue("Expected compiled :core classes directory: " + classesRoot, classesRoot.isDirectory());

        List<File> classFiles = new ArrayList<>();
        collectClassFiles(classesRoot, classFiles);
        assertTrue("Expected compiled class files in :core", classFiles.size() >= 20);

        for (File cf : classFiles) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(cf))) {
                int magic = dis.readInt();
                assertEquals("Invalid class magic: " + cf, 0xCAFEBABE, magic);
                dis.readUnsignedShort(); // minor
                int major = dis.readUnsignedShort();
                assertEquals(":core class must target Java 8 (major 52) for Android API 24+: " + cf.getName(),
                        52, major);
            }

            byte[] raw = java.nio.file.Files.readAllBytes(cf.toPath());
            String content = new String(raw, StandardCharsets.ISO_8859_1);
            assertFalse(":core must NOT reference android.*: " + cf.getName(),
                    content.contains("Landroid/"));
            assertFalse(":core must NOT reference javax.swing.*: " + cf.getName(),
                    content.contains("Ljavax/swing/"));
            assertFalse(":core must NOT reference java.awt.*: " + cf.getName(),
                    content.contains("Ljava/awt/"));
            assertFalse(":core must NOT use System.out / PrintStream: " + cf.getName(),
                    content.contains("Ljava/io/PrintStream;"));
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
