package top.weixiansen574.hybridfilexfer.core.protocol;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact QR pairing URI codec.
 *
 * <p>Format:
 * {@code hfx://pair?v=1&id=<pcId12>&n=<urlEncHost>&p=<5741>&t=<base64url_16B_token>&i=<ip1,ip2>&m=<mdnsName>}</p>
 */
public final class PairUriCodec {
    public static final String SCHEME_PREFIX = "hfx://pair?";
    public static final int CURRENT_VERSION = 1;

    private PairUriCodec() {
    }

    public static final class PairPayload {
        public final int version;
        public final String pcId;
        public final String hostName;
        public final int port;
        public final byte[] token;
        public final List<String> ips;
        public final String mdnsName;

        public PairPayload(int version, String pcId, String hostName, int port,
                           byte[] token, List<String> ips, String mdnsName) {
            this.version = version;
            this.pcId = pcId;
            this.hostName = hostName;
            this.port = port;
            this.token = token;
            this.ips = ips == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(ips));
            this.mdnsName = mdnsName;
        }
    }

    public static String encode(PairPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.pcId == null || payload.pcId.isEmpty()) {
            throw new IllegalArgumentException("pcId must not be empty");
        }
        if (payload.port <= 0 || payload.port > 65535) {
            throw new IllegalArgumentException("invalid port: " + payload.port);
        }
        if (payload.token == null || payload.token.length == 0) {
            throw new IllegalArgumentException("token must not be empty");
        }

        StringBuilder sb = new StringBuilder(SCHEME_PREFIX);
        sb.append("v=").append(payload.version);
        sb.append("&id=").append(urlEncode(payload.pcId));
        if (payload.hostName != null && !payload.hostName.isEmpty()) {
            sb.append("&n=").append(urlEncode(payload.hostName));
        }
        sb.append("&p=").append(payload.port);
        sb.append("&t=").append(Base64Url.encode(payload.token));
        if (!payload.ips.isEmpty()) {
            sb.append("&i=");
            for (int idx = 0; idx < payload.ips.size(); idx++) {
                if (idx > 0) {
                    sb.append(',');
                }
                sb.append(urlEncode(payload.ips.get(idx)));
            }
        }
        if (payload.mdnsName != null && !payload.mdnsName.isEmpty()) {
            sb.append("&m=").append(urlEncode(payload.mdnsName));
        }
        return sb.toString();
    }

    public static PairPayload decode(String uri) {
        if (uri == null || !uri.startsWith(SCHEME_PREFIX)) {
            throw new IllegalArgumentException("Invalid HFX pairing URI: " + uri);
        }
        String query = uri.substring(SCHEME_PREFIX.length());
        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            params.put(k, v);
        }

        int version = parseRequiredInt(params, "v");
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported pair URI version: " + version);
        }
        String pcId = requireParam(params, "id");
        String hostName = params.containsKey("n") ? params.get("n") : "";
        int port = parseRequiredInt(params, "p");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid pair port: " + port);
        }
        String tokenStr = requireParam(params, "t");
        byte[] token = Base64Url.decode(tokenStr);
        if (token.length == 0) {
            throw new IllegalArgumentException("Empty pair token");
        }
        List<String> ips = new ArrayList<>();
        String ipRaw = params.get("i");
        if (ipRaw != null && !ipRaw.isEmpty()) {
            for (String ip : ipRaw.split(",")) {
                String trimmed = ip.trim();
                if (!trimmed.isEmpty()) {
                    ips.add(trimmed);
                }
            }
        }
        String mdnsName = params.containsKey("m") ? params.get("m") : "";
        return new PairPayload(version, pcId, hostName, port, token, ips, mdnsName);
    }

    private static String requireParam(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value;
    }

    private static int parseRequiredInt(Map<String, String> params, String key) {
        String raw = requireParam(params, key);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer parameter " + key + "=" + raw, e);
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unsupported", e);
        }
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unsupported", e);
        }
    }
}
