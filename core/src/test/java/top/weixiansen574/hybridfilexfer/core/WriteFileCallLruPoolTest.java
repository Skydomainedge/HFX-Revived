package top.weixiansen574.hybridfilexfer.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WriteFileCallLruPoolTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final class InstrumentedWriteFileCall extends WriteFileCall {
        final Map<String, AtomicInteger> preallocateCount = new HashMap<>();
        final Map<String, AtomicInteger> reopenCount = new HashMap<>();
        final Map<String, AtomicInteger> setLastModifiedCount = new HashMap<>();
        final AtomicInteger currentlyOpen = new AtomicInteger();
        final AtomicInteger peakOpen = new AtomicInteger();

        InstrumentedWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, int maxOpenFiles) {
            super(buffers, dequeCount, maxOpenFiles);
        }

        @Override
        protected void createParentDirIfNotExists(String path) throws Exception {
            File parent = new File(path).getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("mkdirs failed: " + parent);
            }
        }

        @Override
        protected void tryMkdirs(String path) throws Exception {
            File dir = new File(path);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("mkdirs failed: " + dir);
            }
        }

        @Override
        protected OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception {
            if (preallocate) {
                preallocateCount.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            } else {
                reopenCount.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            }
            final RandomAccessFile raf = new RandomAccessFile(path, "rw");
            if (preallocate) {
                raf.setLength(totalSize);
            }
            final FileChannel ch = raf.getChannel();
            int now = currentlyOpen.incrementAndGet();
            peakOpen.accumulateAndGet(now, Math::max);

            return new OpenFileHandle() {
                private boolean closed = false;

                @Override
                public FileChannel getChannel() {
                    return ch;
                }

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        currentlyOpen.decrementAndGet();
                        try {
                            ch.close();
                        } finally {
                            raf.close();
                        }
                    }
                }
            };
        }

        @Override
        protected boolean setFileLastModified(String path, long time) {
            setLastModifiedCount.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            return new File(path).setLastModified(time);
        }
    }

    @Test
    public void testHeterogeneousOutOfOrderInterleavedMultiFileWriteWithLruEviction() throws Exception {
        final int blockSize = 4096; // 4 KB blocks for fast multi-block interleaving test
        final int maxOpenFiles = 4; // Force real LRU evictions across 14 simultaneous files
        final int fileCount = 14;

        File outRoot = tempFolder.newFolder("out");
        LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
        for (int i = 0; i < 64; i++) {
            buffers.add(ByteBuffer.allocateDirect(blockSize));
        }

        Random rng = new Random(20261126L);
        Map<String, byte[]> expectedData = new HashMap<>();
        Map<String, Long> expectedMtime = new HashMap<>();
        List<FileBlock> rail0Blocks = new ArrayList<>();
        List<FileBlock> rail1Blocks = new ArrayList<>();

        long baseTime = 1730000000000L; // rounded to seconds for filesystem mtime precision
        for (int f = 0; f < fileCount; f++) {
            String path = new File(outRoot, "sub" + (f % 3) + "/file_" + f + ".bin").getAbsolutePath();
            long mtime = baseTime + (f * 10_000L);
            expectedMtime.put(path, mtime);

            int size = (f == 0) ? 0 : (blockSize * (2 + (f % 4)) + (f * 137));
            byte[] data = new byte[size];
            rng.nextBytes(data);
            expectedData.put(path, data);

            if (size == 0) {
                ByteBuffer b = ByteBuffer.allocate(0);
                rail0Blocks.add(new FileBlock(true, f, path, mtime, 0, 0, blockSize, b));
                continue;
            }
            int blocks = (size + blockSize - 1) / blockSize;
            for (int blk = 0; blk < blocks; blk++) {
                int off = blk * blockSize;
                int len = Math.min(blockSize, size - off);
                ByteBuffer b = ByteBuffer.allocate(len);
                b.put(data, off, len);
                FileBlock fb = new FileBlock(true, f, path, mtime, size, blk, blockSize, b);
                // Split across fast rail 0 (mostly later files & even blocks) and slow rail 1 (lagging odd blocks)
                if ((blk % 2 == 0) && (f % 2 == 0)) {
                    rail0Blocks.add(fb);
                } else {
                    rail1Blocks.add(fb);
                }
            }
        }

        // Deliberately shuffle both rail queues to maximize cross-file interleaving and out-of-order delivery
        Collections.shuffle(rail0Blocks, new Random(11L));
        Collections.shuffle(rail1Blocks, new Random(22L));

        InstrumentedWriteFileCall writer = new InstrumentedWriteFileCall(buffers, 2, maxOpenFiles);
        for (FileBlock fb : rail0Blocks) {
            writer.putBlock(fb, 0);
        }
        for (FileBlock fb : rail1Blocks) {
            writer.putBlock(fb, 1);
        }
        writer.finishChannel(0);
        writer.finishChannel(1);

        writer.call();

        // 1. LRU pool cap respected & zero handle leaks
        assertTrue("Peak open files exceeded LRU cap: " + writer.peakOpen.get(),
                writer.peakOpen.get() <= maxOpenFiles);
        assertEquals("All open file handles must be closed after write completes",
                0, writer.currentlyOpen.get());

        // 2. Every file was preallocated EXACTLY ONCE, stamped lastModified EXACTLY ONCE, and SHA-256 matches 100%
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, byte[]> entry : expectedData.entrySet()) {
            String path = entry.getKey();
            byte[] expectedBytes = entry.getValue();
            File writtenFile = new File(path);
            assertTrue("File missing: " + path, writtenFile.exists());
            assertEquals("File length mismatch: " + path, expectedBytes.length, writtenFile.length());
            assertEquals("setLength(preallocate) must be called exactly once for " + path,
                    1, writer.preallocateCount.get(path).get());
            assertEquals("setLastModified must be called exactly once for " + path,
                    1, writer.setLastModifiedCount.get(path).get());

            byte[] actualBytes = Files.readAllBytes(writtenFile.toPath());
            assertArrayEquals("SHA-256 mismatch for " + path,
                    sha.digest(expectedBytes), sha.digest(actualBytes));
            long diffMs = Math.abs(writtenFile.lastModified() - expectedMtime.get(path));
            assertTrue("lastModified drift too large for " + path + ": " + diffMs + "ms", diffMs <= 2000L);
        }
    }
}
