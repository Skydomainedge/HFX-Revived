package top.weixiansen574.hybridfilexfer.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.protocol.Base64Url;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.PairCallbackMessage;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Pure-Java 8 mobile pairing client (zero {@code android.*} / UI references; fully JVM testable).
 *
 * <p>Decodes {@code hfx://pair?...} via {@link PairUriCodec}, concurrently probes every candidate
 * PC USB/LAN IP with a signed {@code POST /hfx/pair} callback (2s timeout), and automatically
 * switches to mDNS {@code pair_ack} reverse-fallback when inbound Windows Firewall blocks all
 * direct HTTP probes.</p>
 */
public class PairClient {

    public static final int DEFAULT_HTTP_PROBE_TIMEOUT_MS = 2_000;

    public static final class PairOutcome {
        public final boolean directHttpSucceeded;
        public final boolean switchedToMdnsFallback;
        public final String pcId;
        public final String pcName;
        public final String matchedPcIp;
        public final byte[] pairSecret;
        public final String pairAckHash;
        public final String error;

        private PairOutcome(boolean directHttpSucceeded, boolean switchedToMdnsFallback,
                            String pcId, String pcName, String matchedPcIp,
                            byte[] pairSecret, String pairAckHash, String error) {
            this.directHttpSucceeded = directHttpSucceeded;
            this.switchedToMdnsFallback = switchedToMdnsFallback;
            this.pcId = pcId;
            this.pcName = pcName;
            this.matchedPcIp = matchedPcIp;
            this.pairSecret = pairSecret;
            this.pairAckHash = pairAckHash;
            this.error = error;
        }

        public static PairOutcome directSuccess(String pcId, String pcName, String matchedPcIp,
                                                byte[] pairSecret, String pairAckHash) {
            return new PairOutcome(true, false, pcId, pcName, matchedPcIp, pairSecret, pairAckHash, null);
        }

        public static PairOutcome mdnsFallback(String pcId, String pcName, byte[] pairSecret,
                                               String pairAckHash, String reason) {
            return new PairOutcome(false, true, pcId, pcName, null, pairSecret, pairAckHash, reason);
        }

        public static PairOutcome failed(String error) {
            return new PairOutcome(false, false, null, null, null, null, null, error);
        }
    }

    public static final class PreparedServerInfo {
        public final int boundPort;
        public final List<String> mobileIps;
        public final String usbType;

        public PreparedServerInfo(int boundPort, List<String> mobileIps, String usbType) {
            this.boundPort = boundPort;
            this.mobileIps = mobileIps != null ? new ArrayList<>(mobileIps) : new ArrayList<>();
            this.usbType = usbType;
        }
    }

    public interface PairCallback {
        PreparedServerInfo onPrepareServerAndPendingTrust(PairUriCodec.PairPayload payload, byte[] pairSecret) throws Exception;

        void onDirectPairSucceeded(PairOutcome outcome);

        void onActivateMdnsFallback(PairOutcome outcome);

        void onPairFailed(String code, String reason);
    }

    private final int probeTimeoutMs;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public PairClient() {
        this(DEFAULT_HTTP_PROBE_TIMEOUT_MS);
    }

    public PairClient(int probeTimeoutMs) {
        this.probeTimeoutMs = Math.max(200, probeTimeoutMs);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void resetCancel() {
        cancelled.set(false);
    }

    /**
     * Parses and validates a QR URI without performing network I/O.
     */
    public static PairUriCodec.PairPayload parseQrUri(String rawUri) {
        return PairUriCodec.decode(rawUri);
    }

    /**
     * Executes the full mobile pairing workflow synchronously (call from a background thread):
     * <ol>
     *   <li>Derives shared {@code pairSecret} and {@code pair_ack} hash;</li>
     *   <li>Notifies {@code callback.onPrepareServerAndPendingTrust} to bring up {@code HFXServer}
     *       and register pending HMAC trust;</li>
     *   <li>Concurrently sends signed {@code POST /hfx/pair} to all candidate IPs in the QR URI
     *       within {@link #probeTimeoutMs};</li>
     *   <li>If any IP succeeds and passes PC {@code ackProof} verification, completes directly;
     *       otherwise activates mDNS {@code pair_ack} reverse-fallback for PC outbound TCP connect.</li>
     * </ol>
     */
    public PairOutcome executePairing(final PairUriCodec.PairPayload payload,
                                      final String mobileDeviceId,
                                      final String mobileDeviceName,
                                      final int serverPort,
                                      final List<String> mobileIps,
                                      final String usbType,
                                      final PairCallback callback) {
        cancelled.set(false);
        if (payload == null) {
            PairOutcome fail = PairOutcome.failed("Null pairing payload");
            if (callback != null) callback.onPairFailed("INVALID_URI", fail.error);
            return fail;
        }

        final byte[] pairSecret = HmacAuth.derivePairSecret(payload.token, payload.pcId, mobileDeviceId);
        final String pairAckHash = HmacAuth.computePairAckHash(payload.token);

        int effectivePort = serverPort > 0 ? serverPort : ProtocolConstants.DEFAULT_CONTROL_PORT;
        List<String> effectiveMobileIps = mobileIps != null ? new ArrayList<>(mobileIps) : new ArrayList<>();
        String effectiveUsbType = usbType;

        if (callback != null) {
            try {
                PreparedServerInfo prepared = callback.onPrepareServerAndPendingTrust(payload, pairSecret);
                if (prepared != null) {
                    if (prepared.boundPort > 0) {
                        effectivePort = prepared.boundPort;
                    }
                    if (!prepared.mobileIps.isEmpty()) {
                        effectiveMobileIps = new ArrayList<>(prepared.mobileIps);
                    }
                    if (prepared.usbType != null && !prepared.usbType.isEmpty()) {
                        effectiveUsbType = prepared.usbType;
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                PairOutcome fail = PairOutcome.failed("Failed to start local server: " + msg);
                callback.onPairFailed("SERVER_START_FAILED", fail.error);
                return fail;
            }
        }

        if (cancelled.get()) {
            return PairOutcome.failed("Pairing cancelled");
        }

        final String nonce = Base64Url.encode(HmacAuth.generateRandomBytes(ProtocolConstants.NONCE_LENGTH));
        final PairCallbackMessage.Request reqBody = new PairCallbackMessage.Request();
        reqBody.version = PairUriCodec.CURRENT_VERSION;
        reqBody.deviceId = mobileDeviceId;
        reqBody.deviceName = mobileDeviceName;
        reqBody.port = effectivePort;
        reqBody.ips = effectiveMobileIps;
        reqBody.usbType = effectiveUsbType;
        reqBody.nonce = nonce;
        reqBody.proof = HmacAuth.computePairProof(
                payload.token, payload.pcId, mobileDeviceId, effectivePort, nonce);
        final byte[] jsonBytes = IpcJson.toJson(reqBody).getBytes(StandardCharsets.UTF_8);

        List<String> targetIps = new ArrayList<>();
        for (String ip : payload.ips) {
            if (ip != null && !ip.trim().isEmpty()) {
                targetIps.add(ip.trim());
            }
        }

        if (!targetIps.isEmpty()) {
            ProbeSuccess winner = probeAllConcurrently(targetIps, payload, mobileDeviceId, nonce, jsonBytes);
            if (cancelled.get()) {
                return PairOutcome.failed("Pairing cancelled");
            }
            if (winner != null) {
                String pcName = (winner.pcName != null && !winner.pcName.isEmpty())
                        ? winner.pcName : payload.hostName;
                PairOutcome outcome = PairOutcome.directSuccess(
                        payload.pcId, pcName, winner.matchedIp, pairSecret, pairAckHash);
                if (callback != null) {
                    callback.onDirectPairSucceeded(outcome);
                }
                return outcome;
            }
        }

        if (cancelled.get()) {
            return PairOutcome.failed("Pairing cancelled");
        }

        // All direct HTTP callbacks failed or timed out (e.g. Windows Firewall blocked inbound :5741).
        // Switch automatically to mDNS pair_ack reverse-fallback so PC connects outbound to phone :5740.
        PairOutcome fallback = PairOutcome.mdnsFallback(
                payload.pcId,
                payload.hostName,
                pairSecret,
                pairAckHash,
                "Direct HTTP callback unreachable within " + probeTimeoutMs + "ms; switched to mDNS pair_ack fallback");
        if (callback != null) {
            callback.onActivateMdnsFallback(fallback);
        }
        return fallback;
    }

    private ProbeSuccess probeAllConcurrently(List<String> targetIps,
                                              final PairUriCodec.PairPayload payload,
                                              final String mobileDeviceId,
                                              final String nonce,
                                              final byte[] jsonBytes) {
        int poolSize = Math.min(targetIps.size(), 6);
        ExecutorService exec = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
            private int idx = 0;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "hfx-pair-probe-" + (++idx));
                t.setDaemon(true);
                return t;
            }
        });
        CompletionService<ProbeSuccess> completion = new ExecutorCompletionService<>(exec);
        List<Future<ProbeSuccess>> futures = new ArrayList<>(targetIps.size());

        try {
            for (final String ip : targetIps) {
                futures.add(completion.submit(new Callable<ProbeSuccess>() {
                    @Override
                    public ProbeSuccess call() {
                        return probeSingleIp(ip, payload, mobileDeviceId, nonce, jsonBytes);
                    }
                }));
            }

            long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(probeTimeoutMs + 300L);
            for (int i = 0; i < targetIps.size(); i++) {
                if (cancelled.get()) {
                    return null;
                }
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    break;
                }
                try {
                    Future<ProbeSuccess> done = completion.poll(remainingNs, TimeUnit.NANOSECONDS);
                    if (done == null) {
                        break;
                    }
                    ProbeSuccess res = done.get();
                    if (res != null) {
                        return res;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception ignored) {
                }
            }
            return null;
        } finally {
            for (Future<ProbeSuccess> f : futures) {
                f.cancel(true);
            }
            exec.shutdownNow();
        }
    }

    private ProbeSuccess probeSingleIp(String ip,
                                       PairUriCodec.PairPayload payload,
                                       String mobileDeviceId,
                                       String nonce,
                                       byte[] jsonBytes) {
        if (cancelled.get()) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + payload.port + ProtocolConstants.PAIR_HTTP_PATH);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(probeTimeoutMs);
            conn.setReadTimeout(probeTimeoutMs);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(jsonBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                return null;
            }
            String body = readStreamUtf8(conn.getInputStream());
            PairCallbackMessage.Response resp = IpcJson.fromJson(body, PairCallbackMessage.Response.class);
            if (resp == null || !resp.ok) {
                return null;
            }
            boolean ackValid = HmacAuth.verifyPairAckProof(
                    payload.token, payload.pcId, mobileDeviceId, nonce, resp.ackProof);
            if (!ackValid) {
                return null;
            }
            return new ProbeSuccess(ip, resp.pcName);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStreamUtf8(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            byte[] buf = new byte[1024];
            int n;
            while ((n = stream.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class ProbeSuccess {
        final String matchedIp;
        final String pcName;

        ProbeSuccess(String matchedIp, String pcName) {
            this.matchedIp = matchedIp;
            this.pcName = pcName;
        }
    }
}
