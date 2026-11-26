package top.weixiansen574.hybridfilexfer.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;
import top.weixiansen574.hybridfilexfer.IOService;
import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngine;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngineCallback;
import top.weixiansen574.hybridfilexfer.core.AbstractHFXServer;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;

/**
 * Android Foreground Service acting as the sole lifecycle owner of {@link HFXServer},
 * {@link IIOService} (standard & Shizuku/Root), and {@link HfxEngineController}.
 *
 * <p>Architecture red line: strictly zero {@code Activity} / {@code View} / {@code Widget} /
 * {@code Toast} / {@code RecyclerView} / {@code PrintStream} references. Survives Activity
 * rotation, recreation, and backgrounding seamlessly, while exposing {@link IHfxEngine} AIDL
 * and an optional Android abstract {@link LocalServerSocket} NDJSON IPC bridge.</p>
 */
public class HfxEngineService extends Service {

    public static final String ACTION_STOP_ENGINE = "top.weixiansen574.hybridfilexfer.action.STOP_ENGINE";
    public static final String EXTRA_ENABLE_LOCAL_SOCKET_BRIDGE = "enable_local_socket_bridge";
    public static final String ABSTRACT_SOCKET_NAME = "hfx_engine_ipc";

    private static final String NOTIFICATION_CHANNEL_ID = "hfx_engine_service";
    private static final int NOTIFICATION_ID = 5740;
    private static final long IO_SERVICE_BIND_TIMEOUT_SECONDS = 10L;
    private static final long NOTIFICATION_THROTTLE_MS = 500L;

    private final RemoteCallbackList<IHfxEngineCallback> callbacks = new RemoteCallbackList<>();
    private final AtomicBoolean foregroundActive = new AtomicBoolean(false);
    private final AtomicLong lastNotificationUpdateMs = new AtomicLong(0L);
    private final Object ioBindLock = new Object();

    private HfxEngineController controller;
    private ServiceConnection activeIoConnection;
    private boolean activeIoIsShizuku;

    // Reserved Android Abstract Unix Socket NDJSON bridge extension point (for future Flutter/KMP shells)
    private volatile LocalServerSocket localBridgeServer;
    private volatile boolean localBridgeRunning = false;

