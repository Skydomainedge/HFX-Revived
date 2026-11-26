package top.weixiansen574.hybridfilexfer.core.protocol;

/**
 * Provides HMAC-SHA256 pairing secret lookup for HFX v4 server handshakes.
 */
public interface SessionAuthProvider {
    SessionAuthProvider NO_AUTH = new SessionAuthProvider() {
        @Override
        public boolean isAuthRequired() {
            return false;
        }

        @Override
        public byte[] lookupPairSecret(String clientId) {
            return null;
        }
    };

    boolean isAuthRequired();

    /**
     * Looks up the shared pairing secret for {@code clientId}, or returns {@code null} if unknown.
     */
    byte[] lookupPairSecret(String clientId);
}
