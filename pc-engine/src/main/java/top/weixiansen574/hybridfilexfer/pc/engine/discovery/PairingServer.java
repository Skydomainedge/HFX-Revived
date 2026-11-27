package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.PairCallbackMessage;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Ephemeral HTTP callback pairing server ({@code :5741} with automatic random-port fallback),
 * 128-bit one-time token manager, 120s expiry timer with automatic PNG cleanup, and Windows
 * Firewall mDNS {@code pair_ack} reverse-fallback handler.
 */
public class PairingServer implements AutoCloseable {

    public interface PairingListener {
        void onPairingCompleted(IpcDtos.PeerInfo peer, byte[] pairSecret, boolean viaFallbackMdns);

        void onPairingExpired(String reason);

        void onPairingFailed(String code, String reason);
    }

    private static final int MAX_HTTP_BODY_BYTES = 16 * 1024;
    private static final int HTTP_READ_TIMEOUT_MS = 5_000;

    private final QrImageGenerator qrGenerator;
    private final TrustedPeerStore trustedPeerStore;
    private final int preferredPort;
    private final boolean rejectInboundHttpForTest;

    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hfx-pair-timer");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private volatile PairingListener listener;
    private volatile ActivePairingSession activeSession;

    public PairingServer(QrImageGenerator qrGenerator, TrustedPeerStore trustedPeerStore) {
        this(qrGenerator, trustedPeerStore, ProtocolConstants.DEFAULT_PAIR_PORT, false);
    }

    public PairingServer(QrImageGenerator qrGenerator,
                         TrustedPeerStore trustedPeerStore,
                         int preferredPort,
                         boolean rejectInboundHttpForTest) {
        this.qrGenerator = qrGenerator;
        this.trustedPeerStore = trustedPeerStore;
        this.preferredPort = preferredPort;
        this.rejectInboundHttpForTest = rejectInboundHttpForTest;
    }

    public void setListener(PairingListener listener) {
        this.listener = listener;
    }

    public boolean isPairingActive() {
        ActivePairingSession s = this.activeSession;
        return s != null && !s.consumed.get();
    }

