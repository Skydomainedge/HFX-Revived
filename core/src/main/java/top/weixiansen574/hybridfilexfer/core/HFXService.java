package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

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
    public static final long DEFAULT_TRANSFER_ABORT_TIMEOUT_MS = 2500L;
    public static final long DEFAULT_SHUTDOWN_LOCK_TIMEOUT_MS = 400L;

    protected final Object controlLock = new Object();
    private final ReentrantLock controlAccessLock = new ReentrantLock();
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
    protected long transferAbortTimeoutMs = DEFAULT_TRANSFER_ABORT_TIMEOUT_MS;

    private volatile ReadFileCall activeReadFileCall;
    private volatile Thread activeReadThread;
    private volatile WriteFileCall activeWriteFileCall;
    private volatile boolean transferInProgress = false;
    private volatile List<Thread> activeTransferThreads = Collections.emptyList();
    private volatile Selector activeTransferControlSelector;

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

    public void setTransferAbortTimeoutMs(long timeoutMs) {
        this.transferAbortTimeoutMs = Math.max(200L, timeoutMs);
    }

    protected boolean isTransferInProgress() {
        return transferInProgress;
    }

    protected void closeTransferConnectionsQuietly() {
        List<TransferConnection> conns = this.connections;
        if (conns != null) {
            for (TransferConnection conn : conns) {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    protected void awaitTransferThreadsQuietly(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(50L, timeoutMs);
        List<Thread> workers = this.activeTransferThreads;
        Thread readThread = this.activeReadThread;
        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
        }
        if (workers != null) {
            for (Thread t : workers) {
                if (t != null && t.isAlive()) {
                    t.interrupt();
                }
            }
        }
        if (readThread != null && readThread != Thread.currentThread()) {
            long rem = deadline - System.currentTimeMillis();
            if (rem > 0L) {
                try {
                    readThread.join(rem);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (workers != null) {
            for (Thread t : workers) {
                if (t == null || t == Thread.currentThread()) {
                    continue;
                }
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0L) {
                    break;
                }
                try {
                    t.join(rem);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
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
        Selector transferSel = this.activeTransferControlSelector;
        if (transferSel != null) {
            transferSel.wakeup();
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
                    controlAccessLock.lock();
                    try {
                        if (!controlLoopRunning || !ch.isOpen()) {
                            break;
                        }
                        short cmdId = ch.readShort();
                        boolean keepGoing = handler.handleCommand(cmdId);
                        if (!keepGoing) {
                            controlLoopRunning = false;
                            break;
                        }
                    } finally {
                        controlAccessLock.unlock();
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
            controlAccessLock.lock();
            try {
                if (ctChannel == null || !ctChannel.isOpen()) {
                    throw new IOException("Control channel is closed");
                }
                return action.run();
            } finally {
                controlAccessLock.unlock();
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

    /**
     * Bounded, non-deadlocking variant of {@link #executeWithControlLock(ControlAction)} intended
     * for best-effort {@code SHUTDOWN} signalling during teardown. Never blocks indefinitely if
     * a transfer or peer RPC currently holds the control channel lock.
     */
    protected boolean tryExecuteWithControlLock(ControlAction<Void> action, long timeoutMs) {
        if (transferInProgress) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(50L, timeoutMs);
        boolean pausedLoop = false;
        synchronized (loopStateLock) {
            if (controlLoopRunning && Thread.currentThread() != controlLoopThread) {
                while (localTurnRequested && controlLoopRunning) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L || transferInProgress) {
                        return false;
                    }
                    try {
                        loopStateLock.wait(Math.min(50L, remaining));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
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
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0L || transferInProgress) {
                            localTurnRequested = false;
                            loopStateLock.notifyAll();
                            return false;
                        }
                        try {
                            loopStateLock.wait(Math.min(50L, remaining));
                            Selector s2 = controlSelector;
                            if (s2 != null) {
                                s2.wakeup();
                            }
                        } catch (InterruptedException e) {
                            localTurnRequested = false;
                            loopStateLock.notifyAll();
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
            }
        }
        boolean locked = false;
        try {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            locked = controlAccessLock.tryLock(remaining, TimeUnit.MILLISECONDS);
            if (!locked) {
                return false;
            }
            if (ctChannel == null || !ctChannel.isOpen()) {
                return false;
            }
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (locked) {
                controlAccessLock.unlock();
            }
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
        final ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        final Thread readThread = new Thread(readFileTask, "FileRead");
        readThread.setDaemon(true);
        this.transferInProgress = true;
        this.activeReadFileCall = readFileCall;
        this.activeReadThread = readThread;
        readThread.start();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, speedMonitorIntervalMs);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.setDaemon(true);
        speedMonitorThread.start();

        final AtomicBoolean dataChannelBroken = new AtomicBoolean(false);
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        List<Thread> transferThreads = new ArrayList<>(connections.size());

        try {
            long startTime = System.currentTimeMillis();
            Runnable onBreak = new Runnable() {
                @Override
                public void run() {
                    if (dataChannelBroken.compareAndSet(false, true)) {
                        readFileCall.shutdownByConnectionBreak();
                        readThread.interrupt();
                        Selector sel = activeTransferControlSelector;
                        if (sel != null) {
                            sel.wakeup();
                        }
                    }
                }
            };
            for (TransferConnection connection : connections) {
                FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback, onBreak));
                transferTasks.add(task);
                Thread thread = new Thread(task, "UL_" + connection.getStreamLabel());
                thread.setDaemon(true);
                transferThreads.add(thread);
                thread.start();
            }
            this.activeTransferThreads = transferThreads;

            boolean complete;
            try {
                complete = readControlBooleanWithAbortAwareness(dataChannelBroken, transferAbortTimeoutMs);
            } catch (IOException e) {
                speedMonitorThread.cancel();
                readFileCall.shutdownByConnectionBreak();
                readThread.interrupt();
                closeTransferConnectionsQuietly();
                abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                callback.onIncomplete();
                return false;
            }
            speedMonitorThread.cancel();

            if (!complete) {
                String errMsg;
                try {
                    errMsg = ctChannel.readUTF();
                } catch (IOException e) {
                    readFileCall.shutdownByConnectionBreak();
                    readThread.interrupt();
                    closeTransferConnectionsQuietly();
                    abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                    callback.onIncomplete();
                    return false;
                }
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

            if (dataChannelBroken.get()) {
                readFileCall.shutdownByConnectionBreak();
                readThread.interrupt();
                closeTransferConnectionsQuietly();
                abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                callback.onIncomplete();
                return false;
            }

            boolean taskFailed = false;
            long joinDeadline = System.currentTimeMillis() + Math.max(200L, transferAbortTimeoutMs);
            for (FutureTask<Void> transferTask : transferTasks) {
                try {
                    if (dataChannelBroken.get()) {
                        long rem = Math.max(1L, joinDeadline - System.currentTimeMillis());
                        transferTask.get(rem, TimeUnit.MILLISECONDS);
                    } else {
                        transferTask.get();
                    }
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    taskFailed = true;
                    break;
                }
            }
            if (taskFailed || dataChannelBroken.get()) {
                readFileCall.shutdownByConnectionBreak();
                readThread.interrupt();
                closeTransferConnectionsQuietly();
                abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                callback.onIncomplete();
                return false;
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
            } catch (IOException e) {
                closeTransferConnectionsQuietly();
                callback.onIncomplete();
                return false;
            }

            long elapsed = Math.max(1L, System.currentTimeMillis() - startTime);
            callback.onComplete(true, totalUploadTraffic, elapsed);
            return true;
        } finally {
            speedMonitorThread.cancel();
            this.transferInProgress = false;
            this.activeReadFileCall = null;
            this.activeReadThread = null;
            this.activeTransferThreads = Collections.emptyList();
        }
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        this.transferInProgress = true;
        this.activeWriteFileCall = writeFileCall;
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, speedMonitorIntervalMs);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.setDaemon(true);
        speedMonitorThread.start();

        final AtomicBoolean dataChannelBroken = new AtomicBoolean(false);
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        List<Thread> transferThreads = new ArrayList<>(connections.size());

        try {
            Runnable onBreak = new Runnable() {
                @Override
                public void run() {
                    dataChannelBroken.set(true);
                }
            };
            for (int i = 0; i < connections.size(); i++) {
                TransferConnection connection = connections.get(i);
                FutureTask<Void> task = new FutureTask<>(
                        new ReceiveFileCall(i, connection, writeFileCall, callback, negotiatedBlockSize, onBreak));
                transferTasks.add(task);
                Thread thread = new Thread(task, "DL_" + connection.getStreamLabel());
                thread.setDaemon(true);
                transferThreads.add(thread);
                thread.start();
            }

            FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
            Thread writeThread = new Thread(writeFileTask, "FileWrite");
            writeThread.setDaemon(true);
            List<Thread> allWorkers = new ArrayList<>(transferThreads.size() + 1);
            allWorkers.addAll(transferThreads);
            allWorkers.add(writeThread);
            this.activeTransferThreads = allWorkers;
            writeThread.start();

            try {
                writeFileTask.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                Throwable cause = e.getCause();
                String ex = cause != null ? cause.getMessage() : e.toString();
                if (ex == null || ex.isEmpty()) {
                    ex = cause != null ? cause.toString() : e.toString();
                }
                try {
                    ctChannel.writeBoolean(false);
                    ctChannel.writeUTF(ex);
                } catch (IOException ioEx) {
                    closeTransferConnectionsQuietly();
                    abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                    callback.onIncomplete();
                    return false;
                }
                for (FutureTask<Void> task : transferTasks) {
                    try {
                        task.get(3, TimeUnit.SECONDS);
                    } catch (TimeoutException | InterruptedException | ExecutionException ignored) {
                    }
                }
                callback.onWriteFileError(ex);
                return true;
            }

            if (dataChannelBroken.get()) {
                speedMonitorThread.cancel();
                closeTransferConnectionsQuietly();
                abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                try {
                    if (ctChannel != null && ctChannel.isOpen()) {
                        ctChannel.writeBoolean(true);
                    }
                } catch (IOException ignored) {
                }
                callback.onIncomplete();
                return false;
            }

            boolean boundedWait = writeFileCall.isCanceled();
            long abortDeadline = System.currentTimeMillis() + Math.max(200L, transferAbortTimeoutMs);
            for (FutureTask<Void> task : transferTasks) {
                try {
                    if (boundedWait || dataChannelBroken.get()) {
                        long rem = abortDeadline - System.currentTimeMillis();
                        if (rem <= 0L) {
                            throw new TimeoutException("Timed out waiting for sibling data rail after transfer abort");
                        }
                        task.get(rem, TimeUnit.MILLISECONDS);
                    } else {
                        task.get();
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    speedMonitorThread.cancel();
                    closeTransferConnectionsQuietly();
                    abortAndJoinTransferTasks(transferTasks, transferThreads, 1500L);
                    try {
                        if (ctChannel != null && ctChannel.isOpen()) {
                            ctChannel.writeBoolean(true);
                        }
                    } catch (IOException ignored) {
                    }
                    callback.onIncomplete();
                    return false;
                }
            }

            speedMonitorThread.cancel();
            try {
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
            } catch (IOException e) {
                closeTransferConnectionsQuietly();
                callback.onIncomplete();
                return false;
            }
        } finally {
            speedMonitorThread.cancel();
            this.transferInProgress = false;
            this.activeWriteFileCall = null;
            this.activeTransferThreads = Collections.emptyList();
        }
    }

    private boolean readControlBooleanWithAbortAwareness(AtomicBoolean dataChannelBroken, long postAbortTimeoutMs)
            throws IOException {
        DataByteChannel ch = this.ctChannel;
        if (ch == null || !ch.isOpen()) {
            throw new IOException("Control channel is closed");
        }
        if (!(ch.getOrigin() instanceof SocketChannel)) {
            return ch.readBoolean();
        }
        SocketChannel sc = (SocketChannel) ch.getOrigin();
        Selector selector = Selector.open();
        SelectionKey key = null;
        long abortDeadlineMs = 0L;
        this.activeTransferControlSelector = selector;
        try {
            sc.configureBlocking(false);
            key = sc.register(selector, SelectionKey.OP_READ);
            while (ch.isOpen() && sc.isOpen()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Interrupted waiting for control channel response");
                }
                long waitMs = 150L;
                if (dataChannelBroken.get()) {
                    long now = System.currentTimeMillis();
                    if (abortDeadlineMs == 0L) {
                        abortDeadlineMs = now + Math.max(200L, postAbortTimeoutMs);
                    }
                    long remaining = abortDeadlineMs - now;
                    if (remaining <= 0L) {
                        throw new IOException("Control channel response timed out after data rail break");
                    }
                    waitMs = Math.min(waitMs, remaining);
                }
                selector.select(waitMs);
                if (key.isValid() && key.isReadable()) {
                    break;
                }
            }
        } finally {
            this.activeTransferControlSelector = null;
            if (key != null) {
                key.cancel();
            }
            try {
                selector.selectNow();
            } catch (IOException ignored) {
            }
            try {
                selector.close();
            } catch (IOException ignored) {
            }
            if (sc.isOpen()) {
                try {
                    sc.configureBlocking(true);
                } catch (IOException ignored) {
                }
            }
        }
        if (!ch.isOpen() || !sc.isOpen()) {
            throw new IOException("Control channel closed while waiting for transfer ack");
        }
        return ch.readBoolean();
    }

    private static void abortAndJoinTransferTasks(List<FutureTask<Void>> tasks,
                                                  List<Thread> threads,
                                                  long timeoutMs) {
        if (threads != null) {
            for (Thread t : threads) {
                if (t != null && t.isAlive()) {
                    t.interrupt();
                }
            }
        }
        long deadline = System.currentTimeMillis() + Math.max(100L, timeoutMs);
        if (tasks != null) {
            for (FutureTask<Void> task : tasks) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    task.get(remaining, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                }
            }
        }
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
                                                       List<RemoteFile> files,
                                                       Directory localDir,
                                                       Directory remoteDir,
                                                       int operateThreadCount);
}
