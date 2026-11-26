package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Multi-rail disk writer with an LRU open-file handle pool and full-write loop.
 *
 * <p>Eliminates repeated {@code open / setLength / close} thrashing when heterogeneous
 * high-speed and low-speed rails deliver blocks from different files out of order:
 * each file is preallocated at most once ({@code preallocate=true}), kept in an LRU
 * handle pool (default capacity 12), written via {@code while (data.hasRemaining())},
 * and only closed + stamped with {@code setLastModified} once all blocks are written
 * (or when the transfer completes).</p>
 */
public abstract class WriteFileCall implements Callable<Void> {
    public static final int DEFAULT_MAX_OPEN_FILES = 12;

    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final boolean[] channelFinished;
    private final ArrayList<LinkedList<FileBlock>> dequeArray;
    private final int maxOpenFiles;
    private boolean canceled = false;
    private volatile String cancelErrorMessage = null;

    private final Map<Integer, FileWriteState> activeFiles = new HashMap<>();
    private final LinkedHashMap<Integer, FileWriteState> openPool = new LinkedHashMap<>(16, 0.75f, true);
    private final List<DirEntry> createdDirs = new ArrayList<>();

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        this(buffers, dequeCount, DEFAULT_MAX_OPEN_FILES);
    }

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, int maxOpenFiles) {
        this.buffers = buffers;
        this.maxOpenFiles = Math.max(1, maxOpenFiles);
        this.dequeArray = new ArrayList<>(dequeCount);
        this.channelFinished = new boolean[dequeCount];
        for (int i = 0; i < dequeCount; i++) {
            dequeArray.add(new LinkedList<FileBlock>());
        }
    }

    private static final class FileWriteState {
        final int fileIndex;
        final String path;
        final long lastModified;
        final long totalSize;
        final long expectedBlocks;
        boolean preallocated = false;
        long writtenBlocks = 0;
        long writtenBytes = 0;
        long cursor = -1;
        OpenFileHandle handle;

        FileWriteState(int fileIndex, String path, long lastModified, long totalSize, long expectedBlocks) {
            this.fileIndex = fileIndex;
            this.path = path;
            this.lastModified = lastModified;
            this.totalSize = totalSize;
            this.expectedBlocks = expectedBlocks;
        }

        boolean isComplete() {
            if (totalSize == 0) {
                return writtenBlocks >= 1;
            }
            return writtenBytes >= totalSize && writtenBlocks >= expectedBlocks;
        }
    }

    private static final class DirEntry {
        final String path;
        final long lastModified;

        DirEntry(String path, long lastModified) {
            this.path = path;
            this.lastModified = lastModified;
        }
    }

    @Override
    public Void call() throws Exception {
        try {
            FileBlock block = takeBlock();
            while (block != null) {
                if (block.isDirectory()) {
                    tryMkdirs(block.path);
                    setFileLastModified(block.path, block.lastModified);
                    createdDirs.add(new DirEntry(block.path, block.lastModified));
                    block = takeBlock();
                    continue;
                }
                writeBlock(block);
                block = takeBlock();
            }

            if (cancelErrorMessage != null) {
                throw new IOException(cancelErrorMessage);
            }
            if (!canceled) {
                finalizeAllRemaining();
            }
        } catch (IOException e) {
            cancel();
            throw e;
        } finally {
            closeAllOpenHandlesQuietly();
        }
        return null;
    }

    private void writeBlock(FileBlock block) throws Exception {
        ByteBuffer data = block.data;
        try {
            FileWriteState state = activeFiles.get(block.fileIndex);
            if (state == null) {
                createParentDirIfNotExists(block.path);
                state = new FileWriteState(
                        block.fileIndex,
                        block.path,
                        block.lastModified,
                        block.totalSize,
                        block.calcBlockCount());
                activeFiles.put(block.fileIndex, state);
            }

            OpenFileHandle handle = acquireHandle(state);
            FileChannel channel = handle.getChannel();

            long startPos = block.getStartPosition();
            if (state.cursor != startPos) {
                channel.position(startPos);
                state.cursor = startPos;
            }

            data.flip();
            int writtenForBlock = 0;
            while (data.hasRemaining()) {
                int w = channel.write(data);
                if (w < 0) {
                    throw new IOException("FileChannel.write returned " + w + " for " + state.path);
                }
                if (w == 0) {
                    Thread.yield();
                } else {
                    state.cursor += w;
                    writtenForBlock += w;
                }
            }

            state.writtenBlocks++;
            state.writtenBytes += writtenForBlock;

            if (state.isComplete()) {
                closeHandleForState(state);
                activeFiles.remove(state.fileIndex);
                setFileLastModified(state.path, state.lastModified);
            }
        } finally {
            recycleBuffer(data);
        }
    }

    private OpenFileHandle acquireHandle(FileWriteState state) throws Exception {
        FileWriteState pooled = openPool.get(state.fileIndex);
        if (pooled != null && pooled.handle != null) {
            return pooled.handle;
        }

        while (openPool.size() >= maxOpenFiles) {
            Iterator<Map.Entry<Integer, FileWriteState>> it = openPool.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            FileWriteState evicted = it.next().getValue();
            it.remove();
            if (evicted.handle != null) {
                evicted.handle.close();
                evicted.handle = null;
                evicted.cursor = -1;
            }
        }

        boolean preallocate = !state.preallocated;
        OpenFileHandle handle = openWritableFile(state.path, state.totalSize, preallocate);
        state.preallocated = true;
        state.handle = handle;
        state.cursor = 0;
        openPool.put(state.fileIndex, state);
        return handle;
    }

    private void closeHandleForState(FileWriteState state) throws IOException {
        openPool.remove(state.fileIndex);
        if (state.handle != null) {
            try {
                state.handle.close();
            } finally {
                state.handle = null;
                state.cursor = -1;
            }
        }
    }

    private void finalizeAllRemaining() throws Exception {
        List<FileWriteState> remaining = new ArrayList<>(activeFiles.values());
        activeFiles.clear();
        for (FileWriteState state : remaining) {
            closeHandleForState(state);
            setFileLastModified(state.path, state.lastModified);
        }
        // Re-apply directory mtimes in reverse order since creating child files updates parent mtime
        for (int i = createdDirs.size() - 1; i >= 0; i--) {
            DirEntry dir = createdDirs.get(i);
            setFileLastModified(dir.path, dir.lastModified);
        }
        createdDirs.clear();
    }

    private void closeAllOpenHandlesQuietly() {
        for (FileWriteState state : openPool.values()) {
            if (state.handle != null) {
                try {
                    state.handle.close();
                } catch (IOException ignored) {
                }
                state.handle = null;
                state.cursor = -1;
            }
        }
        openPool.clear();
    }

    public ByteBuffer getBuffer() throws InterruptedException {
        return buffers.take();
    }

    public void recycleBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            buffers.add(buffer);
        }
    }

    public synchronized void finishChannel(int tIndex) {
        channelFinished[tIndex] = true;
        notifyAll();
    }

    public synchronized void cancel() {
        canceled = true;
        for (LinkedList<FileBlock> deque : dequeArray) {
            while (!deque.isEmpty()) {
                FileBlock fb = deque.removeFirst();
                if (fb.data != null) {
                    buffers.add(fb.data);
                }
            }
        }
        notifyAll();
    }

    public synchronized void cancelWithError(String reason) {
        this.cancelErrorMessage = (reason != null && !reason.isEmpty()) ? reason : "Transfer cancelled";
        cancel();
    }

    public synchronized void putBlock(FileBlock block, int tIndex) {
        if (canceled) {
            if (block.data != null) {
                buffers.add(block.data);
            }
            return;
        }
        dequeArray.get(tIndex).add(block);
        notifyAll();
    }

    private synchronized FileBlock takeBlock() throws InterruptedException {
        while (true) {
            if (canceled) {
                return null;
            }
            FileBlock block = tryTakeBlockInternal();
            if (block != null) {
                return block;
            }
            if (allChannelsFinished() && allQueuesEmpty()) {
                return null;
            }
            wait();
        }
    }

    public synchronized FileBlock tryTakeBlockInternal() {
        FileBlock minHead = null;
        int mdqIndex = -1;

        for (int i = 0; i < dequeArray.size(); i++) {
            LinkedList<FileBlock> deque = dequeArray.get(i);
            if (!deque.isEmpty()) {
                FileBlock head = deque.getFirst();
                if (minHead == null || head.compareTo(minHead) < 0) {
                    minHead = head;
                    mdqIndex = i;
                }
            }
        }
        if (minHead != null) {
            dequeArray.get(mdqIndex).removeFirst();
        }
        return minHead;
    }

    private boolean allChannelsFinished() {
        for (boolean finished : channelFinished) {
            if (!finished) return false;
        }
        return true;
    }

    private boolean allQueuesEmpty() {
        for (LinkedList<FileBlock> deque : dequeArray) {
            if (!deque.isEmpty()) return false;
        }
        return true;
    }

    protected abstract void createParentDirIfNotExists(String path) throws Exception;

    protected abstract void tryMkdirs(String path) throws Exception;

    protected abstract OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception;

    protected abstract boolean setFileLastModified(String path, long time) throws Exception;
}