    /**
     * Starts a fresh pairing session: binds {@code :5741} (or random fallback port), mints a
     * 128-bit one-time token, generates the 480x480 PNG QR code, and arms the TTL expiry timer.
     */
    public IpcPayloads.PairQrReadyPayload startPairing(String pcId,
                                                       String hostName,
                                                       List<String> candidateIps,
                                                       int ttlSeconds,
                                                       int pngSize) throws IOException {
        cancelPairing();

        int effectiveTtl = ttlSeconds > 0 ? ttlSeconds : ProtocolConstants.DEFAULT_PAIR_TTL_SECONDS;
        int effectivePngSize = pngSize > 0 ? pngSize : QrImageGenerator.DEFAULT_SIZE_PX;
        byte[] token = HmacAuth.generateRandomBytes(ProtocolConstants.SESSION_TOKEN_LENGTH);
        String expectedPairAck = HmacAuth.computePairAckHash(token);

        ServerSocket serverSocket = null;
        int boundPort;
        if (rejectInboundHttpForTest) {
            // Simulate a blocked inbound firewall port by allocating an unused port and closing it
            // immediately so mobile HTTP callbacks hit connection refused and trigger mDNS fallback.
            try (ServerSocket probe = new ServerSocket(0)) {
                boundPort = probe.getLocalPort();
            }
        } else {
            serverSocket = bindServerSocket(preferredPort);
            boundPort = serverSocket.getLocalPort();
        }

        List<String> ips = candidateIps != null ? new ArrayList<>(candidateIps) : Collections.emptyList();
        String safeHost = (hostName != null && !hostName.trim().isEmpty()) ? hostName.trim() : "PC";
        PairUriCodec.PairPayload uriPayload = new PairUriCodec.PairPayload(
                PairUriCodec.CURRENT_VERSION,
                pcId,
                safeHost,
                boundPort,
                token,
                ips,
                safeHost + ".local");
        String pairUri = PairUriCodec.encode(uriPayload);

        Path qrPngPath;
        try {
            qrPngPath = qrGenerator.generatePngFile(pairUri, effectivePngSize);
        } catch (IOException e) {
            closeQuietly(serverSocket);
            throw e;
        }

        long expiresAt = System.currentTimeMillis() + effectiveTtl * 1000L;
        ActivePairingSession session = new ActivePairingSession(
                pcId, safeHost, boundPort, token, expectedPairAck, qrPngPath, serverSocket, expiresAt);

        synchronized (lock) {
            this.activeSession = session;
        }

        session.expiryTask = timerExecutor.schedule(() -> handleSessionExpired(session),
                effectiveTtl, TimeUnit.SECONDS);

        if (serverSocket != null) {
            Thread acceptThread = new Thread(() -> runHttpAcceptLoop(session), "hfx-pair-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        IpcPayloads.PairQrReadyPayload payload = new IpcPayloads.PairQrReadyPayload();
        payload.pairUri = pairUri;
        payload.qrPngPath = qrPngPath.toAbsolutePath().toString();
        payload.hostName = safeHost;
        payload.ips = ips;
        payload.port = boundPort;
        payload.expiresAt = expiresAt;
        return payload;
    }

    /**
     * Cancels any active pairing session, closes the ephemeral port, and forcibly deletes the PNG.
     */
    public boolean cancelPairing() {
        ActivePairingSession session;
        synchronized (lock) {
            session = this.activeSession;
            this.activeSession = null;
        }
        if (session == null) {
            return false;
        }
        if (session.consumed.compareAndSet(false, true)) {
            cleanupSessionResources(session);
            return true;
        }
        cleanupSessionResources(session);
        return false;
    }

    /**
     * Invoked by {@link MdnsManager} when an mDNS service advertises a {@code pair_ack} TXT record.
     * Matches against the active pairing token hash to complete firewall-blocked pairing via
     * PC-initiated outbound TCP connection.
     */
    public boolean onMdnsPairAckObserved(IpcDtos.PeerInfo peer, String observedPairAck) {
        if (peer == null || peer.deviceId == null || peer.deviceId.trim().isEmpty()
                || observedPairAck == null || observedPairAck.trim().isEmpty()) {
            return false;
        }
        ActivePairingSession session;
        synchronized (lock) {
            session = this.activeSession;
            if (session == null || session.consumed.get()) {
                return false;
            }
            if (!constantTimeEquals(session.expectedPairAck, observedPairAck.trim())) {
                return false;
            }
            if (!session.consumed.compareAndSet(false, true)) {
                return false;
            }
            this.activeSession = null;
        }

        cleanupSessionResources(session);

        byte[] pairSecret = HmacAuth.derivePairSecret(session.token, session.pcId, peer.deviceId.trim());
        IpcDtos.PeerInfo savedPeer = persistTrustedPeer(peer, pairSecret);

        PairingListener l = this.listener;
        if (l != null) {
            l.onPairingCompleted(savedPeer, pairSecret, true);
        }
        return true;
    }

    @Override
    public void close() {
        cancelPairing();
        timerExecutor.shutdownNow();
    }

    private void handleSessionExpired(ActivePairingSession session) {
        boolean expiredNow;
        synchronized (lock) {
            expiredNow = session.consumed.compareAndSet(false, true);
            if (this.activeSession == session) {
                this.activeSession = null;
            }
        }
        cleanupSessionResources(session);
        if (expiredNow) {
            PairingListener l = this.listener;
            if (l != null) {
                l.onPairingExpired("QR pairing timed out");
            }
        }
    }

    private void runHttpAcceptLoop(ActivePairingSession session) {
        ServerSocket ss = session.serverSocket;
        while (!session.consumed.get() && ss != null && !ss.isClosed()) {
            try {
                Socket client = ss.accept();
                handleHttpClient(session, client);
            } catch (IOException e) {
                if (session.consumed.get() || ss.isClosed()) {
                    break;
                }
            }
        }
    }

    private void handleHttpClient(ActivePairingSession session, Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(HTTP_READ_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            HttpRequest httpReq = readHttpRequest(in);
            if (httpReq == null) {
                writeJsonResponse(out, 400, errorResponse("malformed_http"));
                return;
            }
            if (!"POST".equalsIgnoreCase(httpReq.method)
                    || !ProtocolConstants.PAIR_HTTP_PATH.equals(httpReq.path)) {
                writeJsonResponse(out, 404, errorResponse("not_found"));
                return;
            }

            PairCallbackMessage.Request req;
            try {
                req = IpcJson.fromJson(httpReq.body, PairCallbackMessage.Request.class);
            } catch (Exception e) {
                writeJsonResponse(out, 400, errorResponse("invalid_json"));
                return;
            }
            if (req == null || req.version != PairUriCodec.CURRENT_VERSION
                    || req.deviceId == null || req.deviceId.trim().isEmpty()
                    || req.nonce == null || req.nonce.trim().isEmpty()
                    || req.proof == null || req.proof.trim().isEmpty()) {
                writeJsonResponse(out, 400, errorResponse("missing_required_fields"));
                return;
            }

            int mobilePort = req.port > 0 ? req.port : ProtocolConstants.DEFAULT_CONTROL_PORT;
            boolean validProof = HmacAuth.verifyPairProof(
                    session.token, session.pcId, req.deviceId.trim(), mobilePort, req.nonce.trim(), req.proof.trim());
            if (!validProof) {
                writeJsonResponse(out, 403, errorResponse("invalid_proof"));
                PairingListener l = this.listener;
                if (l != null) {
                    l.onPairingFailed("INVALID_PROOF", "Rejected pairing callback with invalid HMAC proof");
                }
                return;
            }

            synchronized (lock) {
                if (!session.consumed.compareAndSet(false, true)) {
                    writeJsonResponse(out, 410, errorResponse("session_expired_or_consumed"));
                    return;
                }
                if (this.activeSession == session) {
                    this.activeSession = null;
                }
            }

            String deviceId = req.deviceId.trim();
            byte[] pairSecret = HmacAuth.derivePairSecret(session.token, session.pcId, deviceId);
            String ackProof = HmacAuth.computePairAckProof(session.token, session.pcId, deviceId, req.nonce.trim());

            List<String> peerIps = new ArrayList<>();
            if (req.ips != null) {
                for (String ip : req.ips) {
                    if (ip != null && !ip.trim().isEmpty()) {
                        peerIps.add(ip.trim());
                    }
                }
            }
            if (socket.getInetAddress() != null) {
                String remoteIp = socket.getInetAddress().getHostAddress();
                if (remoteIp != null && !remoteIp.isEmpty() && !peerIps.contains(remoteIp)) {
                    peerIps.add(remoteIp);
                }
            }
            // Strip mobile's 127.0.0.1 placeholder (e.g. when only USB_ADB was checked) if real
            // USB-NIC / LAN remote IPs exist, while preserving 127.0.0.1 in pure loopback tests.
            boolean hasNonLoopbackIp = false;
            for (String ip : peerIps) {
                if (!isLoopbackLiteral(ip)) {
                    hasNonLoopbackIp = true;
                    break;
                }
            }
            if (hasNonLoopbackIp) {
                List<String> filtered = new ArrayList<>(peerIps.size());
                for (String ip : peerIps) {
                    if (!isLoopbackLiteral(ip)) {
                        filtered.add(ip);
                    }
                }
                peerIps = filtered;
            }

            IpcDtos.PeerInfo peer = new IpcDtos.PeerInfo();
            peer.deviceId = deviceId;
            peer.deviceName = (req.deviceName != null && !req.deviceName.trim().isEmpty())
                    ? req.deviceName.trim() : deviceId;
            peer.ips = peerIps;
            peer.port = mobilePort;
            peer.usbType = req.usbType;
            peer.trusted = true;

            IpcDtos.PeerInfo savedPeer = persistTrustedPeer(peer, pairSecret);

            PairCallbackMessage.Response resp = new PairCallbackMessage.Response();
            resp.ok = true;
            resp.pcId = session.pcId;
            resp.pcName = session.hostName;
            resp.ackProof = ackProof;
            writeJsonResponse(out, 200, resp);

            cleanupSessionResources(session);

            PairingListener l = this.listener;
            if (l != null) {
                l.onPairingCompleted(savedPeer, pairSecret, false);
            }
        } catch (IOException ignored) {
        }
    }

    private IpcDtos.PeerInfo persistTrustedPeer(IpcDtos.PeerInfo peer, byte[] pairSecret) {
        if (trustedPeerStore != null) {
            try {
                TrustedPeerStore.TrustedPeerRecord saved = trustedPeerStore.upsertPeer(
                        peer.deviceId, peer.deviceName, pairSecret, peer.ips, peer.port, peer.usbType);
                return saved.toPeerInfo();
            } catch (IOException ignored) {
            }
        }
        peer.trusted = true;
        return peer;
    }

    private void cleanupSessionResources(ActivePairingSession session) {
        if (session.expiryTask != null) {
            session.expiryTask.cancel(false);
        }
        closeQuietly(session.serverSocket);
        qrGenerator.deleteQrFile(session.qrPngPath);
    }

    private static ServerSocket bindServerSocket(int preferredPort) throws IOException {
        if (preferredPort > 0) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(preferredPort));
                return ss;
            } catch (IOException ignored) {
                // Fall back to an ephemeral port if preferredPort (e.g. 5741) is occupied
            }
        }
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(0));
        return ss;
    }

    private static void closeQuietly(ServerSocket ss) {
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isLoopbackLiteral(String ip) {
        if (ip == null) {
            return false;
        }
        String trimmed = ip.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("127.") || "::1".equals(trimmed) || "0:0:0:0:0:0:0:1".equals(trimmed)
                || "localhost".equals(trimmed);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static PairCallbackMessage.Response errorResponse(String error) {
        PairCallbackMessage.Response r = new PairCallbackMessage.Response();
        r.ok = false;
        r.error = error;
        return r;
    }

    private static void writeJsonResponse(OutputStream out, int statusCode, Object payload) throws IOException {
        byte[] body = IpcJson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        String reason = statusCode == 200 ? "OK"
                : statusCode == 400 ? "Bad Request"
                : statusCode == 403 ? "Forbidden"
                : statusCode == 404 ? "Not Found"
                : statusCode == 410 ? "Gone" : "Error";
        String header = "HTTP/1.1 " + statusCode + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static HttpRequest readHttpRequest(InputStream in) throws IOException {
        String requestLine = readLineCrlf(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        String method = parts[0].trim();
        String path = parts[1].trim();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLineCrlf(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        int contentLength = 0;
        String clHeader = headers.get("content-length");
        if (clHeader != null) {
            try {
                contentLength = Integer.parseInt(clHeader);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (contentLength < 0 || contentLength > MAX_HTTP_BODY_BYTES) {
            return null;
        }
        byte[] bodyBytes = new byte[contentLength];
        int readTotal = 0;
        while (readTotal < contentLength) {
            int n = in.read(bodyBytes, readTotal, contentLength - readTotal);
            if (n < 0) {
                break;
            }
            readTotal += n;
        }
        return new HttpRequest(method, path, new String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8));
    }

    private static String readLineCrlf(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                baos.write(b);
                if (baos.size() > 8192) {
                    throw new IOException("HTTP header line too long");
                }
            }
        }
        return any ? baos.toString(StandardCharsets.US_ASCII) : null;
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final String body;

        HttpRequest(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    private static final class ActivePairingSession {
        final String pcId;
        final String hostName;
        final int port;
        final byte[] token;
        final String expectedPairAck;
        final Path qrPngPath;
        final ServerSocket serverSocket;
        final long expiresAt;
        final AtomicBoolean consumed = new AtomicBoolean(false);
        volatile ScheduledFuture<?> expiryTask;

        ActivePairingSession(String pcId, String hostName, int port, byte[] token, String expectedPairAck,
                             Path qrPngPath, ServerSocket serverSocket, long expiresAt) {
            this.pcId = pcId;
            this.hostName = hostName;
            this.port = port;
            this.token = token;
            this.expectedPairAck = expectedPairAck;
            this.qrPngPath = qrPngPath;
            this.serverSocket = serverSocket;
            this.expiresAt = expiresAt;
        }
    }
}
