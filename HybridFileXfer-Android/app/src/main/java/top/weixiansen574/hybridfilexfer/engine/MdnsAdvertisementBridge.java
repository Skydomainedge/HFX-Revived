package top.weixiansen574.hybridfilexfer.engine;

import java.util.List;

/**
 * Platform-neutral mDNS advertisement interface so pure-Java {@link HfxEngineController}
 * can coordinate {@code _hfx._tcp.} advertising and firewall {@code pair_ack} TXT updates
 * without importing {@code android.*}.
 */
public interface MdnsAdvertisementBridge {

    MdnsAdvertisementBridge NO_OP = new MdnsAdvertisementBridge() {
        @Override
        public void startAdvertising(int port, String deviceId, String deviceName,
                                     List<String> ips, String usbType, String pairAck) {
        }

        @Override
        public void updatePairAck(String pairAck) {
        }

        @Override
        public void stopAdvertising() {
        }
    };

    void startAdvertising(int port, String deviceId, String deviceName,
                          List<String> ips, String usbType, String pairAck);

    void updatePairAck(String pairAck);

    void stopAdvertising();
}
