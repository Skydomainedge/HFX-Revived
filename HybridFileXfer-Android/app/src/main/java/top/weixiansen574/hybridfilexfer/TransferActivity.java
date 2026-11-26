package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import top.weixiansen574.hybridfilexfer.aidl.IHfxEngine;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngineCallback;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.ipc.SessionState;
import top.weixiansen574.hybridfilexfer.engine.HfxEngineService;
import top.weixiansen574.hybridfilexfer.listadapter.BookmarkAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.FileSelectAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.LocalFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.RemoteFileSelectAdapter;

/**
 * Thin dual-pane file management shell driven entirely by {@link IHfxEngine} AIDL and
 * {@link IHfxEngineCallback}. Survives configuration changes (screen rotation / recreation)
 * without interrupting the underlying foreground {@link HfxEngineService} session or transfer.
 */
public class TransferActivity extends AppCompatActivity implements ServiceConnection {
    private static final String STATE_LEFT_DIR = "state_left_dir";
    private static final String STATE_RIGHT_DIR = "state_right_dir";
    private static final String STATE_LEFT_FOCUS = "state_left_focus";

    private boolean isLeftFocus = true;
    private Activity context;
    private IHfxEngine engine;
    private boolean engineBound = false;

    private FileSelectAdapter leftRVAdapter, rightRVAdapter;
    private RecyclerView leftList;
    private RecyclerView rightList;
    private ConstraintLayout shadowGroupLeft;
    private ConstraintLayout shadowGroupRight;
    private Toolbar leftSelectToolbar;
    private Toolbar rightSelectToolbar;

    private String savedLeftDir;
    private String savedRightDir;
    private List<String> connectedRailNames = new ArrayList<>();
    private TransferDialog activeTransferDialog;
    private boolean activeTransferIsUpload = true;

