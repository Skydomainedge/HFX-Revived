package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.OpenFileHandle;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

public class DroidWriteFileCall extends WriteFileCall {
    private final IIOService ioService;

    public DroidWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, IIOService ioService) {
        super(buffers, dequeCount);
        this.ioService = ioService;
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        String exception = ioService.createParentDirIfNotExists(path);
        if (exception != null) {
            throw new IOException(exception);
        }
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        String exception = ioService.tryMkdirs(path);
        if (exception != null) {
            throw new IOException(exception);
        }
    }

    @Override
    protected OpenFileHandle openWritableFile(String path, long totalSize, boolean preallocate) throws Exception {
        ParcelFileDescriptor pfd = preallocate
                ? ioService.createAndOpenWriteableFile(path, totalSize)
                : ioService.openExistingWriteableFile(path);
        if (pfd == null) {
            throw new IOException("Failed to open writable file via IIOService: " + path);
        }
        FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
        FileChannel ch = fos.getChannel();
        return new DroidOpenFileHandle(pfd, fos, ch);
    }

    @Override
    protected boolean setFileLastModified(String path, long time) throws Exception {
        return ioService.setFileLastModified(path, time);
    }

    private static final class DroidOpenFileHandle implements OpenFileHandle {
        private final ParcelFileDescriptor pfd;
        private final FileOutputStream fos;
        private final FileChannel channel;

        DroidOpenFileHandle(ParcelFileDescriptor pfd, FileOutputStream fos, FileChannel channel) {
            this.pfd = pfd;
            this.fos = fos;
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
                try {
                    fos.close();
                } finally {
                    pfd.close();
                }
            }
        }
    }
}
