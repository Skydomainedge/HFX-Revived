package top.weixiansen574.nio;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Full-write / full-read wrapper over {@link ByteChannel} with optional
 * {@link GatheringByteChannel} ({@code writev}) support.
 *
 * <p>Fixes the v3 silent partial-write bug by guaranteeing all write operations
 * loop until every remaining byte is written.</p>
 */
public class DataByteChannel implements ByteChannel, GatheringByteChannel, DataInput, DataOutput {
    private final ByteChannel origin;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8);
    private final ByteBuffer writeBuffer = ByteBuffer.allocate(8);

    public DataByteChannel(ByteChannel origin) {
        if (origin == null) {
            throw new IllegalArgumentException("origin channel must not be null");
        }
        this.origin = origin;
    }

    public ByteChannel getOrigin() {
        return origin;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return origin.read(dst);
    }

    /**
     * Writes all remaining bytes from {@code src} into the underlying channel.
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
        int total = src.remaining();
        writeFully(src);
        return total;
    }

    public void writeFully(ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            int written = origin.write(src);
            if (written < 0) {
                throw new IOException("Underlying channel returned " + written + " on write");
            }
            if (written == 0) {
                Thread.yield();
            }
        }
    }

    @Override
    public long write(ByteBuffer[] srcs) throws IOException {
        return writeFully(srcs, 0, srcs.length);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return writeFully(srcs, offset, length);
    }

    public long writeFully(ByteBuffer[] srcs) throws IOException {
        return writeFully(srcs, 0, srcs.length);
    }

    /**
     * Writes all remaining bytes across {@code srcs[offset .. offset+length-1]}.
     * Uses a single {@link GatheringByteChannel#write(ByteBuffer[], int, int)} loop
     * ({@code writev}) whenever the underlying channel supports gathering writes.
     */
    public long writeFully(ByteBuffer[] srcs, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > srcs.length) {
            throw new IndexOutOfBoundsException("Invalid slice: offset=" + offset + ", length=" + length);
        }
        long totalWritten = 0;
        if (origin instanceof GatheringByteChannel) {
            GatheringByteChannel gathering = (GatheringByteChannel) origin;
            int idx = offset;
            int end = offset + length;
            while (idx < end) {
                while (idx < end && !srcs[idx].hasRemaining()) {
                    idx++;
                }
                if (idx >= end) {
                    break;
                }
                long written = gathering.write(srcs, idx, end - idx);
                if (written < 0) {
                    throw new IOException("Underlying gathering channel returned " + written);
                }
                if (written == 0) {
                    Thread.yield();
                } else {
                    totalWritten += written;
                }
            }
        } else {
            for (int i = offset; i < offset + length; i++) {
                ByteBuffer src = srcs[i];
                int rem = src.remaining();
                if (rem > 0) {
                    writeFully(src);
                    totalWritten += rem;
                }
            }
        }
        return totalWritten;
    }

    @Override
    public boolean isOpen() {
        return origin.isOpen();
    }

    @Override
    public void close() throws IOException {
        origin.close();
    }

    public void readFully(ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            int read = origin.read(dst);
            if (read == -1) {
                throw new EOFException("Unexpected EOF with " + dst.remaining() + " bytes remaining");
            }
            if (read == 0) {
                Thread.yield();
            }
        }
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        readFully(ByteBuffer.wrap(b));
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        readFully(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public int skipBytes(int n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        int remaining = n;
        int skipped = 0;
        ByteBuffer skipBuf = ByteBuffer.allocate(Math.min(n, 4096));
        while (remaining > 0) {
            skipBuf.clear();
            if (remaining < skipBuf.capacity()) {
                skipBuf.limit(remaining);
            }
            int read = origin.read(skipBuf);
            if (read == -1) {
                break;
            }
            if (read == 0) {
                Thread.yield();
            } else {
                skipped += read;
                remaining -= read;
            }
        }
        return skipped;
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        readBuffer.clear();
        readBuffer.limit(1);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.get();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return readByte() & 0xFF;
    }

    @Override
    public short readShort() throws IOException {
        readBuffer.clear();
        readBuffer.limit(2);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.getShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return readShort() & 0xFFFF;
    }

    @Override
    public char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public int readInt() throws IOException {
        readBuffer.clear();
        readBuffer.limit(4);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.getInt();
    }

    @Override
    public long readLong() throws IOException {
        readBuffer.clear();
        readBuffer.limit(8);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.getLong();
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public String readLine() throws IOException {
        throw new UnsupportedOperationException("readLine() is not implemented.");
    }

    @Override
    public String readUTF() throws IOException {
        int length = readUnsignedShort();
        byte[] bytes = new byte[length];
        if (length > 0) {
            readFully(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void write(int b) throws IOException {
        writeByte(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }
        writeFully(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        writeByte(v ? 1 : 0);
    }

    @Override
    public void writeByte(int v) throws IOException {
        writeBuffer.clear();
        writeBuffer.put((byte) v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeShort(int v) throws IOException {
        writeBuffer.clear();
        writeBuffer.putShort((short) v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeChar(int v) throws IOException {
        writeShort(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        writeBuffer.clear();
        writeBuffer.putInt(v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeLong(long v) throws IOException {
        writeBuffer.clear();
        writeBuffer.putLong(v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    @Override
    public void writeBytes(String s) throws IOException {
        write(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeChars(String s) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            buf.putChar(s.charAt(i));
        }
        buf.flip();
        writeFully(buf);
    }

    @Override
    public void writeUTF(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("UTF string too long: " + bytes.length + " bytes");
        }
        ByteBuffer packet = ByteBuffer.allocate(2 + bytes.length);
        packet.putShort((short) bytes.length);
        packet.put(bytes);
        packet.flip();
        writeFully(packet);
    }
}