    private final IHfxEngineCallback engineCallback = new IHfxEngineCallback.Stub() {
        @Override
        public void onEvent(final String eventName, final String payloadJson) {
            runOnUiThread(() -> handleEngineEvent(eventName, payloadJson));
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        setContentView(R.layout.activity_transfer);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        leftList = findViewById(R.id.rv_left_files);
        rightList = findViewById(R.id.rv_right_files);
        shadowGroupLeft = findViewById(R.id.shadow_group_left);
        shadowGroupRight = findViewById(R.id.shadow_group_right);
        leftSelectToolbar = findViewById(R.id.toolbar_select_left);
        rightSelectToolbar = findViewById(R.id.toolbar_select_right);

        if (savedInstanceState != null) {
            savedLeftDir = savedInstanceState.getString(STATE_LEFT_DIR);
            savedRightDir = savedInstanceState.getString(STATE_RIGHT_DIR);
            isLeftFocus = savedInstanceState.getBoolean(STATE_LEFT_FOCUS, true);
        }
        shadowGroupLeft.setVisibility(isLeftFocus ? View.INVISIBLE : View.VISIBLE);
        shadowGroupRight.setVisibility(isLeftFocus ? View.VISIBLE : View.INVISIBLE);

        Intent intent = new Intent(this, HfxEngineService.class);
        engineBound = bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (leftRVAdapter != null) {
            outState.putString(STATE_LEFT_DIR, leftRVAdapter.getCurrentDir());
        }
        if (rightRVAdapter != null) {
            outState.putString(STATE_RIGHT_DIR, rightRVAdapter.getCurrentDir());
        }
        outState.putBoolean(STATE_LEFT_FOCUS, isLeftFocus);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        engine = IHfxEngine.Stub.asInterface(service);
        try {
            engine.registerCallback(engineCallback);
        } catch (RemoteException ignored) {
        }
        initializeFromSnapshot();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        engine = null;
    }

    private void initializeFromSnapshot() {
        final IHfxEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        new Thread(() -> {
            try {
                IpcDtos.EngineSnapshot snap = EngineIpcHelper.unwrap(
                        eng.getSnapshotJson(), IpcDtos.EngineSnapshot.class);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (snap == null || (snap.state != SessionState.CONNECTED && snap.state != SessionState.TRANSFERRING)) {
                        Toast.makeText(context, R.string.fu_wu_wei_yun_xing, Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    connectedRailNames = extractRailNames(snap.rails);
                    setupPanels(eng, snap);
                    if (snap.state == SessionState.TRANSFERRING) {
                        ensureTransferDialogVisible(activeTransferIsUpload);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(context, R.string.fu_wu_wei_yun_xing, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPanels(IHfxEngine eng, IpcDtos.EngineSnapshot snap) {
        if (leftRVAdapter != null && rightRVAdapter != null) {
            return;
        }
        // Left pane: Android local storage
        LinearLayoutManager leftLm = new LinearLayoutManager(context);
        leftLm.setOrientation(RecyclerView.VERTICAL);
        leftList.setLayoutManager(leftLm);
        OnTouchListener leftTouch = new OnTouchListener(this, true);
        leftRVAdapter = new LocalFileSelectAdapter(context, findViewById(R.id.loading_left),
                leftList, leftLm, leftSelectToolbar, leftTouch, eng, savedLeftDir);
        leftList.setAdapter(leftRVAdapter);
        findViewById(R.id.left).setOnTouchListener(leftTouch);
        leftList.setLayoutAnimation(createListAnimation());
        leftList.setOnTouchListener(leftTouch);

        // Right pane: PC remote filesystem (M3: always defaults to drive root "/" unless restored from rotation)
        LinearLayoutManager rightLm = new LinearLayoutManager(context);
        rightLm.setOrientation(RecyclerView.VERTICAL);
        rightList.setLayoutManager(rightLm);
        OnTouchListener rightTouch = new OnTouchListener(this, false);
        rightRVAdapter = new RemoteFileSelectAdapter(context, findViewById(R.id.loading_right),
                rightList, rightLm, rightSelectToolbar, rightTouch, eng, snap.remoteFileSystem, savedRightDir);
        rightList.setAdapter(rightRVAdapter);
        findViewById(R.id.right).setOnTouchListener(rightTouch);
        rightList.setLayoutAnimation(createListAnimation());
        rightList.setOnTouchListener(rightTouch);

        leftRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle(R.string.que_ren_chuan_shu)
                .setMessage(getString(R.string.chuan_shu_dao_dian_nao_mu_lu, selectedItems.size(), rightRVAdapter.getCurrentDir()))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    leftRVAdapter.cancelSelect();
                    startEngineTransfer("upload", selectedItems, dir, rightRVAdapter.getCurrentDirectory());
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

        rightRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle(R.string.que_ren_chuan_shu)
                .setMessage(getString(R.string.chuan_shu_dao_shou_ji_mu_lu, selectedItems.size(), leftRVAdapter.getCurrentDir()))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    rightRVAdapter.cancelSelect();
                    startEngineTransfer("download", selectedItems, dir, leftRVAdapter.getCurrentDirectory());
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    private LayoutAnimationController createListAnimation() {
        Animation animation = AnimationUtils.loadAnimation(context, R.anim.list_anim);
        LayoutAnimationController controller = new LayoutAnimationController(animation);
        controller.setOrder(LayoutAnimationController.ORDER_NORMAL);
        controller.setDelay(0.2f);
        return controller;
    }

    private void startEngineTransfer(String direction, List<RemoteFile> files, Directory srcDir, Directory dstDir) {
        final IHfxEngine eng = this.engine;
        if (eng == null) {
            return;
        }
        boolean isUpload = "upload".equals(direction);
        activeTransferIsUpload = isUpload;
        ensureTransferDialogVisible(isUpload);

        final IpcPayloads.TransferStartParams params = new IpcPayloads.TransferStartParams();
        params.direction = direction;
        params.srcDir = srcDir.path;
        params.dstDir = dstDir.path;
        params.remoteFileSystem = isUpload ? dstDir.fileSystem : srcDir.fileSystem;
        for (RemoteFile rf : files) {
            params.files.add(rf.getPath());
        }

        new Thread(() -> {
            try {
                EngineIpcHelper.unwrap(eng.startTransfer(IpcJson.toJson(params)), IpcPayloads.OkResult.class);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissTransferDialogQuietly();
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void handleEngineEvent(String eventName, String payloadJson) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (IpcEvents.TRANSFER_PROGRESS.equals(eventName)) {
            IpcPayloads.TransferProgressPayload prog = IpcJson.fromJson(
                    payloadJson, IpcPayloads.TransferProgressPayload.class);
            if (prog != null) {
                boolean isUpload = "upload".equals(prog.direction);
                activeTransferIsUpload = isUpload;
                ensureTransferDialogVisible(isUpload);
                if (activeTransferDialog != null) {
                    activeTransferDialog.showProgress(prog);
                }
            }
        } else if (IpcEvents.TRANSFER_COMPLETED.equals(eventName)) {
            IpcPayloads.TransferCompletedPayload done = IpcJson.fromJson(
                    payloadJson, IpcPayloads.TransferCompletedPayload.class);
            if (done != null) {
                boolean isUpload = "upload".equals(done.direction);
                ensureTransferDialogVisible(isUpload);
                if (activeTransferDialog != null) {
                    activeTransferDialog.complete(isUpload, done.totalBytes, done.elapsedMs);
                }
                if (isUpload && rightRVAdapter != null) {
                    rightRVAdapter.refresh();
                } else if (!isUpload && leftRVAdapter != null) {
                    leftRVAdapter.refresh();
                }
            }
        } else if (IpcEvents.TRANSFER_ERROR.equals(eventName)) {
            IpcPayloads.TransferCompletedPayload err = IpcJson.fromJson(
                    payloadJson, IpcPayloads.TransferCompletedPayload.class);
            if (activeTransferDialog != null) {
                activeTransferDialog.setTitle("传输失败");
                activeTransferDialog.setCloseBtnEnable(true);
            }
            String message = (err != null && err.error != null)
                    ? err.error
                    : getString(R.string.chuan_shu_shi_fa_sheng_yi_chang);
            new AlertDialog.Builder(context)
                    .setTitle("传输异常")
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } else if (IpcEvents.SESSION_STATE_CHANGED.equals(eventName)) {
            IpcPayloads.SessionStateChangedPayload sc = IpcJson.fromJson(
                    payloadJson, IpcPayloads.SessionStateChangedPayload.class);
            if (sc != null && (sc.current == SessionState.IDLE || sc.current == SessionState.ERROR)) {
                dismissTransferDialogQuietly();
                Toast.makeText(context, R.string.kong_tong_dao_de_lian_jie_yi_duan_kai__, Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (IpcEvents.RAIL_STATUS.equals(eventName)) {
            IpcPayloads.RailStatusPayload rp = IpcJson.fromJson(payloadJson, IpcPayloads.RailStatusPayload.class);
            if (rp != null && !rp.rails.isEmpty()) {
                connectedRailNames = extractRailNames(rp.rails);
            }
        }
    }

    private void ensureTransferDialogVisible(boolean isUpload) {
        if (activeTransferDialog != null && activeTransferDialog.getDialog() != null
                && activeTransferDialog.getDialog().isShowing()
                && activeTransferDialog.isUpload == isUpload) {
            return;
        }
        dismissTransferDialogQuietly();
        List<String> rails = connectedRailNames.isEmpty()
                ? java.util.Collections.singletonList("HFX")
                : connectedRailNames;
        activeTransferDialog = new TransferDialog(context, isUpload, rails);
        activeTransferDialog.show();
    }

    private void dismissTransferDialogQuietly() {
        if (activeTransferDialog != null) {
            try {
                activeTransferDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeTransferDialog = null;
        }
    }

    private static List<String> extractRailNames(List<IpcDtos.RailStatusInfo> rails) {
        List<String> names = new ArrayList<>();
        if (rails != null) {
            for (IpcDtos.RailStatusInfo r : rails) {
                if (r.name != null && !names.contains(r.name)) {
                    names.add(r.name);
                }
            }
        }
        return names;
    }

    public void switchTo(boolean isLeft) {
        if (leftRVAdapter == null || rightRVAdapter == null) {
            return;
        }
        if (isLeft) {
            if (!this.isLeftFocus) {
                shadowGroupLeft.setVisibility(View.INVISIBLE);
                shadowGroupRight.setVisibility(View.VISIBLE);
            }
            if (leftRVAdapter.isSelectMode()) {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.VISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            } else {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            }
        } else {
            if (isLeftFocus) {
                shadowGroupLeft.setVisibility(View.VISIBLE);
                shadowGroupRight.setVisibility(View.INVISIBLE);
            }
            if (rightRVAdapter.isSelectMode()) {
                rightRVAdapter.fileSelectToolbar.setVisibility(View.VISIBLE);
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            } else {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            }
        }
        this.isLeftFocus = isLeft;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (leftRVAdapter == null || rightRVAdapter == null) {
            return super.onOptionsItemSelected(item);
        }
        if (id == R.id.refresh) {
            if (isLeftFocus) {
                leftRVAdapter.refresh();
            } else {
                rightRVAdapter.refresh();
            }
            return true;
        } else if (id == R.id.bookmark_list) {
            View dialogView = View.inflate(context, R.layout.dialog_bookmarks, null);
            RecyclerView recyclerView = dialogView.findViewById(R.id.bookmark_list);
            LinearLayoutManager layoutManager = new LinearLayoutManager(context);
            layoutManager.setOrientation(RecyclerView.VERTICAL);
            recyclerView.setLayoutManager(layoutManager);
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setPositiveButton(R.string.close, null);
            if (isLeftFocus) {
                builder.setTitle(R.string.ben_di_wen_jian_jia_shu_qian);
                recyclerView.setAdapter(new BookmarkAdapter(context, builder.show(), leftRVAdapter, false));
            } else {
                builder.setTitle(R.string.dian_nao_wen_jian_jia_shu_qian);
                recyclerView.setAdapter(new BookmarkAdapter(context, builder.show(), rightRVAdapter, true));
            }
            return true;
        } else if (id == R.id.add_bookmark) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (isLeftFocus) {
                builder.setTitle(R.string.que_ren_tian_jia_dao_ben_di_wen_jian_jia_shu_qian_ma)
                        .setMessage(leftRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> addBookmark(false, leftRVAdapter.getCurrentDir()));
            } else {
                builder.setTitle(R.string.que_ren_tian_jia_dao_dian_nao_wen_jian_jia_shu_qian_ma)
                        .setMessage(rightRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> addBookmark(true, rightRVAdapter.getCurrentDir()));
            }
            builder.setNegativeButton(R.string.cancel, null);
            builder.show();
            return true;
        } else if (id == R.id.mkdir) {
            View dialogView = View.inflate(context, R.layout.edit_text, null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            String title = isLeftFocus
                    ? context.getString(R.string.create_folder_mobile)
                    : context.getString(R.string.create_folder_computer);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    editText.setError(getString(R.string.qing_shu_ru_wen_jian_ming));
                    return;
                }
                if (Utils.containsIllegalCharacters(input)) {
                    editText.setError(context.getString(R.string.invalid_filename_characters));
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus) {
                    leftRVAdapter.mkdir(leftRVAdapter.getCurrentDir(), input);
                } else {
                    rightRVAdapter.mkdir(rightRVAdapter.getCurrentDir(), input);
                }
            });
        } else if (id == R.id.jump) {
            View dialogView = View.inflate(context, R.layout.edit_text, null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            editText.setText(isLeftFocus ? leftRVAdapter.getCurrentDir() : rightRVAdapter.getCurrentDir());
            String title = isLeftFocus
                    ? context.getString(R.string.jump_path_mobile)
                    : context.getString(R.string.jump_path_computer);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    editText.setError(getString(R.string.qing_shu_ru_lu_jing));
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus) {
                    leftRVAdapter.jump(input);
                } else {
                    rightRVAdapter.jump(input);
                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void addBookmark(boolean isRemote, String path) {
        boolean exists;
        ConfigDB configDB = ConfigDB.getInstance(context);
        if (isRemote) {
            exists = configDB.checkRemoteBookmarkExists(path);
            if (!exists) {
                configDB.addRemoteBookmark(path);
            }
        } else {
            exists = configDB.checkLocalBookmarkExists(path);
            if (!exists) {
                configDB.addLocalBookmark(path);
            }
        }
        if (!exists) {
            String message = isRemote
                    ? getString(R.string.bookmark_added_remote)
                    : getString(R.string.bookmark_added_local);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.shu_qian_yi_cun_zai, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    private static class OnTouchListener implements View.OnTouchListener {
        private final TransferActivity activity;
        private final boolean isLeft;

        public OnTouchListener(TransferActivity activity, boolean isLeft) {
            this.activity = activity;
            this.isLeft = isLeft;
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                activity.switchTo(isLeft);
            }
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && leftRVAdapter != null && rightRVAdapter != null) {
            if (isLeftFocus) {
                if (leftRVAdapter.isSelectMode()) {
                    leftRVAdapter.cancelSelect();
                } else {
                    leftRVAdapter.back();
                }
            } else {
                if (rightRVAdapter.isSelectMode()) {
                    rightRVAdapter.cancelSelect();
                } else {
                    rightRVAdapter.back();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        dismissTransferDialogQuietly();
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
