package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.AbstractHFXServer;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.ServerHandshakeCallback;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;

/**
 * Android HFX v4 server implementation owned exclusively by {@code HfxEngineService}.
 *
 * <p>The legacy global static singleton has been permanently removed in M3 to decouple
 * transport lifecycle from Android Activities.</p>
 */
public class HFXServer extends AbstractHFXServer {
    protected final IIOService ioService;

    public HFXServer(IIOService ioService) {
        this.ioService = ioService;
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList,
                            int localBufferCount, int remoteBufferCount,
                            final StartServerCallback callback) throws IOException {
        super.startServer(port, interfaceList, localBufferCount, remoteBufferCount,
                new ServerHandshakeCallback() {
                    @Override
                    public void onBindFailed(int boundPort) {
                        callback.onBindFailed(boundPort);
                    }

                    @Override
                    public void onServerStarted() {
                        callback.onStatedServer();
                    }

                    @Override
                    public void onRailAccepted(String name, int railId, int streamIndex, int streamCount) {
                        if (streamIndex == 0) {
                            callback.onAccepted(name);
                        }
                    }

                    @Override
                    public void onAcceptFailed(String name) {
                        callback.onAcceptFailed(name);
                    }

                    @Override
                    public void onRemoteOOM() {
                        callback.onPcOOM();
                    }

                    @Override
                    public void onLocalOOM(int created, int localCount) {
                        callback.onMeOOM(created, localCount);
                    }

                    @Override
                    public void onConnectSuccess() {
                        callback.onConnectSuccess();
                    }
                });
    }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    @Override
    protected List<RemoteFile> listLocalFilesForPeer(String path) throws Exception {
        return listLocalFiles(path);
    }

    @Override
    protected boolean createLocalDirForPeer(String parent, String child) throws Exception {
        return createLocalDir(parent, child);
    }

    @Override
    protected boolean deleteLocalFileForPeer(String path) throws Exception {
        return deleteLocalFile(path);
    }

    @Override
    protected RemoteFile resolveLocalFileForPeer(String path) throws Exception {
        File f = new File(path);
        if (f.exists()) {
            return new RemoteFile(f);
        }
        File parent = f.getParentFile();
        if (parent != null) {
            List<RemoteFile> siblings = listLocalFiles(parent.getAbsolutePath());
            if (siblings != null) {
                for (RemoteFile rf : siblings) {
                    if (path.equals(rf.getPath()) || f.getName().equals(rf.getName())) {
                        return rf;
                    }
                }
            }
        }
        return new RemoteFile(f);
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            remoteFiles.addAll(ioService.getAndRemoveFileListSlice(chunkId));
        }
        return remoteFiles;
    }

    @Override
    protected ByteBuffer createBuffer(int size) {
        return NativeMemory.allocateLargeBuffer(size);
    }

    @Override
    protected void freeBuffers() {
        ByteBuffer buffer;
        while ((buffer = buffers.poll()) != null) {
            NativeMemory.freeBuffer(buffer);
        }
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers, dequeCount, ioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                              List<RemoteFile> files,
                                              Directory localDir,
                                              Directory remoteDir,
                                              int operateThreadCount) {
        return new DroidReadFileCall(ioService, buffers, files, localDir, remoteDir,
                operateThreadCount, negotiatedBlockSize);
    }
}
