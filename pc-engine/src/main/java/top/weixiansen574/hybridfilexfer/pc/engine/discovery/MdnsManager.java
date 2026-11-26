package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

/**
 * Multi-NIC mDNS/DNS-SD discovery manager for {@code _hfx._tcp.local.}.
 *
 * <p>Architecture iron rule: NEVER calls no-arg {@code JmDNS.create()}! Always filters interfaces
 * through {@link NicSelector#selectLocalNics()} first and binds one dedicated
 * {@code JmDNS.create(addr, hostName)} per real physical/USB IPv4 NIC.</p>
 */
public class MdnsManager implements AutoCloseable {

    public interface PeerDiscoveryListener {
        void onPeerDiscovered(IpcDtos.PeerInfo peer, String pairAck);

        void onPeerLost(IpcDtos.PeerInfo peer);
    }

    /**
     * Platform-neutral mDNS binding abstraction allowing deterministic unit testing while using
     * real per-NIC {@link JmDNS#create(InetAddress, String)} in production.
     */
    public interface MdnsNicBinding extends AutoCloseable {
        String getBoundIp();

        @Override
        void close();
    }

    @FunctionalInterface
    public interface MdnsBindingFactory {
        MdnsNicBinding bind(IpcDtos.NicInfo nic, String hostName, ServiceObservationSink sink) throws IOException;
    }

    /**
     * Normalized mDNS service record observed on a specific local NIC.
     */
    public static final class ObservedService {
        public final String nicIp;
        public final String serviceKey;
        public final String deviceId;
        public final String deviceName;
        public final List<String> ips;
        public final int port;
        public final String usbType;
        public final String pairAck;

        public ObservedService(String nicIp, String serviceKey, String deviceId, String deviceName,
                               List<String> ips, int port, String usbType, String pairAck) {
            this.nicIp = nicIp;
            this.serviceKey = serviceKey;
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.ips = ips != null ? Collections.unmodifiableList(new ArrayList<>(ips)) : Collections.emptyList();
            this.port = port > 0 ? port : ProtocolConstants.DEFAULT_CONTROL_PORT;
            this.usbType = usbType;
            this.pairAck = pairAck;
        }
    }

    public interface ServiceObservationSink {
        void onServiceResolved(ObservedService service);

        void onServiceRemoved(String nicIp, String serviceKey);
    }

    private final NicSelector nicSelector;
    private final TrustedPeerStore trustedPeerStore;
    private final String hostName;
    private final MdnsBindingFactory bindingFactory;

    private final CopyOnWriteArrayList<PeerDiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, MdnsNicBinding> activeBindings = new LinkedHashMap<>();
    // nicIp + "|" + serviceKey -> ObservedService
    private final Map<String, ObservedService> observationsByKey = new LinkedHashMap<>();
    // deviceId -> aggregated PeerInfo
    private final Map<String, IpcDtos.PeerInfo> peersByDeviceId = new ConcurrentHashMap<>();
    private final Map<String, String> lastPairAckByDeviceId = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile List<IpcDtos.NicInfo> boundNics = Collections.emptyList();

    public MdnsManager(NicSelector nicSelector, TrustedPeerStore trustedPeerStore, String hostName) {
        this(nicSelector, trustedPeerStore, hostName, MdnsManager::createRealJmDnsBinding);
    }

    public MdnsManager(NicSelector nicSelector,
                       TrustedPeerStore trustedPeerStore,
                       String hostName,
                       MdnsBindingFactory bindingFactory) {
        this.nicSelector = nicSelector != null ? nicSelector : new NicSelector();
        this.trustedPeerStore = trustedPeerStore;
        this.hostName = (hostName != null && !hostName.trim().isEmpty()) ? hostName.trim() : "hfx-pc";
        this.bindingFactory = bindingFactory != null ? bindingFactory : MdnsManager::createRealJmDnsBinding;
    }

    public void addListener(PeerDiscoveryListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(PeerDiscoveryListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            refreshBindings(nicSelector.selectLocalNics());
        }
    }

