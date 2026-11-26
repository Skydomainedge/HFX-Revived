package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = ProtocolConstants.MAGIC_CONTROL;
    public static final int VERSION_CODE = ProtocolConstants.VERSION_CODE;

    protected final Object controlLock = new Object();
    private final Object loopStateLock = new Object();
    private volatile Selector controlSelector;
    private volatile Thread controlLoopThread;
    private volatile boolean controlLoopRunning = false;
    private boolean localTurnRequested = false;
    private boolean loopPausedForLocal = false;

    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    protected int negotiatedBlockSize = ProtocolConstants.DEFAULT_BLOCK_SIZE;
    protected int negotiatedCapabilities = 0;
    protected long speedMonitorIntervalMs = SpeedMonitorThread.DEFAULT_INTERVAL_MS;

    private volatile ReadFileCall activeReadFileCall;
    private volatile Thread activeReadThread;
    private volatile WriteFileCall activeWriteFileCall;

    protected interface ControlCommandHandler {
        boolean handleCommand(short commandId) throws Exception;

        void onLoopExit(Throwable error);
    }

    protected interface ControlAction<T> {
        T run() throws IOException;
    }

    public int getNegotiatedBlockSize() {
        return negotiatedBlockSize;
    }

    public int getNegotiatedCapabilities() {
        return negotiatedCapabilities;
    }

    public void setSpeedMonitorIntervalMs(long intervalMs) {
        this.speedMonitorIntervalMs = Math.max(100L, intervalMs);
    }

    public boolean isSessionActive() {
        DataByteChannel ch = this.ctChannel;
        List<TransferConnection> conns = this.connections;
        return ch != null && ch.isOpen() && conns != null && !conns.isEmpty();
    }

    public long getCurrentTransferredBytes(boolean upload) {
        List<TransferConnection> conns = this.connections;
        if (conns == null || conns.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (TransferConnection conn : conns) {
            TrafficInfo info = conn.getTotalTraffic();
            sum += upload ? info.uploadTraffic : info.downloadTraffic;
        }
        return sum;
    }

    public List<String> getDistinctRailNames() {
        List<TransferConnection> conns = this.connections;
        if (conns == null) {
            return new ArrayList<>();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (TransferConnection connection : conns) {
            distinct.add(connection.iName);
        }
        return new ArrayList<>(distinct);
    }

    public List<IpcDtos.RailStatusInfo> getRailStatusInfos(String state, String udcSpeed) {
        List<TransferConnection> conns = this.connections;
        if (conns == null || conns.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Integer, IpcDtos.RailStatusInfo> byRail = new LinkedHashMap<>();
        for (TransferConnection conn : conns) {
            IpcDtos.RailStatusInfo info = byRail.get(conn.railId);
            if (info == null) {
                info = new IpcDtos.RailStatusInfo();
                info.railId = conn.railId;
                info.name = conn.iName;
                info.type = conn.railType != null ? conn.railType.name() : "OTHER";
                info.streamCount = 0;
                info.state = state;
                info.udcSpeed = udcSpeed;
                byRail.put(conn.railId, info);
            }
            info.streamCount++;
        }
        return new ArrayList<>(byRail.values());
    }

    /**
     * Infers target filesystem type from path syntax when peer filesystem was not explicitly exchanged.
     */
    public static int inferFileSystemFromPath(String path) {
        if (path != null && !path.isEmpty()) {
            if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
                return Directory.FILE_SYSTEM_WINDOWS;
            }
            if (path.indexOf('\\') >= 0) {
                return Directory.FILE_SYSTEM_WINDOWS;
            }
        }
        return Directory.FILE_SYSTEM_UNIX;
    }

    /**
     * Requests cancellation of any in-flight send or receive transfer.
     *
     * @return {@code true} if an active transfer was signalled to cancel.
     */
    public boolean cancelActiveTransfer() {
        boolean signalled = false;
        ReadFileCall rfc = this.activeReadFileCall;
        if (rfc != null) {
            rfc.cancelByUser();
            Thread rt = this.activeReadThread;
            if (rt != null) {
                rt.interrupt();
            }
            signalled = true;
        }
        WriteFileCall wfc = this.activeWriteFileCall;
        if (wfc != null) {
            wfc.cancelWithError("Transfer cancelled");
            signalled = true;
        }
        return signalled;
    }

    /**
     * Runs a Selector-backed bidirectional control loop that monitors {@code ctChannel} for
     * incoming peer commands while safely yielding blocking ownership to local initiators
     * via {@link #executeWithControlLock(ControlAction)}.
     */
    protected void runPausableControlLoop(ControlCommandHandler handler) {
        runPausableControlLoop(handler, null);
    }

    protected void runPausableControlLoop(ControlCommandHandler handler, Runnable onLoopStarted) {
        DataByteChannel ch = this.ctChannel;
        if (ch == null || !(ch.getOrigin() instanceof SocketChannel)) {
            if (onLoopStarted != null) {
                onLoopStarted.run();
            }
            handler.onLoopExit(new IOException("Control channel does not support Selector-based control loop"));
            return;
        }
        SocketChannel sc = (SocketChannel) ch.getOrigin();
        Selector selector = null;
        Throwable exitError = null;
        boolean startedNotified = false;
        try {
            selector = Selector.open();
            synchronized (loopStateLock) {
                this.controlSelector = selector;
                this.controlLoopThread = Thread.currentThread();
                this.controlLoopRunning = true;
                this.localTurnRequested = false;
                this.loopPausedForLocal = false;
            }
            if (onLoopStarted != null) {
                startedNotified = true;
                onLoopStarted.run();
            }

            while (controlLoopRunning && ch.isOpen() && sc.isOpen()) {
                synchronized (loopStateLock) {
                    while (localTurnRequested && controlLoopRunning) {
                        loopPausedForLocal = true;
                        loopStateLock.notifyAll();
                        try {
                            loopStateLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            controlLoopRunning = false;
                            break;
                        }
                    }
                    loopPausedForLocal = false;
                }
                if (!controlLoopRunning || !ch.isOpen() || !sc.isOpen()) {
                    break;
                }

                SelectionKey key = null;
                boolean readable = false;
                try {
                    sc.configureBlocking(false);
                    key = sc.register(selector, SelectionKey.OP_READ);
                    selector.select(250L);
                    readable = key.isValid() && key.isReadable();
                } finally {
                    if (key != null) {
                        key.cancel();
                    }
                    try {
                        selector.selectNow();
                    } catch (IOException ignored) {
                    }
                    if (sc.isOpen()) {
                        try {
                            sc.configureBlocking(true);
                        } catch (IOException ignored) {
                        }
                    }
                }

                if (!controlLoopRunning || !ch.isOpen() || !sc.isOpen()) {
                    break;
                }

                if (readable) {
                    synchronized (controlLock) {
                        if (!controlLoopRunning || !ch.isOpen()) {
                            break;
                        }
                        short cmdId = ch.readShort();
                        boolean keepGoing = handler.handleCommand(cmdId);
                        if (!keepGoing) {
                            controlLoopRunning = false;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (controlLoopRunning) {
                exitError = t;
            }
        } finally {
            if (onLoopStarted != null && !startedNotified) {
                onLoopStarted.run();
            }
            synchronized (loopStateLock) {
                controlLoopRunning = false;
                controlSelector = null;
                controlLoopThread = null;
                loopPausedForLocal = true;
                loopStateLock.notifyAll();
            }
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException ignored) {
                }
            }
            handler.onLoopExit(exitError);
        }
    }

    protected void stopControlLoop() {
        Selector sel;
        synchronized (loopStateLock) {
            controlLoopRunning = false;
            localTurnRequested = false;
            sel = controlSelector;
            loopStateLock.notifyAll();
        }
        if (sel != null) {
            sel.wakeup();
        }
    }

    protected boolean isControlLoopRunning() {
        return controlLoopRunning;
    }

    protected <T> T executeWithControlLock(ControlAction<T> action) throws IOException {
        boolean pausedLoop = false;
        synchronized (loopStateLock) {
            if (controlLoopRunning && Thread.currentThread() != controlLoopThread) {
                while (localTurnRequested && controlLoopRunning) {
                    try {
                        loopStateLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for control channel turn", e);
                    }
                }
                if (controlLoopRunning) {
                    localTurnRequested = true;
                    pausedLoop = true;
                    Selector sel = controlSelector;
                    if (sel != null) {
                        sel.wakeup();
                    }
                    while (!loopPausedForLocal && controlLoopRunning) {
                        try {
                            loopStateLock.wait(100L);
                            Selector s2 = controlSelector;
                            if (s2 != null) {
                                s2.wakeup();
                            }
                        } catch (InterruptedException e) {
                            localTurnRequested = false;
                            loopStateLock.notifyAll();
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted pausing control loop", e);
                        }
                    }
                }
            }
        }
        try {
            synchronized (controlLock) {
                if (ctChannel == null || !ctChannel.isOpen()) {
                    throw new IOException("Control channel is closed");
                }
                return action.run();
            }
        } finally {
            if (pausedLoop) {
                synchronized (loopStateLock) {
                    localTurnRequested = false;
                    loopStateLock.notifyAll();
                }
            }
        }
    }

    protected boolean sendFiles(List<RemoteFile> fileList, Directory localDir, Directory remoteDir,
                                TransferFileCallback callback) throws IOException {
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask, "FileRead");
        this.activeReadFileCall = readFileCall;
        this.activeReadThread = readThread;
        readThread.start();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, speedMonitorIntervalMs);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        try {
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
                for (FutureTask<Void> transferTask : transferTasks) {
                    try {
                        transferTask.get(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                }
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
                if (readFileCall.isStopped()) {
                    ctChannel.writeBoolean(false);
                    ctChannel.writeUTF("Transfer cancelled");
                    callback.onReadFileError("Transfer cancelled");
                    return true;
                }
                ctChannel.writeBoolean(true);
            } catch (ExecutionException | InterruptedException e) {
                Throwable cause = e.getCause();
                String ex = readFileCall.isStopped()
                        ? "Transfer cancelled"
                        : (cause != null ? cause.toString() : e.toString());
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF(ex);
                callback.onReadFileError(ex);
                return true;
            }

            long elapsed = Math.max(1L, System.currentTimeMillis() - startTime);
            callback.onComplete(true, totalUploadTraffic, elapsed);
            return true;
        } finally {
            speedMonitorThread.cancel();
            this.activeReadFileCall = null;
            this.activeReadThread = null;
        }
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        this.activeWriteFileCall = writeFileCall;
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, speedMonitorIntervalMs);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        try {
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
                String ex = cause != null ? cause.getMessage() : e.toString();
                if (ex == null || ex.isEmpty()) {
                    ex = cause != null ? cause.toString() : e.toString();
                }
                ctChannel.writeUTF(ex);
                for (FutureTask<Void> task : transferTasks) {
                    try {
                        task.get(3, TimeUnit.SECONDS);
                    } catch (TimeoutException | InterruptedException | ExecutionException ignored) {
                    }
                }
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
        } finally {
            speedMonitorThread.cancel();
            this.activeWriteFileCall = null;
        }
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                       List<RemoteFile> files,
                                                       Directory localDir,
                                                       Directory remoteDir,
                                                       int operateThreadCount);
}
