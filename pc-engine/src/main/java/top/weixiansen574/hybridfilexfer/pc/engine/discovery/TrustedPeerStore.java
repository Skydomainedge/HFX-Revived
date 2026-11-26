package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.Base64Url;
import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Persistent JSON store for trusted mobile peers and this PC's stable identity
 * ({@code ~/.hybridfilexfer/trusted_peers.json} by default).
 */
public class TrustedPeerStore {

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public static class TrustedPeerRecord {
        public String deviceId;
        public String deviceName;
        public String pairSecret; // Base64URL-encoded 32-byte HMAC secret
        public List<String> lastIps = new ArrayList<>();
        public int port = ProtocolConstants.DEFAULT_CONTROL_PORT;
        public String usbType;
        public long pairedAt;
        public long lastSeenAt;

        public TrustedPeerRecord() {
        }

        public TrustedPeerRecord copy() {
            TrustedPeerRecord c = new TrustedPeerRecord();
            c.deviceId = this.deviceId;
            c.deviceName = this.deviceName;
            c.pairSecret = this.pairSecret;
            c.lastIps = this.lastIps != null ? new ArrayList<>(this.lastIps) : new ArrayList<>();
            c.port = this.port > 0 ? this.port : ProtocolConstants.DEFAULT_CONTROL_PORT;
            c.usbType = this.usbType;
            c.pairedAt = this.pairedAt;
            c.lastSeenAt = this.lastSeenAt;
            return c;
        }

        public IpcDtos.PeerInfo toPeerInfo() {
            IpcDtos.PeerInfo dto = new IpcDtos.PeerInfo();
            dto.deviceId = this.deviceId;
            dto.deviceName = this.deviceName;
            dto.ips = this.lastIps != null ? new ArrayList<>(this.lastIps) : new ArrayList<>();
            dto.port = this.port > 0 ? this.port : ProtocolConstants.DEFAULT_CONTROL_PORT;
            dto.trusted = true;
            dto.usbType = this.usbType;
            dto.pairedAt = this.pairedAt;
            dto.lastSeenAt = this.lastSeenAt;
            return dto;
        }
    }

    private static class StoreFileModel {
        int version = 1;
        String pcDeviceId;
        List<TrustedPeerRecord> peers = new ArrayList<>();
    }

    private final Path storePath;
    private final Map<String, TrustedPeerRecord> peersById = new LinkedHashMap<>();
    private String pcDeviceId;

    public TrustedPeerStore() {
        this(defaultStorePath());
    }

    public TrustedPeerStore(Path storePath) {
        this.storePath = (storePath != null ? storePath : defaultStorePath()).toAbsolutePath().normalize();
        reload();
    }

