package top.weixiansen574.hybridfilexfer.core.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic helpers for HFX v4 control handshake HMAC-SHA256 authentication,
 * random nonces/tokens, and mDNS {@code pair_ack} hash derivation.
 */
public final class HmacAuth {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private HmacAuth() {
    }

    public static byte[] generateRandomBytes(int length) {
        byte[] out = new byte[length];
        SECURE_RANDOM.nextBytes(out);
        return out;
    }

    public static byte[] computeHandshakeHmac(byte[] pairSecret, byte[] nonceC, byte[] nonceS, String clientId) {
        if (pairSecret == null || pairSecret.length == 0) {
            throw new IllegalArgumentException("pairSecret must not be empty");
        }
        if (nonceC == null || nonceS == null) {
            throw new IllegalArgumentException("nonces must not be null");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pairSecret, "HmacSHA256"));
            mac.update(nonceC);
            mac.update(nonceS);
            if (clientId != null) {
                mac.update(clientId.getBytes(StandardCharsets.UTF_8));
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean verifyHandshakeHmac(byte[] pairSecret, byte[] nonceC, byte[] nonceS,
                                              String clientId, byte[] candidateHmac) {
        if (pairSecret == null || pairSecret.length == 0 || candidateHmac == null
                || candidateHmac.length != ProtocolConstants.HMAC_LENGTH) {
            return false;
        }
        byte[] expected = computeHandshakeHmac(pairSecret, nonceC, nonceS, clientId);
        return MessageDigest.isEqual(expected, candidateHmac);
    }

    public static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(byte[] input) {
        return toHex(sha256(input));
    }

    /**
     * Computes the 16-hex-character mDNS fallback {@code pair_ack} tag from the pairing token.
     */
    public static String computePairAckHash(byte[] token) {
        return sha256Hex(token).substring(0, 16);
    }

    public static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX_CHARS[v >>> 4];
            chars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(chars);
    }
}
