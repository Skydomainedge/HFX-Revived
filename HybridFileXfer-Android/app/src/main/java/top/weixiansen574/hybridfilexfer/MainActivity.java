package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import rikka.shizuku.Shizuku;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngine;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngineCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.engine.HfxEngineService;
import top.weixiansen574.hybridfilexfer.listadapter.NetCardsAdapter;

/**
 * Thin display-layer shell binding to headless {@link HfxEngineService} via {@link IHfxEngine}.
 *
 * <p>Holds zero {@code HFXServer} or {@code IIOService} references; destroying or rotating this
 * Activity only unbinds the UI observer and never tears down an active background connection or transfer.</p>
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener, ServiceConnection {
    public static final int REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = 1;
    public static final int REQUEST_CODE_NOTIFICATION_PERMISSION = 3;

    private NetCardsAdapter netCardsAdapter;
    private Spinner spinnerMode;
    private Button startServerBtn;
    private Button toTransfer;
    private Context context;
    private Config config;

    private IHfxEngine engine;
    private boolean engineBound = false;
    private SessionState currentState = SessionState.IDLE;
    private IpcPayloads.RailConflictResolvedPayload lastConflict;

    private final IHfxEngineCallback engineCallback = new IHfxEngineCallback.Stub() {
        @Override
        public void onEvent(final String eventName, final String payloadJson) {
            runOnUiThread(() -> handleEngineEvent(eventName, payloadJson));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        config = Config.getInstance(context);
        startServerBtn = findViewById(R.id.start_server);
        startServerBtn.setOnClickListener(this);
        toTransfer = findViewById(R.id.to_transfer);
        toTransfer.setOnClickListener(this);
        spinnerMode = findViewById(R.id.spinner_mode);
        findViewById(R.id.refresh).setOnClickListener(this);

        RecyclerView recyclerView = findViewById(R.id.rec_view_net_cards);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        netCardsAdapter = new NetCardsAdapter(this);
        recyclerView.setAdapter(netCardsAdapter);

        spinnerMode.setSelection(config.getMode());
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                config.setMode(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        requestNotificationPermissionIfNeeded();
        bindEngineService();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_NOTIFICATION_PERMISSION);
            }
        }
    }

    private void bindEngineService() {
        Intent intent = new Intent(this, HfxEngineService.class);
        engineBound = bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        engine = IHfxEngine.Stub.asInterface(service);
        try {
            engine.registerCallback(engineCallback);
        } catch (RemoteException ignored) {
        }
        syncFromEngineSnapshot();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        engine = null;
    }

    private void syncFromEngineSnapshot() {
        IHfxEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        new Thread(() -> {
            try {
                IpcDtos.EngineSnapshot snap = EngineIpcHelper.unwrap(
                        eng.getSnapshotJson(), IpcDtos.EngineSnapshot.class);
                if (snap == null) {
                    return;
                }
                if (snap.state == SessionState.IDLE && (snap.nics == null || snap.nics.isEmpty())) {
                    IpcPayloads.NicsRefreshResult refreshed = EngineIpcHelper.unwrap(
                            eng.refreshNicsJson(), IpcPayloads.NicsRefreshResult.class);
                    if (refreshed != null) {
                        snap.nics = refreshed.nics;
                        snap.udcSpeed = refreshed.udcSpeed;
                        lastConflict = refreshed.conflictResolved;
                    }
                }
                final IpcDtos.EngineSnapshot finalSnap = snap;
                runOnUiThread(() -> {
                    netCardsAdapter.updateFromNics(
                            finalSnap.nics, finalSnap.udcSpeed, lastConflict, finalSnap.rails);
                    applySessionStateUi(finalSnap.state);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void handleEngineEvent(String eventName, String payloadJson) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (IpcEvents.SESSION_STATE_CHANGED.equals(eventName)) {
            IpcPayloads.SessionStateChangedPayload sc = IpcJson.fromJson(
                    payloadJson, IpcPayloads.SessionStateChangedPayload.class);
            if (sc != null && sc.current != null) {
                applySessionStateUi(sc.current);
                if (sc.prev == SessionState.IDLE && sc.current == SessionState.DISCOVERING) {
                    Toast.makeText(context, R.string.fu_wu_yi_qi_dong, Toast.LENGTH_SHORT).show();
                } else if (sc.current == SessionState.IDLE && sc.prev != SessionState.IDLE) {
                    Toast.makeText(context, R.string.fu_wu_yi_ting_zhi, Toast.LENGTH_SHORT).show();
                }
            }
        } else if (IpcEvents.RAIL_STATUS.equals(eventName)) {
            IpcPayloads.RailStatusPayload rp = IpcJson.fromJson(payloadJson, IpcPayloads.RailStatusPayload.class);
            if (rp != null) {
                netCardsAdapter.applyRailStatuses(rp.rails);
            }
        } else if (IpcEvents.RAIL_CONFLICT_RESOLVED.equals(eventName)) {
            lastConflict = IpcJson.fromJson(payloadJson, IpcPayloads.RailConflictResolvedPayload.class);
        } else if (IpcEvents.DIAGNOSTIC_WARNING.equals(eventName)) {
            IpcPayloads.DiagnosticWarningPayload warn = IpcJson.fromJson(
                    payloadJson, IpcPayloads.DiagnosticWarningPayload.class);
            if (warn != null && warn.code != null) {
                if ("BIND_FAILED".equals(warn.code)) {
                    Toast.makeText(context, getString(R.string.service_start_failed, config.getServerPort()),
                            Toast.LENGTH_SHORT).show();
                } else if ("REMOTE_OOM".equals(warn.code)) {
                    Toast.makeText(context, R.string.dui_fang_nei_cun_bu_zu, Toast.LENGTH_LONG).show();
                } else if ("LOCAL_OOM".equals(warn.code)) {
                    Toast.makeText(context, warn.message, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void applySessionStateUi(SessionState state) {
        this.currentState = state;
        boolean idle = (state == SessionState.IDLE || state == SessionState.ERROR);
        boolean connected = (state == SessionState.CONNECTED || state == SessionState.TRANSFERRING);

        netCardsAdapter.setEnableModify(idle);
        startServerBtn.setEnabled(true);
        toTransfer.setEnabled(connected);

        if (idle) {
            startServerBtn.setText(R.string.qi_dong_fu_wu_qi_bing_deng_dai_lian_jie);
        } else if (connected) {
            startServerBtn.setText(R.string.duan_kai_lian_jie);
        } else {
            startServerBtn.setText(R.string.ting_zhi_fu_wu);
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.start_server) {
            if (currentState == SessionState.IDLE || currentState == SessionState.ERROR) {
                startServer();
            } else {
                stopOrDisconnectServer();
            }
        } else if (id == R.id.to_transfer) {
            startActivity(new Intent(context, TransferActivity.class));
        } else if (id == R.id.refresh) {
            if (currentState == SessionState.IDLE || currentState == SessionState.ERROR) {
                refreshNics();
            } else {
                Toast.makeText(context, R.string.qing_xian_ting_zhi_fu_wu_duan, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refreshNics() {
        IHfxEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        new Thread(() -> {
            try {
                IpcPayloads.NicsRefreshResult res = EngineIpcHelper.unwrap(
                        eng.refreshNicsJson(), IpcPayloads.NicsRefreshResult.class);
                if (res != null) {
                    lastConflict = res.conflictResolved;
                    runOnUiThread(() -> netCardsAdapter.updateFromNics(
                            res.nics, res.udcSpeed, res.conflictResolved, null));
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    public void startServer() {
        if (engine == null) {
            Toast.makeText(context, R.string.fu_wu_wei_yun_xing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkPermissionOrRequest()) {
            Toast.makeText(context, R.string.xu_yao_wen_jian_du_xie_quan_xian, Toast.LENGTH_LONG).show();
            return;
        }
        List<IpcDtos.RailSelection> selectedRails = netCardsAdapter.getSelectedRailSelections();
        if (selectedRails == null) {
            return;
        }
        if (selectedRails.isEmpty()) {
            Toast.makeText(context, R.string.mei_you_wang_ka_xuan_ze, Toast.LENGTH_SHORT).show();
            return;
        }
        long availableMemoryMB = getAvailableMemoryMB();
        if (config.getLocalBufferCount() > availableMemoryMB) {
            Toast.makeText(context, R.string.shou_ji_ke_yong_nei_cun_bu_zu, Toast.LENGTH_SHORT).show();
            return;
        }

        int ioMode = spinnerMode.getSelectedItemPosition();
        if (ioMode != 0 && checkShizukuOrReq(ioMode)) {
            return;
        }

        final IpcPayloads.ServerStartParams params = new IpcPayloads.ServerStartParams();
        params.port = config.getServerPort();
        params.ioMode = ioMode;
        params.selectedRails = selectedRails;
        params.localBufferCount = config.getLocalBufferCount();
        params.remoteBufferCount = config.getRemoteBufferCount();

        startServerBtn.setEnabled(false);
        netCardsAdapter.setEnableModify(false);
        startServerBtn.setText(R.string.ting_zhi_fu_wu);

        final IHfxEngine eng = this.engine;
        new Thread(() -> {
            try {
                EngineIpcHelper.unwrap(eng.startServer(IpcJson.toJson(params)), IpcPayloads.OkResult.class);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
                    applySessionStateUi(SessionState.IDLE);
                });
            }
        }).start();
    }

    private void stopOrDisconnectServer() {
        final IHfxEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        startServerBtn.setEnabled(false);
        new Thread(() -> {
            try {
                eng.stopServer();
            } catch (Exception ignored) {
            }
            runOnUiThread(() -> applySessionStateUi(SessionState.IDLE));
        }).start();
    }

    private boolean checkPermissionOrRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                return false;
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                return true;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return false;
            } else {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
                return true;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.github) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.update) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer/releases"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.open_wifi_p2p_settings) {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings", "com.android.settings.Settings$WifiP2pSettingsActivity");
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (itemId == R.id.buffer_count) {
            View view = View.inflate(context, R.layout.dialog_buffer_settings, null);
            EditText editLocalCount = view.findViewById(R.id.edit_local_buffer_count);
            EditText editRemoteCount = view.findViewById(R.id.edit_remote_buffer_count);
            editLocalCount.setText(String.valueOf(config.getLocalBufferCount()));
            editRemoteCount.setText(String.valueOf(config.getRemoteBufferCount()));
            editLocalCount.setHint(getString(R.string.dang_qian_zui_gao_ke_she_zhi, getAvailableMemoryMB()));

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.she_zhi_huan_chong_qu_kuai_shu)
                    .setView(view)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(v -> {
                    String localCountStr = editLocalCount.getText().toString();
                    String remoteCountStr = editRemoteCount.getText().toString();
                    editLocalCount.setError(null);
                    editRemoteCount.setError(null);

                    if (localCountStr.isEmpty()) {
                        editLocalCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }
                    if (remoteCountStr.isEmpty()) {
                        editRemoteCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }

                    int localCount = Integer.parseInt(localCountStr);
                    int remoteCount = Integer.parseInt(remoteCountStr);
                    if (localCount <= 16) {
                        editLocalCount.setError(getString(R.string.zui_xiao_ke_she_zhi_16));
                        return;
                    }
                    if (remoteCount <= 16) {
                        editRemoteCount.setError(getString(R.string.zui_xiao_ke_she_zhi_16));
                        return;
                    }

                    config.setLocalBufferCount(localCount);
                    config.setRemoteBufferCount(remoteCount);
                    dialog.dismiss();
                });
            });
            dialog.show();
        } else if (itemId == R.id.client) {
            startClient();
        }
        return super.onOptionsItemSelected(item);
    }

    private void startClient() {
        if (checkPermissionOrRequest()) {
            return;
        }
        View view = View.inflate(context, R.layout.dialog_connect_server, null);
        EditText editIp = view.findViewById(R.id.edit_server_controller_ip);
        editIp.setText(config.getConnectServerControllerIp());
        EditText editMainDir = view.findViewById(R.id.edit_home_dir);
        editMainDir.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        Spinner spinner = view.findViewById(R.id.spinner_mode);
        spinner.setSelection(config.getClientIOMode());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setTitle(R.string.connect_to_server)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = editIp.getText().toString();
            if (TextUtils.isEmpty(ip)) {
                editIp.setError(getString(R.string.please_enter_the_server_ip));
                return;
            }
            if (TextUtils.isEmpty(editMainDir.getText())) {
                editMainDir.setError(getString(R.string.please_enter_the_home_dir));
                return;
            }
            int mode = spinner.getSelectedItemPosition();
            if (mode != 0 && checkShizukuOrReq(mode)) {
                return;
            }
            config.setConnectServerControllerIp(ip);
            config.setClientIOMode(mode);
            Intent intent = new Intent(context, ClientActivity.class);
            intent.putExtra("io_mode", mode);
            intent.putExtra("controller_ip", ip);
            intent.putExtra("home_dir", editMainDir.getText().toString());
            startActivity(intent);
            dialog.dismiss();
        });
    }

    public boolean checkShizukuOrReq(int mode) {
        if (!Shizuku.pingBinder()) {
            showInstallShizukuDialog(context);
            return true;
        } else if (Shizuku.checkSelfPermission() != 0) {
            Toast.makeText(context, R.string.wei_shou_quan_sui_ti_shi, Toast.LENGTH_SHORT).show();
            Shizuku.requestPermission(1);
            return true;
        }
        if (mode == 1 && Shizuku.getUid() != 0) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                    .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_root)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return true;
        } else if (mode == 2 && Shizuku.getUid() != 2000) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                    .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_adb)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return true;
        }
        return false;
    }

    public void showInstallShizukuDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.shizuku_not_running)
                .setMessage(R.string.shizuku_not_running_message)
                .setPositiveButton(R.string.open_shizuku, (dialog, which) -> {
                    try {
                        Intent intent = context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.please_install_shizuku, Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                        context.startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private long getAvailableMemoryMB() {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long totalMemoryMB = memoryInfo.totalMem / (1024 * 1024);
        long availableMemoryMB = memoryInfo.availMem / (1024 * 1024);
        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected void onDestroy() {
        // Only unbind UI observer; background HfxEngineService keeps connection & transfer alive!
        if (engine != null) {
            try {
                engine.unregisterCallback(engineCallback);
            } catch (RemoteException ignored) {
            }
            engine = null;
        }
        if (engineBound) {
            try {
                unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
            engineBound = false;
        }
        super.onDestroy();
    }
}
