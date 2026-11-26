package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.OpenFileHandle;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

public class JdkWriteFileCall extends WriteFileCall {

    public JdkWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        super(buffers, dequeCount);
    }

    public JdkWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, int maxOpenFiles) {
        super(buffers, dequeCount, maxOpenFiles);
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        File file = new File(path);
        File parentFile = file.getParentFile();
        if (parentFile == null) {
            return;
        }
        mkdirOrThrow(parentFile);
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        mkdirOrThrow(new File(path));
    }

    @Override
    protected OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(path, "rw");
        try {
            if (preallocate) {
                raf.setLength(totalSize);
            }
            FileChannel ch = raf.getChannel();
            return new JdkOpenFileHandle(raf, ch);
        } catch (Exception e) {
            try {
                raf.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Override
    protected boolean setFileLastModified(String path, long time) {
        return new File(path).setLastModified(time);
    }

    private void mkdirOrThrow(File file) throws IOException {
        if (file.exists()) {
            if (file.isFile()) {
                if (file.delete()) {
                    if (!file.mkdirs()) {
                        throw new IOException("cannot mkdirs " + file);
                    }
                } else {
                    throw new IOException("cannot delete file " + file);
                }
            }
        } else {
            if (!file.mkdirs()) {
                throw new IOException("cannot mkdirs " + file);
            }
        }
    }

    private static final class JdkOpenFileHandle implements OpenFileHandle {
        private final RandomAccessFile raf;
        private final FileChannel channel;

        JdkOpenFileHandle(RandomAccessFile raf, FileChannel channel) {
            this.raf = raf;
            this.channel = channel;
        }

        @Override
        public FileChannel getChannel() {
            return channel;
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                raf.close();
            }
        }
    }
}
