package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = ProtocolConstants.MAGIC_CONTROL;
    public static final int VERSION_CODE = ProtocolConstants.VERSION_CODE;

    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    protected int negotiatedBlockSize = ProtocolConstants.DEFAULT_BLOCK_SIZE;
    protected int negotiatedCapabilities = 0;
    protected long speedMonitorIntervalMs = SpeedMonitorThread.DEFAULT_INTERVAL_MS;

    public int getNegotiatedBlockSize() {
        return negotiatedBlockSize;
    }

    public int getNegotiatedCapabilities() {
        return negotiatedCapabilities;
    }

    public void setSpeedMonitorIntervalMs(long intervalMs) {
        this.speedMonitorIntervalMs = Math.max(100L, intervalMs);
    }

    public List<String> getDistinctRailNames() {
        if (connections == null) {
            return new ArrayList<>();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (TransferConnection connection : connections) {
            distinct.add(connection.iName);
        }
        return new ArrayList<>(distinct);
    }

    protected boolean sendFiles(List<RemoteFile> fileList, Directory localDir, Directory remoteDir,
                                TransferFileCallback callback) throws IOException {
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask, "FileRead");
        readThread.start();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, speedMonitorIntervalMs);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        long startTime = System.currentTimeMillis();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task, "UL_" + connection.getStreamLabel());
            thread.start();
        }

        boolean complete;
        try {
            complete = ctChannel.readBoolean();
        } catch (IOException e) {
            speedMonitorThread.cancel();
            readFileCall.shutdownByConnectionBreak();
            callback.onIncomplete();
            return false;
        }
        speedMonitorThread.cancel();

        if (!complete) {
            String errMsg = ctChannel.readUTF();
            callback.onWriteFileError(errMsg);
            readFileCall.shutdownByWriteError();
            return true;
        }

        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException | InterruptedException e) {
                callback.onIncomplete();
                return false;
            }
        }

        long totalUploadTraffic = 0;
        for (TransferConnection connection : connections) {
            totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
        }

        try {
            readFileTask.get();
            ctChannel.writeBoolean(true);
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeBoolean(false);
            ctChannel.writeUTF(ex);
            callback.onReadFileError(ex);
            return true;
        }

        long elapsed = Math.max(1L, System.currentTimeMillis() - startTime);
        callback.onComplete(true, totalUploadTraffic, elapsed);
        return true;
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, speedMonitorIntervalMs);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(
                    new ReceiveFileCall(i, connection, writeFileCall, callback, negotiatedBlockSize));
            transferTasks.add(task);
            Thread thread = new Thread(task, "DL_" + connection.getStreamLabel());
            thread.start();
        }

        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread writeThread = new Thread(writeFileTask, "FileWrite");
        writeThread.start();

        try {
            writeFileTask.get();
        } catch (InterruptedException | ExecutionException e) {
            speedMonitorThread.cancel();
            Throwable cause = e.getCause();
            ctChannel.writeBoolean(false);
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeUTF(ex);
            callback.onWriteFileError(ex);
            return true;
        }

        for (FutureTask<Void> task : transferTasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                ctChannel.writeBoolean(true);
                callback.onIncomplete();
                return false;
            }
        }

        speedMonitorThread.cancel();
        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            long elapsed = Math.max(1L, System.currentTimeMillis() - startTime);
            callback.onComplete(false, totalDownloadTraffic, elapsed);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
        return true;
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                       List<RemoteFile> files,
                                                       Directory localDir,
                                                       Directory remoteDir,
                                                       int operateThreadCount);
}