    public static Path defaultStorePath() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".hybridfilexfer", "trusted_peers.json");
    }

    public Path getStorePath() {
        return storePath;
    }

    public synchronized String getOrCreatePcDeviceId(String explicitOverride) {
        if (explicitOverride != null && !explicitOverride.trim().isEmpty()) {
            return explicitOverride.trim();
        }
        if (pcDeviceId == null || pcDeviceId.trim().isEmpty()) {
            // Compact 12-hex-char PC ID for sparse high-recognition QR codes (RFC 6.2)
            pcDeviceId = HmacAuth.toHex(HmacAuth.generateRandomBytes(6));
            saveQuietly();
        }
        return pcDeviceId;
    }

    public synchronized void reload() {
        peersById.clear();
        if (!Files.isRegularFile(storePath)) {
            return;
        }
        try {
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            if (json == null || json.trim().isEmpty()) {
                return;
            }
            StoreFileModel model = PRETTY_GSON.fromJson(json, StoreFileModel.class);
            if (model == null) {
                return;
            }
            if (model.pcDeviceId != null && !model.pcDeviceId.trim().isEmpty()) {
                this.pcDeviceId = model.pcDeviceId.trim();
            }
            if (model.peers != null) {
                for (TrustedPeerRecord rec : model.peers) {
                    if (rec != null && rec.deviceId != null && !rec.deviceId.trim().isEmpty()
                            && rec.pairSecret != null && !rec.pairSecret.trim().isEmpty()) {
                        TrustedPeerRecord clean = rec.copy();
                        clean.deviceId = clean.deviceId.trim();
                        peersById.put(clean.deviceId, clean);
                    }
                }
            }
        } catch (IOException | JsonSyntaxException ignored) {
        }
    }

    public synchronized List<IpcDtos.PeerInfo> listPeers() {
        List<IpcDtos.PeerInfo> list = new ArrayList<>(peersById.size());
        for (TrustedPeerRecord rec : peersById.values()) {
            list.add(rec.toPeerInfo());
        }
        return list;
    }

    public synchronized int size() {
        return peersById.size();
    }

    public synchronized boolean isTrusted(String deviceId) {
        return deviceId != null && peersById.containsKey(deviceId.trim());
    }

    public synchronized TrustedPeerRecord getPeer(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        TrustedPeerRecord rec = peersById.get(deviceId.trim());
        return rec != null ? rec.copy() : null;
    }

    public synchronized byte[] getPairSecret(String deviceId) {
        TrustedPeerRecord rec = getPeer(deviceId);
        if (rec == null || rec.pairSecret == null || rec.pairSecret.isEmpty()) {
            return null;
        }
        try {
            return Base64Url.decode(rec.pairSecret);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public synchronized TrustedPeerRecord findByIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return null;
        }
        String target = ip.trim();
        for (TrustedPeerRecord rec : peersById.values()) {
            if (rec.lastIps != null && rec.lastIps.contains(target)) {
                return rec.copy();
            }
        }
        return null;
    }

    public synchronized TrustedPeerRecord getSinglePeerIfUnique() {
        if (peersById.size() != 1) {
            return null;
        }
        return peersById.values().iterator().next().copy();
    }

    public synchronized TrustedPeerRecord upsertPeer(String deviceId,
                                                     String deviceName,
                                                     byte[] pairSecret,
                                                     List<String> ips,
                                                     int port,
                                                     String usbType) throws IOException {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("deviceId must not be empty");
        }
        if (pairSecret == null || pairSecret.length == 0) {
            throw new IllegalArgumentException("pairSecret must not be empty");
        }
        String key = deviceId.trim();
        long now = System.currentTimeMillis();
        TrustedPeerRecord existing = peersById.get(key);
        TrustedPeerRecord rec = existing != null ? existing.copy() : new TrustedPeerRecord();
        rec.deviceId = key;
        rec.deviceName = (deviceName != null && !deviceName.trim().isEmpty())
                ? deviceName.trim()
                : (rec.deviceName != null ? rec.deviceName : key);
        rec.pairSecret = Base64Url.encode(pairSecret);
        rec.lastIps = sanitizeIps(ips);
        rec.port = port > 0 ? port : ProtocolConstants.DEFAULT_CONTROL_PORT;
        if (usbType != null && !usbType.trim().isEmpty()) {
            rec.usbType = usbType.trim();
        }
        if (rec.pairedAt <= 0L) {
            rec.pairedAt = now;
        }
        rec.lastSeenAt = now;
        peersById.put(key, rec);
        save();
        return rec.copy();
    }

    public synchronized void updateLastSeen(String deviceId, List<String> ips, int port, String usbType) {
        if (deviceId == null) {
            return;
        }
        TrustedPeerRecord rec = peersById.get(deviceId.trim());
        if (rec == null) {
            return;
        }
        boolean changed = false;
        List<String> cleanIps = sanitizeIps(ips);
        if (!cleanIps.isEmpty() && !cleanIps.equals(rec.lastIps)) {
            rec.lastIps = cleanIps;
            changed = true;
        }
        if (port > 0 && port != rec.port) {
            rec.port = port;
            changed = true;
        }
        if (usbType != null && !usbType.trim().isEmpty() && !usbType.equals(rec.usbType)) {
            rec.usbType = usbType.trim();
            changed = true;
        }
        long now = System.currentTimeMillis();
        if (changed || (now - rec.lastSeenAt) > 30_000L) {
            rec.lastSeenAt = now;
            saveQuietly();
        }
    }

    public synchronized boolean removePeer(String deviceId) throws IOException {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return false;
        }
        TrustedPeerRecord removed = peersById.remove(deviceId.trim());
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    private synchronized void save() throws IOException {
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StoreFileModel model = new StoreFileModel();
        model.version = 1;
        model.pcDeviceId = this.pcDeviceId;
        model.peers = new ArrayList<>(peersById.values());
        String json = PRETTY_GSON.toJson(model);

        Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException ignored) {
        }
    }

    private static List<String> sanitizeIps(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String ip : ips) {
            if (ip != null) {
                String trimmed = ip.trim();
                if (!trimmed.isEmpty()) {
                    unique.add(trimmed);
                }
            }
        }
        return new ArrayList<>(unique);
    }
}
