package top.weixiansen574.hybridfilexfer.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.Base64Url;
import top.weixiansen574.hybridfilexfer.core.protocol.SessionAuthProvider;

/**
 * Headless store for trusted PCs paired with this Android device.
 *
 * <p>Pure Java 8 (zero {@code android.*} references) backed by a pluggable
 * {@link PersistenceBackend} (SQLite {@code ConfigDB.trusted_pcs} on Android, or in-memory in JVM
 * unit tests). Implements {@link SessionAuthProvider} so {@code AbstractHFXServer} validates
 * incoming PC {@code HFXC} HMAC handshakes seamlessly while keeping unpaired legacy {@code -c adb}
 * sessions backward-compatible.</p>
 */
public class TrustedPcStore implements SessionAuthProvider {

    public static class TrustedPcRecord {
        public String pcId;
        public String pcName;
        public String pairSecret; // Base64URL-encoded 32-byte secret
        public List<String> lastIps = new ArrayList<>();
        public long pairedAt;
        public long lastSeenAt;

        public TrustedPcRecord() {
        }

        public TrustedPcRecord copy() {
            TrustedPcRecord c = new TrustedPcRecord();
            c.pcId = this.pcId;
            c.pcName = this.pcName;
            c.pairSecret = this.pairSecret;
            c.lastIps = this.lastIps != null ? new ArrayList<>(this.lastIps) : new ArrayList<>();
            c.pairedAt = this.pairedAt;
            c.lastSeenAt = this.lastSeenAt;
            return c;
        }

        public IpcDtos.PeerInfo toPeerInfo() {
            IpcDtos.PeerInfo info = new IpcDtos.PeerInfo();
            info.deviceId = this.pcId;
            info.deviceName = this.pcName != null ? this.pcName : this.pcId;
            info.ips = this.lastIps != null ? new ArrayList<>(this.lastIps) : new ArrayList<>();
            info.trusted = true;
            info.pairedAt = this.pairedAt;
            info.lastSeenAt = this.lastSeenAt;
            return info;
        }
    }

    public interface PersistenceBackend {
        List<TrustedPcRecord> loadAll();

        void upsert(TrustedPcRecord record);

        boolean delete(String pcId);
    }

    public interface PendingCommitListener {
        void onPendingPairingCommitted(TrustedPcRecord record, boolean viaFallbackMdns);
    }

    private static final class PendingPairing {
        final String pcId;
        final String pcName;
        final byte[] pairSecret;
        final List<String> ips;
        final long expiresAt;
        volatile boolean viaFallbackMdns;

