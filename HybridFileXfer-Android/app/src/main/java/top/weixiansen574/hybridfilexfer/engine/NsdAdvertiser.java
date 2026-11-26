package top.weixiansen574.hybridfilexfer.engine;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Android NSD ({@link NsdManager}) + {@link WifiManager.MulticastLock} advertiser for
 * {@code _hfx._tcp.}, supporting dynamic {@code pair_ack} TXT updates for Windows Firewall
 * reverse-fallback pairing and deterministic MulticastLock release on stop/destroy.
 *
 * <p>Architecture red line: strictly zero UI / Activity / View / Widget / Toast / PrintStream references.</p>
 */
public class NsdAdvertiser implements MdnsAdvertisementBridge {

    private static final String MULTICAST_LOCK_TAG = "HybridFileXfer_mDNS";

    private final Context appContext;
    private final NsdManager nsdManager;
    private final WifiManager wifiManager;
    private final Object lock = new Object();

    private WifiManager.MulticastLock multicastLock;
    private NsdManager.RegistrationListener activeListener;
    private boolean advertising = false;

    private int lastPort = ProtocolConstants.DEFAULT_CONTROL_PORT;
    private String lastDeviceId;
    private String lastDeviceName;
    private List<String> lastIps = new ArrayList<>();
    private String lastUsbType;
    private String lastPairAck;

    public NsdAdvertiser(Context context) {
        this.appContext = context.getApplicationContext();
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        this.wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public void startAdvertising(int port,
                                 String deviceId,
                                 String deviceName,
                                 List<String> ips,
                                 String usbType,
                                 String pairAck) {
        synchronized (lock) {
            this.lastPort = port > 0 ? port : ProtocolConstants.DEFAULT_CONTROL_PORT;
            this.lastDeviceId = deviceId;
            this.lastDeviceName = deviceName;
            this.lastIps = ips != null ? new ArrayList<>(ips) : new ArrayList<>();
            this.lastUsbType = usbType;
            this.lastPairAck = pairAck;
            this.advertising = true;
            registerLocked();
        }
    }

    @Override
    public void updatePairAck(String pairAck) {
        synchronized (lock) {
            boolean same = (pairAck == null && lastPairAck == null)
                    || (pairAck != null && pairAck.equals(lastPairAck));
            this.lastPairAck = pairAck;
            if (!advertising || same) {
                return;
            }
            // Re-register NSD service so peers receive the updated pair_ack TXT record promptly
            registerLocked();
        }
    }

    @Override
    public void stopAdvertising() {
        synchronized (lock) {
            advertising = false;
            lastPairAck = null;
            try {
                unregisterLocked();
            } finally {
                releaseMulticastLockLocked();
            }
        }
    }

    private void registerLocked() {
        unregisterLocked();
        if (nsdManager == null) {
            return;
        }
        acquireMulticastLockLocked();

        NsdServiceInfo info = new NsdServiceInfo();
        String shortId = (lastDeviceId != null && lastDeviceId.length() > 8)
                ? lastDeviceId.substring(0, 8)
                : (lastDeviceId != null ? lastDeviceId : "android");
        info.setServiceName("HFX-" + shortId);
        info.setServiceType(ProtocolConstants.MDNS_ANDROID_SERVICE_TYPE);
        info.setPort(lastPort);
        info.setAttribute("v", String.valueOf(ProtocolConstants.VERSION_CODE));
        if (lastDeviceId != null && !lastDeviceId.isEmpty()) {
            info.setAttribute("id", lastDeviceId);
        }
        if (lastDeviceName != null && !lastDeviceName.isEmpty()) {
            info.setAttribute("n", lastDeviceName);
        }
        if (lastUsbType != null && !lastUsbType.isEmpty()) {
            info.setAttribute("usb", lastUsbType);
        }
        if (lastIps != null && !lastIps.isEmpty()) {
            info.setAttribute("ips", joinCsv(lastIps));
        }
        if (lastPairAck != null && !lastPairAck.isEmpty()) {
            info.setAttribute("pair_ack", lastPairAck);
        }

        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }
        };

        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            this.activeListener = listener;
        } catch (Exception ignored) {
            this.activeListener = null;
        }
    }

    private void unregisterLocked() {
        NsdManager.RegistrationListener listener = this.activeListener;
        this.activeListener = null;
        if (listener != null && nsdManager != null) {
            try {
                nsdManager.unregisterService(listener);
            } catch (Exception ignored) {
            }
        }
    }

    private void acquireMulticastLockLocked() {
        if (wifiManager == null) {
            return;
        }
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG);
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLockLocked() {
        WifiManager.MulticastLock ml = this.multicastLock;
        if (ml != null) {
            try {
                if (ml.isHeld()) {
                    ml.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String joinCsv(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (item == null || item.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(item.trim());
        }
        return sb.toString();
    }
}
