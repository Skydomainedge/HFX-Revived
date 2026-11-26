package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Abstraction for an open writable file handle managed inside {@link WriteFileCall}'s LRU pool.
 */
public interface OpenFileHandle extends Closeable {
    FileChannel getChannel();

    @Override
    void close() throws IOException;
}