        PendingPairing(String pcId, String pcName, byte[] pairSecret,
                       List<String> ips, long expiresAt, boolean viaFallbackMdns) {
            this.pcId = pcId;
            this.pcName = pcName;
            this.pairSecret = pairSecret;
            this.ips = ips != null ? new ArrayList<>(ips) : new ArrayList<>();
            this.expiresAt = expiresAt;
            this.viaFallbackMdns = viaFallbackMdns;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final PersistenceBackend backend;
    private final Map<String, TrustedPcRecord> recordsById = new LinkedHashMap<>();
    private volatile boolean explicitAuthRequired = false;
    private volatile PendingPairing pendingPairing;
    private volatile PendingCommitListener pendingCommitListener;

    public TrustedPcStore() {
        this(null);
    }

    public TrustedPcStore(PersistenceBackend backend) {
        this.backend = backend;
        reload();
    }

    public void setPendingCommitListener(PendingCommitListener listener) {
        this.pendingCommitListener = listener;
    }

    public void setExplicitAuthRequired(boolean authRequired) {
        this.explicitAuthRequired = authRequired;
    }

    public synchronized void reload() {
        recordsById.clear();
        if (backend == null) {
            return;
        }
        List<TrustedPcRecord> loaded = backend.loadAll();
        if (loaded == null) {
            return;
        }
        for (TrustedPcRecord rec : loaded) {
            if (rec != null && rec.pcId != null && !rec.pcId.trim().isEmpty()
                    && rec.pairSecret != null && !rec.pairSecret.trim().isEmpty()) {
                TrustedPcRecord clean = rec.copy();
                clean.pcId = clean.pcId.trim();
                recordsById.put(clean.pcId, clean);
            }
        }
    }

    public synchronized void registerPendingPairing(String pcId,
                                                    String pcName,
                                                    byte[] pairSecret,
                                                    List<String> ips,
                                                    long ttlMs,
                                                    boolean viaFallbackMdns) {
        if (pcId == null || pcId.trim().isEmpty() || pairSecret == null || pairSecret.length == 0) {
            return;
        }
        long expiry = System.currentTimeMillis() + Math.max(5_000L, ttlMs);
        this.pendingPairing = new PendingPairing(
                pcId.trim(),
                (pcName != null && !pcName.trim().isEmpty()) ? pcName.trim() : pcId.trim(),
                pairSecret,
                sanitizeIps(ips),
                expiry,
                viaFallbackMdns);
    }

    public synchronized void markPendingAsFallbackMdns(String pcId) {
        PendingPairing p = this.pendingPairing;
        if (p != null && p.pcId.equals(pcId != null ? pcId.trim() : null)) {
            p.viaFallbackMdns = true;
        }
    }

    public synchronized void clearPendingPairing() {
        this.pendingPairing = null;
    }

    public synchronized boolean hasActivePendingPairing() {
        PendingPairing p = this.pendingPairing;
        if (p == null) {
            return false;
        }
        if (p.isExpired()) {
            this.pendingPairing = null;
            return false;
        }
        return true;
    }

    public synchronized TrustedPcRecord upsertTrustedPc(String pcId,
                                                        String pcName,
                                                        byte[] pairSecret,
                                                        List<String> ips) {
        if (pcId == null || pcId.trim().isEmpty()) {
            throw new IllegalArgumentException("pcId must not be empty");
        }
        if (pairSecret == null || pairSecret.length == 0) {
            throw new IllegalArgumentException("pairSecret must not be empty");
        }
        String key = pcId.trim();
        long now = System.currentTimeMillis();
        TrustedPcRecord existing = recordsById.get(key);
        TrustedPcRecord rec = existing != null ? existing.copy() : new TrustedPcRecord();
        rec.pcId = key;
        rec.pcName = (pcName != null && !pcName.trim().isEmpty())
                ? pcName.trim()
                : (rec.pcName != null ? rec.pcName : key);
        rec.pairSecret = Base64Url.encode(pairSecret);
        List<String> cleanIps = sanitizeIps(ips);
        if (!cleanIps.isEmpty()) {
            rec.lastIps = cleanIps;
        }
        if (rec.pairedAt <= 0L) {
            rec.pairedAt = now;
        }
        rec.lastSeenAt = now;

        recordsById.put(key, rec);
        if (backend != null) {
            backend.upsert(rec.copy());
        }

        PendingPairing p = this.pendingPairing;
        if (p != null && key.equals(p.pcId)) {
            this.pendingPairing = null;
        }
        return rec.copy();
    }

    public synchronized boolean removePeer(String pcId) {
        if (pcId == null || pcId.trim().isEmpty()) {
            return false;
        }
        String key = pcId.trim();
        TrustedPcRecord removed = recordsById.remove(key);
        if (backend != null) {
            backend.delete(key);
        }
        return removed != null;
    }

    public synchronized boolean hasTrustedPeers() {
        return !recordsById.isEmpty();
    }

    public synchronized int size() {
        return recordsById.size();
    }

    public synchronized TrustedPcRecord getPeer(String pcId) {
        if (pcId == null) return null;
        TrustedPcRecord rec = recordsById.get(pcId.trim());
        return rec != null ? rec.copy() : null;
    }

    public synchronized List<IpcDtos.PeerInfo> listPeers() {
        List<IpcDtos.PeerInfo> list = new ArrayList<>(recordsById.size());
        for (TrustedPcRecord rec : recordsById.values()) {
            list.add(rec.toPeerInfo());
        }
        return list;
    }

    // ---- SessionAuthProvider ----

    @Override
    public synchronized boolean isAuthRequired() {
        return explicitAuthRequired || !recordsById.isEmpty() || hasActivePendingPairing();
    }

    @Override
    public synchronized byte[] lookupPairSecret(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return null;
        }
        String key = clientId.trim();
        PendingPairing p = this.pendingPairing;
        if (p != null) {
            if (p.isExpired()) {
                this.pendingPairing = null;
            } else if (key.equals(p.pcId)) {
                return p.pairSecret;
            }
        }
        TrustedPcRecord rec = recordsById.get(key);
        if (rec == null || rec.pairSecret == null || rec.pairSecret.isEmpty()) {
            return null;
        }
        try {
            return Base64Url.decode(rec.pairSecret);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void onAuthSucceeded(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return;
        }
        String key = clientId.trim();
        TrustedPcRecord committed = null;
        boolean viaFallback = false;

        synchronized (this) {
            PendingPairing p = this.pendingPairing;
            if (p != null && !p.isExpired() && key.equals(p.pcId)) {
                viaFallback = p.viaFallbackMdns;
                committed = upsertTrustedPc(p.pcId, p.pcName, p.pairSecret, p.ips);
            } else {
                TrustedPcRecord existing = recordsById.get(key);
                if (existing != null) {
                    existing.lastSeenAt = System.currentTimeMillis();
                    if (backend != null) {
                        backend.upsert(existing.copy());
                    }
                }
            }
        }

        if (committed != null) {
            PendingCommitListener listener = this.pendingCommitListener;
            if (listener != null) {
                listener.onPendingPairingCommitted(committed, viaFallback);
            }
        }
    }

    private static List<String> sanitizeIps(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String ip : ips) {
            if (ip != null && !ip.trim().isEmpty()) {
                set.add(ip.trim());
            }
        }
        return new ArrayList<>(set);
    }
}
