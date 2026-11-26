package top.weixiansen574.hybridfilexfer.core.protocol;

import java.nio.charset.StandardCharsets;

public final class ProtocolConstants {
    public static final String ENGINE_VERSION = "4.0.0";
    public static final int VERSION_CODE = 400;

    public static final String MAGIC_CONTROL = "HFXC";
    public static final String MAGIC_DATA = "HFXD";
    public static final byte[] MAGIC_CONTROL_BYTES = MAGIC_CONTROL.getBytes(StandardCharsets.US_ASCII);
    public static final byte[] MAGIC_DATA_BYTES = MAGIC_DATA.getBytes(StandardCharsets.US_ASCII);

    public static final int DEFAULT_CONTROL_PORT = 5740;
    public static final int DEFAULT_PAIR_PORT = 5741;

    public static final int MIN_BLOCK_SIZE = 256 * 1024;          // 256 KB
    public static final int DEFAULT_BLOCK_SIZE = 1024 * 1024;     // 1 MB
    public static final int HIGH_SPEED_BLOCK_SIZE = 2 * 1024 * 1024; // 2 MB
    public static final int MAX_BLOCK_SIZE = 4 * 1024 * 1024;     // 4 MB

    public static final int NONCE_LENGTH = 16;
    public static final int SESSION_TOKEN_LENGTH = 16;
    public static final int HMAC_LENGTH = 32;
    public static final int DATA_HELLO_LENGTH = 4 + SESSION_TOKEN_LENGTH + 2 + 1; // 23 bytes

    public static final int CAP_MULTI_STREAM = 1 << 0;
    public static final int CAP_AUTH_HMAC = 1 << 1;
    public static final int CAP_V4_FRAME_COALESCE = 1 << 2;
    public static final int CAP_DYNAMIC_BLOCK = 1 << 3;
    public static final int ALL_V4_CAPS =
            CAP_MULTI_STREAM | CAP_AUTH_HMAC | CAP_V4_FRAME_COALESCE | CAP_DYNAMIC_BLOCK;

    public static final String USB_ADB_RAIL_NAME = "USB_ADB";
    public static final int MAX_STREAMS_PER_RAIL = 8;
    public static final int DATA_HANDSHAKE_TIMEOUT_MS = 20_000;
    public static final int DATA_HELLO_READ_TIMEOUT_MS = 5_000;
    public static final int SOCKET_CONNECT_TIMEOUT_MS = 10_000;

    private ProtocolConstants() {
    }

    public static int clampBlockSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_BLOCK_SIZE;
        }
        return Math.max(MIN_BLOCK_SIZE, Math.min(MAX_BLOCK_SIZE, requested));
    }

    public static int clampStreamCount(int requested) {
        if (requested <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_STREAMS_PER_RAIL, requested));
    }
}
