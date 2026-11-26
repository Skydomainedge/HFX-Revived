package top.weixiansen574.hybridfilexfer.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Explicit data channel registration packet ({@code "HFXD" + sessionToken(16B) + railId(2B) + streamIdx(1B)}).
 *
 * <p>Eliminates v3 blind {@code accept()} race conditions when opening multiple rails
 * and multiple concurrent streams per rail in parallel.</p>
 */
public final class DataChannelHandshake {
    public static final byte ACK_OK = 1;
    public static final byte ACK_REJECT = 0;

    private DataChannelHandshake() {
    }

    public static final class DataHello {
        public final byte[] sessionToken;
        public final int railId;
        public final int streamIndex;

        public DataHello(byte[] sessionToken, int railId, int streamIndex) {
            this.sessionToken = sessionToken;
            this.railId = railId;
            this.streamIndex = streamIndex;
        }
    }

    public static ByteBuffer encodeClientHello(byte[] sessionToken, int railId, int streamIndex) {
        if (sessionToken == null || sessionToken.length != ProtocolConstants.SESSION_TOKEN_LENGTH) {
            throw new IllegalArgumentException("sessionToken must be 16 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(ProtocolConstants.DATA_HELLO_LENGTH);
        buf.put(ProtocolConstants.MAGIC_DATA_BYTES);
        buf.put(sessionToken);
        buf.putShort((short) railId);
        buf.put((byte) streamIndex);
        buf.flip();
        return buf;
    }

    public static DataHello decodeClientHello(ByteBuffer buf) throws IOException {
        if (buf.remaining() < ProtocolConstants.DATA_HELLO_LENGTH) {
            throw new IOException("Truncated HFXD header: " + buf.remaining() + " bytes");
        }
        byte[] magic = new byte[4];
        buf.get(magic);
        if (!Arrays.equals(ProtocolConstants.MAGIC_DATA_BYTES, magic)) {
            throw new IOException("Invalid HFXD magic");
        }
        byte[] token = new byte[ProtocolConstants.SESSION_TOKEN_LENGTH];
        buf.get(token);
        int railId = buf.getShort() & 0xFFFF;
        int streamIdx = buf.get() & 0xFF;
        return new DataHello(token, railId, streamIdx);
    }

    public static void writeClientHelloAndAwaitAck(SocketChannel channel, byte[] sessionToken,
                                                   int railId, int streamIndex, int timeoutMs) throws IOException {
        ByteBuffer hello = encodeClientHello(sessionToken, railId, streamIndex);
        while (hello.hasRemaining()) {
            int w = channel.write(hello);
            if (w < 0) {
                throw new IOException("Closed while writing HFXD hello");
            }
            if (w == 0) {
                Thread.yield();
            }
        }
        ByteBuffer ack = ByteBuffer.allocate(1);
        readFullyWithTimeout(channel, ack, timeoutMs);
        ack.flip();
        byte status = ack.get();
        if (status != ACK_OK) {
            throw new IOException("Server rejected HFXD data channel (railId=" + railId
                    + ", streamIdx=" + streamIndex + ")");
        }
    }

    public static DataHello readClientHelloWithTimeout(SocketChannel channel, int timeoutMs) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(ProtocolConstants.DATA_HELLO_LENGTH);
        readFullyWithTimeout(channel, buf, timeoutMs);
        buf.flip();
        return decodeClientHello(buf);
    }

    public static void writeServerAck(SocketChannel channel, boolean accepted) throws IOException {
        ByteBuffer ack = ByteBuffer.allocate(1);
        ack.put(accepted ? ACK_OK : ACK_REJECT);
        ack.flip();
        while (ack.hasRemaining()) {
            int w = channel.write(ack);
            if (w < 0) {
                throw new IOException("Closed while writing HFXD ack");
            }
            if (w == 0) {
                Thread.yield();
            }
        }
    }

    public static boolean tokenMatches(byte[] expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
    }

    public static void readFullyWithTimeout(SocketChannel channel, ByteBuffer dst, int timeoutMs) throws IOException {
        boolean wasBlocking = channel.isBlocking();
        if (wasBlocking) {
            channel.configureBlocking(false);
        }
        Selector selector = null;
        try {
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);
            long deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
            while (dst.hasRemaining()) {
                int read = channel.read(dst);
                if (read == -1) {
                    throw new EOFException("EOF while reading HFXD handshake");
                }
                if (!dst.hasRemaining()) {
                    break;
                }
                long remainingMs = deadline - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    throw new SocketTimeoutException("Timed out waiting for HFXD handshake bytes");
                }
                selector.select(Math.min(remainingMs, 250));
                selector.selectedKeys().clear();
            }
        } finally {
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException ignored) {
                }
            }
            if (wasBlocking && channel.isOpen()) {
                channel.configureBlocking(true);
            }
        }
    }
}
