package top.weixiansen574.hybridfilexfer.core;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public abstract class ReadFileCall implements Callable<Void> {
    public static final FileBlock END_POINT = new FileBlock(true, -1, "END_POINT", 0, 0, -1, null);
    public static final FileBlock INTERRUPT = new FileBlock(true, -1, "INTERRUPT", 0, 0, -1, null);
    public static final FileBlock READ_ERROR = new FileBlock(true, -1, "READ_ERROR", 0, 0, -1, null);
    public static final FileBlock WRITE_ERROR = new FileBlock(true, -1, "WRITE_ERROR", 0, 0, -1, null);

    private final LinkedBlockingDeque<FileBlock> deque = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    private final int operateThreadCount;
    private final int blockSize;
    private volatile boolean stopped = false;
    private int fileIndex = -1;

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                        Directory localDir, Directory remoteDir, int operateThreadCount) {
        this(buffers, files, localDir, remoteDir, operateThreadCount, FileBlock.DEFAULT_BLOCK_SIZE);
    }

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                        Directory localDir, Directory remoteDir, int operateThreadCount, int blockSize) {
        this.buffers = buffers;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.operateThreadCount = operateThreadCount;
        this.blockSize = blockSize > 0 ? blockSize : FileBlock.DEFAULT_BLOCK_SIZE;
    }

    public int getBlockSize() {
        return blockSize;
    }

    @Override
    public Void call() throws Exception {
        try {
            for (RemoteFile file : files) {
                if (stopped) {
                    return null;
                }
                if (!fileExists(file.getPath())) {
                    continue;
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file);
                }
            }
            if (!stopped) {
                for (int i = 0; i < operateThreadCount; i++) {
                    deque.add(END_POINT);
                }
            }
        } catch (Exception e) {
            if (!stopped) {
                for (int i = 0; i < operateThreadCount; i++) {
                    deque.add(READ_ERROR);
                }
            }
            throw e;
        }
        return null;
    }

    private void listFilesAndRead(RemoteFile folder) throws Exception {
        List<RemoteFile> childFiles = listFiles(folder.getPath());
        if (childFiles != null) {
            for (RemoteFile file : childFiles) {
                if (stopped) {
                    return;
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file);
                }
            }
        }
    }

    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        if (file.isDirectory()) {
            deque.add(new FileBlock(false,
                    fileIndex, localDir.generateTransferPath(file.getPath(), remoteDir),
                    file.lastModified(), 0, 0, blockSize, null));
            return;
        }
        FileChannel channel = openFile(file.getPath());
        try {
            long length = channel.size();
            long lastModified = file.lastModified();
            String transferPath = localDir.generateTransferPath(file.getPath(), remoteDir);
            if (length == 0) {
                ByteBuffer buffer = buffers.take();
                buffer.clear();
                buffer.limit(0);
                deque.add(new FileBlock(true, fileIndex, transferPath, lastModified, 0, 0, blockSize, buffer));
                return;
            }
            long remaining = length;
            int idx = 0;
            while (remaining > 0 && !stopped) {
                int blkSize = (int) Math.min(remaining, blockSize);
                ByteBuffer buffer = buffers.take();
                buffer.clear();
                buffer.limit(blkSize);
                try {
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer);
                        if (read == -1) {
                            throw new EOFException("Unexpected EOF while reading " + file.getPath()
                                    + " (expected " + length + " bytes)");
                        }
                    }
                } catch (Exception readEx) {
                    recycleBuffer(buffer);
                    throw readEx;
                }
                deque.add(new FileBlock(true, fileIndex, transferPath, lastModified, length, idx, blockSize, buffer));
                remaining -= blkSize;
                idx++;
            }
        } finally {
            closeFile();
        }
    }

    public void recycleBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            buffers.add(buffer);
        }
    }

    public FileBlock takeBlock() throws InterruptedException {
        return deque.take();
    }

    public void shutdownByWriteError() {
        stopped = true;
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount; i++) {
            deque.addFirst(WRITE_ERROR);
        }
    }

    public void shutdownByConnectionBreak() {
        stopped = true;
        recycleAllBuffer();
        for (int i = 0; i < Math.max(0, operateThreadCount - 1); i++) {
            deque.addFirst(INTERRUPT);
        }
    }

    private void recycleAllBuffer() {
        FileBlock fb;
        while ((fb = deque.poll()) != null) {
            if (fb.data != null) {
                recycleBuffer(fb.data);
            }
        }
    }

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    protected abstract FileChannel openFile(String path) throws Exception;

    protected abstract void closeFile() throws Exception;
}