    public void refreshBindings() {
        refreshBindings(nicSelector.selectLocalNics());
    }

    /**
     * Reconciles active per-NIC JmDNS instances against the cleaned NIC list from {@link NicSelector}.
     */
    public void refreshBindings(List<IpcDtos.NicInfo> eligibleNics) {
        List<IpcDtos.NicInfo> targetNics = eligibleNics != null ? new ArrayList<>(eligibleNics) : Collections.emptyList();
        this.boundNics = Collections.unmodifiableList(targetNics);

        Set<String> targetIps = new LinkedHashSet<>();
        for (IpcDtos.NicInfo nic : targetNics) {
            if (nic != null && nic.ip != null && !nic.ip.trim().isEmpty()) {
                targetIps.add(nic.ip.trim());
            }
        }

        List<MdnsNicBinding> toClose = new ArrayList<>();
        synchronized (lock) {
            List<String> staleIps = new ArrayList<>();
            for (Map.Entry<String, MdnsNicBinding> entry : activeBindings.entrySet()) {
                if (!targetIps.contains(entry.getKey())) {
                    staleIps.add(entry.getKey());
                    toClose.add(entry.getValue());
                }
            }
            for (String ip : staleIps) {
                activeBindings.remove(ip);
            }
        }
        for (MdnsNicBinding binding : toClose) {
            try {
                binding.close();
            } catch (Exception ignored) {
            }
        }

        if (!started.get()) {
            return;
        }

        ServiceObservationSink sink = new ServiceObservationSink() {
            @Override
            public void onServiceResolved(ObservedService service) {
                handleServiceResolved(service);
            }

            @Override
            public void onServiceRemoved(String nicIp, String serviceKey) {
                handleServiceRemoved(nicIp, serviceKey);
            }
        };

        for (IpcDtos.NicInfo nic : targetNics) {
            if (nic == null || nic.ip == null || nic.ip.trim().isEmpty()) {
                continue;
            }
            String ip = nic.ip.trim();
            synchronized (lock) {
                if (activeBindings.containsKey(ip)) {
                    continue;
                }
            }
            try {
                MdnsNicBinding binding = bindingFactory.bind(nic, hostName, sink);
                if (binding != null) {
                    synchronized (lock) {
                        if (started.get() && !activeBindings.containsKey(ip)) {
                            activeBindings.put(ip, binding);
                        } else {
                            binding.close();
                        }
                    }
                }
            } catch (Exception ignored) {
                // Best-effort per NIC: one failing adapter never blocks other physical/USB NICs.
            }
        }
    }

    public int getBoundNicCount() {
        synchronized (lock) {
            return activeBindings.size();
        }
    }

    public List<String> getBoundNicIps() {
        synchronized (lock) {
            return new ArrayList<>(activeBindings.keySet());
        }
    }

    public List<IpcDtos.PeerInfo> getDiscoveredPeers() {
        List<IpcDtos.PeerInfo> result = new ArrayList<>(peersByDeviceId.size());
        for (IpcDtos.PeerInfo peer : peersByDeviceId.values()) {
            IpcDtos.PeerInfo copy = copyPeer(peer);
            copy.trusted = trustedPeerStore != null && trustedPeerStore.isTrusted(copy.deviceId);
            result.add(copy);
        }
        result.sort(Comparator.comparing((IpcDtos.PeerInfo p) -> !p.trusted)
                .thenComparing(p -> p.deviceName != null ? p.deviceName : ""));
        return result;
    }

    /**
     * Refreshes trusted flags on cached discovered peers after a peer is paired or removed.
     */
    public void refreshTrustedStatus() {
        for (IpcDtos.PeerInfo peer : peersByDeviceId.values()) {
            peer.trusted = trustedPeerStore != null && trustedPeerStore.isTrusted(peer.deviceId);
        }
    }

