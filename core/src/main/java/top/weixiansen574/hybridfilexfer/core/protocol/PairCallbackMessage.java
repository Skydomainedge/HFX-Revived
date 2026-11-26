package top.weixiansen574.hybridfilexfer.core.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON request/response wire payloads for {@code POST /hfx/pair}.
 *
 * <p>Pure Java 8 POJOs shared by PC {@code PairingServer} and Android {@code PairClient}.</p>
 */
public final class PairCallbackMessage {
    private PairCallbackMessage() {
    }

    public static class Request {
        public int version = PairUriCodec.CURRENT_VERSION;
        public String deviceId;
        public String deviceName;
        public int port = ProtocolConstants.DEFAULT_CONTROL_PORT;
        public List<String> ips = new ArrayList<>();
        public String usbType;
        public String nonce;
        public String proof;

        public Request() {
        }
    }

    public static class Response {
        public boolean ok;
        public String pcId;
        public String pcName;
        public String ackProof;
        public String error;

        public Response() {
        }
    }
}