    private final IHfxEngine.Stub binder = new IHfxEngine.Stub() {
        @Override
        public String executeCommand(String method, String paramsJson) {
            return controller.dispatchCommandJson(method, paramsJson);
        }

        @Override
        public String getSnapshotJson() {
            return controller.dispatchCommandJson(IpcMethods.ENGINE_GET_SNAPSHOT, null);
        }

        @Override
        public String refreshNicsJson() {
            return controller.dispatchCommandJson(IpcMethods.ENGINE_REFRESH_NICS, null);
        }

        @Override
        public String startServer(String paramsJson) {
            return controller.dispatchCommandJson(IpcMethods.SERVER_START, paramsJson);
        }

        @Override
        public String stopServer() {
            return controller.dispatchCommandJson(IpcMethods.SERVER_STOP, null);
        }

        @Override
        public String disconnect() {
            return controller.dispatchCommandJson(IpcMethods.SESSION_DISCONNECT, null);
        }

        @Override
        public String listFilesJson(String side, String path) {
            IpcPayloads.FsListParams p = new IpcPayloads.FsListParams();
            p.side = side;
            p.path = path;
            return controller.dispatchCommandJson(IpcMethods.FS_LIST, IpcJson.toJson(p));
        }

        @Override
        public String mkdir(String side, String parent, String child) {
            IpcPayloads.FsMkdirParams p = new IpcPayloads.FsMkdirParams();
            p.side = side;
            p.parent = parent;
            p.child = child;
            return controller.dispatchCommandJson(IpcMethods.FS_MKDIR, IpcJson.toJson(p));
        }

        @Override
        public String deleteFile(String side, String path) {
            IpcPayloads.FsDeleteParams p = new IpcPayloads.FsDeleteParams();
            p.side = side;
            p.path = path;
            return controller.dispatchCommandJson(IpcMethods.FS_DELETE, IpcJson.toJson(p));
        }

        @Override
        public String startTransfer(String paramsJson) {
            return controller.dispatchCommandJson(IpcMethods.TRANSFER_START, paramsJson);
        }

        @Override
        public String cancelTransfer() {
            return controller.dispatchCommandJson(IpcMethods.TRANSFER_CANCEL, null);
        }

        @Override
        public void registerCallback(IHfxEngineCallback callback) {
            if (callback != null) {
                callbacks.register(callback);
            }
        }

        @Override
        public void unregisterCallback(IHfxEngineCallback callback) {
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        String deviceName = Build.MODEL != null ? Build.MODEL : "Android";
        this.controller = new HfxEngineController(
                null,
                deviceName,
                new UsbNicAdvisor(),
                new AndroidServerBackend());
        this.controller.addEventListener(this::handleControllerEvent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_STOP_ENGINE.equals(intent.getAction())) {
                controller.stopServer();
                stopForegroundNotification();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent.getBooleanExtra(EXTRA_ENABLE_LOCAL_SOCKET_BRIDGE, false)) {
                startLocalSocketBridge();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopLocalSocketBridge();
        if (controller != null) {
            controller.shutdown();
        }
        unbindActiveIoService();
        stopForegroundNotification();
        callbacks.kill();
        super.onDestroy();
    }

    /**
     * Reserved extension point: exposes the same JSON-RPC 2.0 NDJSON protocol over an Android
     * abstract domain socket ({@code @hfx_engine_ipc}) for future Flutter / KMP / external shells.
     */
    public synchronized void startLocalSocketBridge() {
        if (localBridgeRunning) {
            return;
        }
        try {
            final LocalServerSocket server = new LocalServerSocket(ABSTRACT_SOCKET_NAME);
            this.localBridgeServer = server;
            this.localBridgeRunning = true;
            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (localBridgeRunning) {
                        try {
                            final LocalSocket client = server.accept();
                            Thread clientThread = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    serveLocalSocketClient(client);
                                }
                            }, "hfx-local-ipc-client");
                            clientThread.setDaemon(true);
                            clientThread.start();
                        } catch (IOException e) {
                            if (!localBridgeRunning) {
                                break;
                            }
                        }
                    }
                }
            }, "hfx-local-ipc-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException ignored) {
            localBridgeRunning = false;
        }
    }

    public synchronized void stopLocalSocketBridge() {
        localBridgeRunning = false;
        LocalServerSocket srv = this.localBridgeServer;
        this.localBridgeServer = null;
        if (srv != null) {
            try {
                srv.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serveLocalSocketClient(LocalSocket client) {
        final Object writeLock = new Object();
        final AtomicBoolean open = new AtomicBoolean(true);
        try (LocalSocket sock = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))) {
            HfxEngineController.EventListener bridgeListener = new HfxEngineController.EventListener() {
                @Override
                public void onEvent(String eventName, Object payload) {
                    if (!open.get()) return;
                    String line = IpcJson.encodeEvent(eventName, payload);
                    synchronized (writeLock) {
                        try {
                            writer.write(line);
                            writer.newLine();
                            writer.flush();
                        } catch (IOException e) {
                            open.set(false);
                        }
                    }
                }
            };
            controller.addEventListener(bridgeListener);
            try {
                String line;
                while (open.get() && localBridgeRunning && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    String responseLine = controller.dispatchRawJsonRpcLine(trimmed);
                    synchronized (writeLock) {
                        writer.write(responseLine);
                        writer.newLine();
                        writer.flush();
                    }
                }
            } finally {
                open.set(false);
                controller.removeEventListener(bridgeListener);
            }
        } catch (IOException ignored) {
        }
    }

    private void handleControllerEvent(String eventName, Object payload) {
        updateForegroundForEvent(eventName, payload);
        broadcastToAidlCallbacks(eventName, IpcJson.toJson(payload));
    }

    private void broadcastToAidlCallbacks(String eventName, String payloadJson) {
        synchronized (callbacks) {
            int count = callbacks.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        callbacks.getBroadcastItem(i).onEvent(eventName, payloadJson);
                    } catch (RemoteException ignored) {
                    }
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }
    }

    private void updateForegroundForEvent(String eventName, Object payload) {
        if (IpcEvents.SESSION_STATE_CHANGED.equals(eventName)
                && payload instanceof IpcPayloads.SessionStateChangedPayload) {
            IpcPayloads.SessionStateChangedPayload sc = (IpcPayloads.SessionStateChangedPayload) payload;
            if (sc.current == SessionState.IDLE || sc.current == SessionState.ERROR) {
                stopForegroundNotification();
            } else {
                String text = formatStateNotificationText(sc.current, sc.detail);
                startOrUpdateForegroundNotification(text, true);
            }
        } else if (IpcEvents.TRANSFER_PROGRESS.equals(eventName)
                && payload instanceof IpcPayloads.TransferProgressPayload) {
            IpcPayloads.TransferProgressPayload prog = (IpcPayloads.TransferProgressPayload) payload;
            String arrow = "upload".equals(prog.direction) ? "▲ " : "▼ ";
            String speedText = arrow + Utils.formatSpeed(prog.aggregateBps);
            if (prog.currentFile != null && !prog.currentFile.isEmpty()) {
                speedText = speedText + " · " + prog.currentFile;
            }
            startOrUpdateForegroundNotification(speedText, false);
        } else if (IpcEvents.TRANSFER_COMPLETED.equals(eventName)
                && payload instanceof IpcPayloads.TransferCompletedPayload) {
            IpcPayloads.TransferCompletedPayload done = (IpcPayloads.TransferCompletedPayload) payload;
            String arrow = "upload".equals(done.direction) ? "▲ " : "▼ ";
            String text = getString(R.string.engine_notif_connected) + " (" + arrow
                    + Utils.formatSpeed(done.avgBps) + ")";
            startOrUpdateForegroundNotification(text, true);
        }
    }

    private String formatStateNotificationText(SessionState state, String detail) {
        if (state == SessionState.DISCOVERING) {
            return getString(R.string.engine_notif_waiting);
        }
        if (state == SessionState.CONNECTING) {
            return getString(R.string.engine_notif_connecting);
        }
        if (state == SessionState.CONNECTED) {
            return getString(R.string.engine_notif_connected);
        }
        if (state == SessionState.TRANSFERRING) {
            return getString(R.string.engine_notif_transferring);
        }
        return detail != null ? detail : getString(R.string.app_name);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.engine_notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.engine_notif_channel_desc));
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startOrUpdateForegroundNotification(String contentText, boolean forceImmediate) {
        long now = System.currentTimeMillis();
        if (!forceImmediate && foregroundActive.get()
                && (now - lastNotificationUpdateMs.get()) < NOTIFICATION_THROTTLE_MS) {
            return;
        }
        lastNotificationUpdateMs.set(now);

        Notification notification = buildNotification(contentText);
        if (foregroundActive.compareAndSet(false, true)) {
            try {
                // Keep service alive even if all Activities unbind during rotation / backgrounding
                startService(new Intent(this, HfxEngineService.class));
            } catch (Exception ignored) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private void stopForegroundNotification() {
        if (foregroundActive.compareAndSet(true, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Intent stopIntent = new Intent(this, HfxEngineService.class);
        stopIntent.setAction(ACTION_STOP_ENGINE);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentPendingIntent = null;
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            contentPendingIntent = PendingIntent.getActivity(this, 0, launchIntent, piFlags);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.logo)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_close,
                        getString(R.string.ting_zhi_fu_wu),
                        stopPendingIntent).build());

        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent);
        }
        return builder.build();
    }

    private top.weixiansen574.hybridfilexfer.aidl.IIOService bindIoServiceSync(boolean useShizuku) throws Exception {
        synchronized (ioBindLock) {
            unbindActiveIoService();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<top.weixiansen574.hybridfilexfer.aidl.IIOService> ref = new AtomicReference<>(null);

            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    ref.set(top.weixiansen574.hybridfilexfer.aidl.IIOService.Stub.asInterface(service));
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            };

            if (useShizuku) {
                Shizuku.bindUserService(IOService.getUserServiceArgs(this), conn);
            } else {
                Intent intent = new Intent(this, IOService.class);
                boolean bound = bindService(intent, conn, Service.BIND_AUTO_CREATE);
                if (!bound) {
                    throw new IOException("Failed to bind local IOService");
                }
            }

            this.activeIoConnection = conn;
            this.activeIoIsShizuku = useShizuku;

            if (!latch.await(IO_SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || ref.get() == null) {
                unbindActiveIoService();
                throw new IOException("Timed out waiting for IIOService connection (shizuku=" + useShizuku + ")");
            }
            return ref.get();
        }
    }

    private void unbindActiveIoService() {
        synchronized (ioBindLock) {
            ServiceConnection conn = this.activeIoConnection;
            boolean isShizuku = this.activeIoIsShizuku;
            this.activeIoConnection = null;
            this.activeIoIsShizuku = false;
            if (conn != null) {
                try {
                    if (isShizuku) {
                        Shizuku.unbindUserService(IOService.getUserServiceArgs(this), conn, true);
                    } else {
                        unbindService(conn);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private final class AndroidServerBackend implements HfxEngineController.ServerSessionBackend {
        @Override
        public AbstractHFXServer acquireServer(int ioMode) throws Exception {
            boolean useShizuku = ioMode != 0;
            top.weixiansen574.hybridfilexfer.aidl.IIOService ioService = bindIoServiceSync(useShizuku);
            return new HFXServer(ioService);
        }

        @Override
        public void releaseServer(AbstractHFXServer server) {
            unbindActiveIoService();
        }

        @Override
        public List<RemoteFile> listLocalFiles(AbstractHFXServer server, String path) throws Exception {
            return ((HFXServer) server).listLocalFiles(path);
        }

        @Override
        public boolean createLocalDir(AbstractHFXServer server, String parent, String child) throws Exception {
            return ((HFXServer) server).createLocalDir(parent, child);
        }

        @Override
        public boolean deleteLocalFile(AbstractHFXServer server, String path) throws Exception {
            return ((HFXServer) server).deleteLocalFile(path);
        }

        @Override
        public RemoteFile resolveLocalFile(AbstractHFXServer server, String path) throws Exception {
            File f = new File(path);
            if (f.exists()) {
                return new RemoteFile(f);
            }
            // Fallback for Shizuku/Root paths not readable in app process
            File parent = f.getParentFile();
            if (parent != null) {
                List<RemoteFile> siblings = ((HFXServer) server).listLocalFiles(parent.getAbsolutePath());
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
    }
}