    /**
     * Selects the best currently discovered trusted peer (optionally matching {@code preferredDeviceId}).
     */
    public IpcDtos.PeerInfo findBestTrustedPeer(String preferredDeviceId) {
        List<IpcDtos.PeerInfo> peers = getDiscoveredPeers();
        for (IpcDtos.PeerInfo peer : peers) {
            if (!peer.trusted || peer.ips.isEmpty()) {
                continue;
            }
            if (preferredDeviceId != null && !preferredDeviceId.trim().isEmpty()) {
                if (preferredDeviceId.trim().equals(peer.deviceId)) {
                    return peer;
                }
            } else {
                return peer;
            }
        }
        return null;
    }

    /**
     * Ranks candidate peer IPs so same-subnet USB NIC > ETH > Wi-Fi > other IPs come first.
     */
    public List<String> rankPeerIps(List<String> rawIps) {
        if (rawIps == null || rawIps.isEmpty()) {
            return Collections.emptyList();
        }
        List<IpcDtos.NicInfo> localNics = !boundNics.isEmpty() ? boundNics : nicSelector.selectLocalNics();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String ip : rawIps) {
            if (ip != null && !ip.trim().isEmpty()) {
                unique.add(ip.trim());
            }
        }
        List<String> list = new ArrayList<>(unique);
        list.sort(Comparator.comparingInt(ip -> scoreTargetIp(ip, localNics)));
        return list;
    }

    /**
     * Directly injects an observed mDNS service record (used by both real JmDNS callbacks and unit tests).
     */
    public void handleServiceResolved(ObservedService service) {
        if (service == null || service.deviceId == null || service.deviceId.trim().isEmpty()) {
            return;
        }
        String compositeKey = (service.nicIp != null ? service.nicIp : "") + "|"
                + (service.serviceKey != null ? service.serviceKey : service.deviceId);

        IpcDtos.PeerInfo aggregated;
        String effectivePairAck;
        boolean shouldEmit;
        synchronized (lock) {
            observationsByKey.put(compositeKey, service);
            aggregated = rebuildAggregatedPeerLocked(service.deviceId.trim());
            if (aggregated == null) {
                return;
            }
            IpcDtos.PeerInfo prev = peersByDeviceId.put(aggregated.deviceId, aggregated);
            effectivePairAck = (service.pairAck != null && !service.pairAck.trim().isEmpty())
                    ? service.pairAck.trim()
                    : null;
            String prevAck = effectivePairAck != null
                    ? lastPairAckByDeviceId.put(aggregated.deviceId, effectivePairAck)
                    : lastPairAckByDeviceId.remove(aggregated.deviceId);
            shouldEmit = prev == null
                    || !prev.ips.equals(aggregated.ips)
                    || prev.port != aggregated.port
                    || prev.trusted != aggregated.trusted
                    || (effectivePairAck != null && !effectivePairAck.equals(prevAck));
        }

        if (aggregated.trusted && trustedPeerStore != null) {
            trustedPeerStore.updateLastSeen(aggregated.deviceId, aggregated.ips, aggregated.port, aggregated.usbType);
        }

        if (shouldEmit || effectivePairAck != null) {
            IpcDtos.PeerInfo eventCopy = copyPeer(aggregated);
            for (PeerDiscoveryListener l : listeners) {
                try {
                    l.onPeerDiscovered(eventCopy, effectivePairAck);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Handles mDNS service removal on a NIC and emits {@code mdns.peer_lost} once no NIC observes the peer.
     */
    public void handleServiceRemoved(String nicIp, String serviceKey) {
        String compositeKey = (nicIp != null ? nicIp : "") + "|" + (serviceKey != null ? serviceKey : "");
        IpcDtos.PeerInfo lostPeer = null;
        IpcDtos.PeerInfo updatedPeer = null;

        synchronized (lock) {
            ObservedService removed = observationsByKey.remove(compositeKey);
            if (removed == null) {
                return;
            }
            String deviceId = removed.deviceId.trim();
            IpcDtos.PeerInfo rebuilt = rebuildAggregatedPeerLocked(deviceId);
            if (rebuilt == null) {
                lostPeer = peersByDeviceId.remove(deviceId);
                lastPairAckByDeviceId.remove(deviceId);
            } else {
                peersByDeviceId.put(deviceId, rebuilt);
                updatedPeer = rebuilt;
            }
        }

        if (lostPeer != null) {
            IpcDtos.PeerInfo eventCopy = copyPeer(lostPeer);
            for (PeerDiscoveryListener l : listeners) {
                try {
                    l.onPeerLost(eventCopy);
                } catch (Exception ignored) {
                }
            }
        } else if (updatedPeer != null) {
            IpcDtos.PeerInfo eventCopy = copyPeer(updatedPeer);
            for (PeerDiscoveryListener l : listeners) {
                try {
                    l.onPeerDiscovered(eventCopy, lastPairAckByDeviceId.get(updatedPeer.deviceId));
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        started.set(false);
        List<MdnsNicBinding> bindings;
        synchronized (lock) {
            bindings = new ArrayList<>(activeBindings.values());
            activeBindings.clear();
            observationsByKey.clear();
            peersByDeviceId.clear();
            lastPairAckByDeviceId.clear();
        }
        for (MdnsNicBinding binding : bindings) {
            try {
                binding.close();
            } catch (Exception ignored) {
            }
        }
    }

    private IpcDtos.PeerInfo rebuildAggregatedPeerLocked(String deviceId) {
        List<ObservedService> matches = new ArrayList<>();
        for (ObservedService obs : observationsByKey.values()) {
            if (deviceId.equals(obs.deviceId != null ? obs.deviceId.trim() : null)) {
                matches.add(obs);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        IpcDtos.PeerInfo info = new IpcDtos.PeerInfo();
        info.deviceId = deviceId;
        info.port = ProtocolConstants.DEFAULT_CONTROL_PORT;
        info.lastSeenAt = System.currentTimeMillis();
        LinkedHashSet<String> allIps = new LinkedHashSet<>();

        for (ObservedService obs : matches) {
            if ((info.deviceName == null || info.deviceName.isEmpty()) && obs.deviceName != null) {
                info.deviceName = obs.deviceName;
            }
            if (obs.port > 0) {
                info.port = obs.port;
            }
            if ((info.usbType == null || info.usbType.isEmpty()) && obs.usbType != null && !obs.usbType.isEmpty()) {
                info.usbType = obs.usbType;
            }
            allIps.addAll(obs.ips);
        }
        if (info.deviceName == null || info.deviceName.isEmpty()) {
            info.deviceName = deviceId;
        }
        info.ips = rankPeerIps(new ArrayList<>(allIps));
        if (trustedPeerStore != null) {
            TrustedPeerStore.TrustedPeerRecord trustedRec = trustedPeerStore.getPeer(deviceId);
            info.trusted = (trustedRec != null);
            if (trustedRec != null) {
                info.pairedAt = trustedRec.pairedAt;
            }
        }
        return info;
    }

    private static int scoreTargetIp(String targetIp, List<IpcDtos.NicInfo> localNics) {
        try {
            InetAddress targetAddr = InetAddress.getByName(targetIp);
            if (!(targetAddr instanceof Inet4Address)) {
                return 100;
            }
            byte[] tBytes = targetAddr.getAddress();
            int bestScore = 50;
            for (IpcDtos.NicInfo nic : localNics) {
                if (nic == null || nic.ip == null) continue;
                InetAddress localAddr = InetAddress.getByName(nic.ip);
                if (!(localAddr instanceof Inet4Address)) continue;
                int prefix = nic.prefixLength > 0 && nic.prefixLength <= 32 ? nic.prefixLength : 24;
                if (sameSubnet(tBytes, localAddr.getAddress(), prefix)) {
                    RailType rt = parseRailType(nic.railType);
                    int prio = NicSelector.priorityOf(rt);
                    if (prio < bestScore) {
                        bestScore = prio;
                    }
                }
            }
            return bestScore;
        } catch (Exception e) {
            return 200;
        }
    }

    private static boolean sameSubnet(byte[] a, byte[] b, int prefixLength) {
        int mask = prefixLength == 0 ? 0 : (-1 << (32 - prefixLength));
        int ia = toInt(a);
        int ib = toInt(b);
        return (ia & mask) == (ib & mask);
    }

    private static int toInt(byte[] b) {
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    private static RailType parseRailType(String name) {
        if (name == null) return RailType.OTHER;
        try {
            return RailType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return RailType.OTHER;
        }
    }

    private static IpcDtos.PeerInfo copyPeer(IpcDtos.PeerInfo src) {
        IpcDtos.PeerInfo dst = new IpcDtos.PeerInfo();
        dst.deviceId = src.deviceId;
        dst.deviceName = src.deviceName;
        dst.ips = src.ips != null ? new ArrayList<>(src.ips) : new ArrayList<>();
        dst.port = src.port;
        dst.trusted = src.trusted;
        dst.usbType = src.usbType;
        dst.lastSeenAt = src.lastSeenAt;
        dst.pairedAt = src.pairedAt;
        return dst;
    }

    /**
     * Real production JmDNS binding strictly bound to one cleaned IPv4 NIC address.
     */
    private static MdnsNicBinding createRealJmDnsBinding(IpcDtos.NicInfo nic,
                                                         String hostName,
                                                         ServiceObservationSink sink) throws IOException {
        final String nicIp = nic.ip.trim();
        final InetAddress bindAddr = InetAddress.getByName(nicIp);
        final JmDNS jmdns = JmDNS.create(bindAddr, hostName + "-" + nicIp.replace('.', '-'));

        ServiceListener listener = new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent event) {
                jmdns.requestServiceInfo(event.getType(), event.getName(), true, 1500L);
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                sink.onServiceRemoved(nicIp, event.getName());
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                ObservedService obs = parseServiceInfo(nicIp, event.getInfo());
                if (obs != null) {
                    sink.onServiceResolved(obs);
                }
            }
        };
        jmdns.addServiceListener(ProtocolConstants.MDNS_SERVICE_TYPE, listener);

        return new MdnsNicBinding() {
            @Override
            public String getBoundIp() {
                return nicIp;
            }

            @Override
            public void close() {
                try {
                    jmdns.removeServiceListener(ProtocolConstants.MDNS_SERVICE_TYPE, listener);
                } catch (Exception ignored) {
                }
                try {
                    jmdns.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    static ObservedService parseServiceInfo(String nicIp, ServiceInfo info) {
        if (info == null) {
            return null;
        }
        String deviceId = info.getPropertyString("id");
        if (deviceId == null || deviceId.trim().isEmpty()) {
            deviceId = info.getName();
        }
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return null;
        }
        String deviceName = info.getPropertyString("n");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            deviceName = info.getName();
        }
        String usbType = info.getPropertyString("usb");
        String pairAck = info.getPropertyString("pair_ack");
        int port = info.getPort() > 0 ? info.getPort() : ProtocolConstants.DEFAULT_CONTROL_PORT;

        LinkedHashSet<String> ips = new LinkedHashSet<>();
        Inet4Address[] v4Addrs = info.getInet4Addresses();
        if (v4Addrs != null) {
            for (Inet4Address addr : v4Addrs) {
                if (isUsablePeerIp(addr)) {
                    ips.add(addr.getHostAddress());
                }
            }
        }
        String txtIps = info.getPropertyString("ips");
        if (txtIps != null && !txtIps.trim().isEmpty()) {
            for (String raw : txtIps.split(",")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("127.") && !trimmed.startsWith("169.254.")
                        && !trimmed.startsWith("198.18.") && !trimmed.startsWith("198.19.")) {
                    ips.add(trimmed);
                }
            }
        }
        return new ObservedService(
                nicIp,
                info.getName(),
                deviceId.trim(),
                deviceName != null ? deviceName.trim() : deviceId.trim(),
                new ArrayList<>(ips),
                port,
                usbType != null ? usbType.trim() : null,
                pairAck != null ? pairAck.trim() : null);
    }

    private static boolean isUsablePeerIp(Inet4Address addr) {
        if (addr == null || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return false;
        }
        byte[] b = addr.getAddress();
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        if (b0 == 127) return false;
        if (b0 == 169 && b1 == 254) return false;
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;
        return true;
    }
}
