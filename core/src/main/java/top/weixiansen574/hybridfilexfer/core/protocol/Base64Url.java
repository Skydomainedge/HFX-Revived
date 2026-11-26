package top.weixiansen574.hybridfilexfer.core.protocol;

/**
 * Pure Java 8 / Android API 24+ compatible RFC 4648 Base64URL encoder/decoder
 * without padding (avoids {@code java.util.Base64}, which requires Android API 26).
 */
public final class Base64Url {
    private static final char[] ENCODE_TABLE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] DECODE_TABLE = new int[128];

    static {
        for (int i = 0; i < DECODE_TABLE.length; i++) {
            DECODE_TABLE[i] = -1;
        }
        for (int i = 0; i < ENCODE_TABLE.length; i++) {
            DECODE_TABLE[ENCODE_TABLE[i]] = i;
        }
        // Also tolerate standard Base64 '+' and '/'
        DECODE_TABLE['+'] = 62;
        DECODE_TABLE['/'] = 63;
    }

    private Base64Url() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        int len = data.length;
        StringBuilder sb = new StringBuilder(((len + 2) / 3) * 4);
        int i = 0;
        while (i + 3 <= len) {
            int triple = ((data[i] & 0xFF) << 16)
                    | ((data[i + 1] & 0xFF) << 8)
                    | (data[i + 2] & 0xFF);
            sb.append(ENCODE_TABLE[(triple >>> 18) & 0x3F]);
            sb.append(ENCODE_TABLE[(triple >>> 12) & 0x3F]);
            sb.append(ENCODE_TABLE[(triple >>> 6) & 0x3F]);
            sb.append(ENCODE_TABLE[triple & 0x3F]);
            i += 3;
        }
        int rem = len - i;
        if (rem == 1) {
            int b0 = data[i] & 0xFF;
            sb.append(ENCODE_TABLE[b0 >>> 2]);
            sb.append(ENCODE_TABLE[(b0 & 0x03) << 4]);
        } else if (rem == 2) {
            int b0 = data[i] & 0xFF;
            int b1 = data[i + 1] & 0xFF;
            sb.append(ENCODE_TABLE[b0 >>> 2]);
            sb.append(ENCODE_TABLE[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            sb.append(ENCODE_TABLE[(b1 & 0x0F) << 2]);
        }
        return sb.toString();
    }

    public static byte[] decode(String input) {
        if (input == null || input.isEmpty()) {
            return new byte[0];
        }
        int end = input.length();
        while (end > 0 && input.charAt(end - 1) == '=') {
            end--;
        }
        int mod = end % 4;
        if (mod == 1) {
            throw new IllegalArgumentException("Invalid Base64URL length: " + end);
        }
        int outLen = (end / 4) * 3 + (mod == 2 ? 1 : (mod == 3 ? 2 : 0));
        byte[] out = new byte[outLen];
        int outIdx = 0;
        int i = 0;
        while (i + 4 <= end) {
            int v0 = decodeChar(input.charAt(i));
            int v1 = decodeChar(input.charAt(i + 1));
            int v2 = decodeChar(input.charAt(i + 2));
            int v3 = decodeChar(input.charAt(i + 3));
            int triple = (v0 << 18) | (v1 << 12) | (v2 << 6) | v3;
            out[outIdx++] = (byte) ((triple >>> 16) & 0xFF);
            out[outIdx++] = (byte) ((triple >>> 8) & 0xFF);
            out[outIdx++] = (byte) (triple & 0xFF);
            i += 4;
        }
        if (mod == 2) {
            int v0 = decodeChar(input.charAt(i));
            int v1 = decodeChar(input.charAt(i + 1));
            out[outIdx] = (byte) ((v0 << 2) | (v1 >>> 4));
        } else if (mod == 3) {
            int v0 = decodeChar(input.charAt(i));
            int v1 = decodeChar(input.charAt(i + 1));
            int v2 = decodeChar(input.charAt(i + 2));
            out[outIdx++] = (byte) ((v0 << 2) | (v1 >>> 4));
            out[outIdx] = (byte) (((v1 & 0x0F) << 4) | (v2 >>> 2));
        }
        return out;
    }

    private static int decodeChar(char c) {
        if (c >= 128 || DECODE_TABLE[c] < 0) {
            throw new IllegalArgumentException("Invalid Base64URL character: '" + c + "'");
        }
        return DECODE_TABLE[c];
    }
}
