package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.pc.engine.transport.SubnetBindResolver;

public class JdkHFXClient extends HFXClient {
    @FunctionalInterface
    public interface AdbRailPreparer {
        AdbRailPrepareResult prepare(List<ServerNetInterface> rails);
    }

    private SubnetBindResolver subnetBindResolver;
    private boolean autoSubnetBindEnabled = true;
    private AdbRailPreparer adbRailPreparer;

    public JdkHFXClient(String serverControllerAddress, int serverPort, String homeDir) {
        this(serverControllerAddress, serverPort, homeDir, new SubnetBindResolver());
    }

    public JdkHFXClient(String serverControllerAddress, int serverPort, String homeDir,
                        SubnetBindResolver subnetBindResolver) {
        super(serverControllerAddress, serverPort, homeDir);
        this.subnetBindResolver = subnetBindResolver;
    }

    public void setSubnetBindResolver(SubnetBindResolver subnetBindResolver) {
        this.subnetBindResolver = subnetBindResolver;
    }

    public void setAutoSubnetBindEnabled(boolean enabled) {
        this.autoSubnetBindEnabled = enabled;
    }

    public boolean isAutoSubnetBindEnabled() {
        return autoSubnetBindEnabled;
    }

    public void setAdbRailPreparer(AdbRailPreparer adbRailPreparer) {
        this.adbRailPreparer = adbRailPreparer;
    }

    @Override
    protected InetAddress resolveControlBindAddress(InetAddress controlAddress) {
        if (!autoSubnetBindEnabled || subnetBindResolver == null
                || controlAddress == null || controlAddress.isLoopbackAddress()) {
            return null;
        }
        return subnetBindResolver.resolveLocalBindAddress(controlAddress);
    }

    @Override
    protected AdbRailPrepareResult prepareAdbRailBeforeDataConnect(List<ServerNetInterface> rails) {
        AdbRailPreparer preparer = this.adbRailPreparer;
        if (preparer != null) {
            return preparer.prepare(rails);
        }
        return super.prepareAdbRailBeforeDataConnect(rails);
    }

    @Override
    protected InetAddress resolveLocalBindAddress(ServerNetInterface rail) {
        if (rail == null) {
            return null;
        }
        if (rail.clientBindAddress != null) {
            return rail.clientBindAddress;
        }
        if (!autoSubnetBindEnabled || subnetBindResolver == null) {
            return null;
        }
        if (rail.railType == RailType.ADB || rail.address == null || rail.address.isLoopbackAddress()) {
            return null;
        }
        return subnetBindResolver.resolveLocalBindAddress(rail.address);
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
