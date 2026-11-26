package top.weixiansen574.hybridfilexfer.core;

import java.nio.ByteBuffer;

public class FileBlock implements Comparable<FileBlock> {
    public static final int DEFAULT_BLOCK_SIZE = 1024 * 1024; // 1 MB
    public static final int BLOCK_SIZE = DEFAULT_BLOCK_SIZE;

    public final boolean isFile;
    public final int fileIndex;
    public final String path;
    public final long lastModified;
    public final long totalSize;
    public final int index;
    public final int blockSize;
    public final ByteBuffer data;

    public FileBlock(boolean isFile, int fileIndex, String path, long lastModified,
                     long totalSize, int index, ByteBuffer data) {
        this(isFile, fileIndex, path, lastModified, totalSize, index, DEFAULT_BLOCK_SIZE, data);
    }

    public FileBlock(boolean isFile, int fileIndex, String path, long lastModified,
                     long totalSize, int index, int blockSize, ByteBuffer data) {
        this.isFile = isFile;
        this.fileIndex = fileIndex;
        this.path = path;
        this.lastModified = lastModified;
        this.totalSize = totalSize;
        this.index = index;
        this.blockSize = blockSize > 0 ? blockSize : DEFAULT_BLOCK_SIZE;
        this.data = data;
    }

    public long getStartPosition() {
        return ((long) blockSize) * ((long) index);
    }

    public long calcBlockCount() {
        if (totalSize == 0) {
            return 1;
        }
        return (totalSize + blockSize - 1) / blockSize;
    }

    public boolean isFile() {
        return isFile;
    }

    public boolean isDirectory() {
        return !isFile;
    }

    public int getLength() {
        if (data == null) {
            return -1;
        }
        return data.position();
    }

    @Override
    public int compareTo(FileBlock other) {
        if (this.fileIndex != other.fileIndex) {
            return Integer.compare(this.fileIndex, other.fileIndex);
        }
        return Integer.compare(this.index, other.index);
    }
}
