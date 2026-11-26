package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class JdkHFXClient extends HFXClient {

    public JdkHFXClient(String serverControllerAddress, int serverPort, String homeDir) {
        super(serverControllerAddress, serverPort, homeDir);
    }

    @Override
    public ByteBuffer createBuffer(int size) {
        try {
            return ByteBuffer.allocateDirect(size);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    public long getAvailableMemoryMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    @Override
    protected boolean deleteLocalFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File subFile : files) {
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }
        return file.delete();
    }

    @Override
    protected boolean mkdir(String parent, String child) {
        return new File(parent, child).mkdirs();
    }

    @Override
    protected List<RemoteFile> listFiles(String path) {
        return Utils.listRemoteFiles(path);
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new JdkWriteFileCall(buffers, dequeCount);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                              List<RemoteFile> files,
                                              Directory localDir,
                                              Directory remoteDir,
                                              int operateThreadCount) {
        return new JdkReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, negotiatedBlockSize);
    }
}
