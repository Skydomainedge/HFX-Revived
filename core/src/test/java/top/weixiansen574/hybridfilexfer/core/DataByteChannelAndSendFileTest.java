package top.weixiansen574.hybridfilexfer.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channels;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DataByteChannelAndSendFileTest {

    /**
     * Simulates an aggressive partial-write gathering channel that writes at most
     * {@code maxBytesPerCall} bytes per syscall (and occasionally returns 0).
     */
    private static final class PartialGatheringChannel implements ByteChannel, GatheringByteChannel {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int maxBytesPerCall;
        private final boolean injectZeroWrites;
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger gatheringCalls = new AtomicInteger();
        private boolean open = true;
        private int callSeq = 0;

        PartialGatheringChannel(int maxBytesPerCall, boolean injectZeroWrites) {
            this.maxBytesPerCall = maxBytesPerCall;
            this.injectZeroWrites = injectZeroWrites;
        }

        @Override
        public int read(ByteBuffer dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            writeCalls.incrementAndGet();
            callSeq++;
            if (injectZeroWrites && (callSeq % 5 == 0)) {
                return 0;
            }
            int toWrite = Math.min(src.remaining(), maxBytesPerCall);
            byte[] tmp = new byte[toWrite];
            src.get(tmp);
            out.write(tmp);
            return toWrite;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            gatheringCalls.incrementAndGet();
            callSeq++;
            if (injectZeroWrites && (callSeq % 7 == 0)) {
                return 0;
            }
            int budget = maxBytesPerCall;
            long written = 0;
            for (int i = offset; i < offset + length && budget > 0; i++) {
                ByteBuffer src = srcs[i];
                if (!src.hasRemaining()) {
                    continue;
                }
                int n = Math.min(src.remaining(), budget);
                byte[] tmp = new byte[n];
                src.get(tmp);
                out.write(tmp);
                budget -= n;
                written += n;
            }
            return written;
        }

        @Override
        public long write(ByteBuffer[] srcs) throws IOException {
            return write(srcs, 0, srcs.length);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        int getGatheringCalls() {
            return gatheringCalls.get();
        }
    }

    private static final class ReadableOnlyByteChannel implements ByteChannel {
        private final ReadableByteChannel delegate;

        ReadableOnlyByteChannel(byte[] bytes) {
            this.delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    @Test
    public void testDataByteChannelPartialWriteCompleteness() throws Exception {
        PartialGatheringChannel sink = new PartialGatheringChannel(3, true);
        DataByteChannel outCh = new DataByteChannel(sink);

        byte[] payload = new byte[4099];
        new Random(42L).nextBytes(payload);
        String unicodeText = "测试路径/2026_高速快传_🔥.bin";

        outCh.writeBoolean(true);
        outCh.writeByte(0x7A);
        outCh.writeShort(0x1234);
        outCh.writeInt(0xCAFEBABE);
        outCh.writeLong(0x0102030405060708L);
        outCh.writeUTF(unicodeText);
        outCh.writeFully(ByteBuffer.wrap(payload));

        ByteBuffer part1 = ByteBuffer.wrap("HEADER:".getBytes(StandardCharsets.UTF_8));
        ByteBuffer part2 = ByteBuffer.wrap(payload);
        outCh.writeFully(new ByteBuffer[]{part1, part2});

        DataByteChannel inCh = new DataByteChannel(new ReadableOnlyByteChannel(sink.toByteArray()));
        assertTrue(inCh.readBoolean());
        assertEquals((byte) 0x7A, inCh.readByte());
        assertEquals((short) 0x1234, inCh.readShort());
        assertEquals(0xCAFEBABE, inCh.readInt());
        assertEquals(0x0102030405060708L, inCh.readLong());
        assertEquals(unicodeText, inCh.readUTF());

        byte[] read1 = new byte[payload.length];
        inCh.readFully(read1);
        assertArrayEquals(payload, read1);

        byte[] hdr = new byte[7];
        inCh.readFully(hdr);
        assertEquals("HEADER:", new String(hdr, StandardCharsets.UTF_8));
        byte[] read2 = new byte[payload.length];
        inCh.readFully(read2);
        assertArrayEquals(payload, read2);
    }

    @Test
    public void testSendFileCallUsesSingleWritevAndSurvivesTinyPartialWrites() throws Exception {
        // 1) Verify single gathering writev syscall per block when channel accepts full write
        PartialGatheringChannel fullSink = new PartialGatheringChannel(2 * 1024 * 1024, false);
        byte[] samplePayload = new byte[64 * 1024];
        new Random(99L).nextBytes(samplePayload);

        runSendSingleBlock(fullSink, samplePayload);
        assertEquals("Expected exactly 1 gathering writev call for 1 file block",
                1, fullSink.getGatheringCalls());

        // 2) Verify byte-for-byte identical wire stream under extreme 5-byte partial writes
        PartialGatheringChannel tinySink = new PartialGatheringChannel(5, true);
        runSendSingleBlock(tinySink, samplePayload);

        byte[] expectedWire = fullSink.toByteArray();
        byte[] actualWire = tinySink.toByteArray();
        assertArrayEquals("Wire output under partial write must match single writev output",
                expectedWire, actualWire);

        // 3) Verify framed wire format decodes cleanly
        DataByteChannel reader = new DataByteChannel(new ReadableOnlyByteChannel(actualWire));
        assertEquals(TransferIdentifiers.FILE, reader.readShort());
        assertEquals(7, reader.readInt());
        assertEquals("/dst/dir/sample_测试.dat", reader.readUTF());
        assertEquals(1700000000000L, reader.readLong());
        assertEquals(samplePayload.length, reader.readLong());
        assertEquals(0, reader.readInt());
        assertEquals(samplePayload.length, reader.readInt());
        byte[] decoded = new byte[samplePayload.length];
        reader.readFully(decoded);
        assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(samplePayload),
                MessageDigest.getInstance("SHA-256").digest(decoded));
        assertEquals(TransferIdentifiers.EOF, reader.readShort());
    }

    private void runSendSingleBlock(PartialGatheringChannel sink, byte[] payload) throws Exception {
        LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
        ByteBuffer buf = ByteBuffer.allocateDirect(payload.length);
        buf.put(payload);

        final FileBlock block = new FileBlock(
                true, 7, "/dst/dir/sample_测试.dat", 1700000000000L,
                payload.length, 0, FileBlock.DEFAULT_BLOCK_SIZE, buf);

        ReadFileCall fakeRead = new ReadFileCall(
                buffers, Collections.<RemoteFile>emptyList(),
                new Directory("/", Directory.FILE_SYSTEM_UNIX),
                new Directory("/dst", Directory.FILE_SYSTEM_UNIX), 1) {
            private int step = 0;

            @Override
            public FileBlock takeBlock() {
                if (step == 0) {
                    step++;
                    return block;
                }
                return END_POINT;
            }

            @Override
            protected boolean fileExists(String path) { return false; }

            @Override
            protected List<RemoteFile> listFiles(String path) { return null; }

            @Override
            protected java.nio.channels.FileChannel openFile(String path) { return null; }

            @Override
            protected void closeFile() { }
        };

        TransferConnection conn = new TransferConnection("ncm0", new DataByteChannel(sink));
        SendFileCall sendCall = new SendFileCall(fakeRead, conn, new NoOpTransferCallback());
        sendCall.call();

        // Buffer must have been recycled cleanly back into buffers pool
        assertEquals(1, buffers.size());
    }

    private static final class NoOpTransferCallback implements TransferFileCallback {
        @Override public void onFileUploading(String iName, String path, long targetSize, long totalSize) {}
        @Override public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {}
        @Override public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {}
        @Override public void onChannelComplete(String iName, long traffic, long time) {}
        @Override public void onChannelError(String iName, int errorType, String message) {}
        @Override public void onReadFileError(String message) {}
        @Override public void onWriteFileError(String message) {}
        @Override public void onComplete(boolean isUpload, long traffic, long time) {}
        @Override public void onIncomplete() {}
    }
}
