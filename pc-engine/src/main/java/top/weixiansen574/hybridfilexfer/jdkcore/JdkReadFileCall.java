package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class JdkReadFileCall extends ReadFileCall {
    private RandomAccessFile randomAccessFile;
    private FileChannel channel;

    public JdkReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                           Directory localDir, Directory remoteDir, int operateThreadCount) {
        super(buffers, files, localDir, remoteDir, operateThreadCount);
    }

    public JdkReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                           Directory localDir, Directory remoteDir, int operateThreadCount, int blockSize) {
        super(buffers, files, localDir, remoteDir, operateThreadCount, blockSize);
    }

    @Override
    protected boolean fileExists(String path) {
        return new File(path).exists();
    }

    @Override
    protected List<RemoteFile> listFiles(String path) {
        return Utils.listRemoteFiles(path);
    }

    @Override
    protected FileChannel openFile(String path) throws Exception {
        randomAccessFile = new RandomAccessFile(path, "r");
        channel = randomAccessFile.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        IOException first = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                first = e;
            } finally {
                channel = null;
            }
        }
        if (randomAccessFile != null) {
            try {
                randomAccessFile.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            } finally {
                randomAccessFile = null;
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
