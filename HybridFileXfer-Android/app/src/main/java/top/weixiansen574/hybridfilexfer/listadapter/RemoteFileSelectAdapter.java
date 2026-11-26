package top.weixiansen574.hybridfilexfer.listadapter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.RemoteException;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

import top.weixiansen574.hybridfilexfer.EngineIpcHelper;
import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngine;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;

public class RemoteFileSelectAdapter extends FileSelectAdapter {

    /**
     * M3 default PC root path: opens directly at drive-root {@code "/"} (showing {@code C:\},
     * {@code D:\}, {@code E:\}, ... on Windows) instead of an empty {@code -d} subdirectory.
     */
    public static final String DEFAULT_REMOTE_ROOT_DIR = "/";

    public RemoteFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView,
                                   LinearLayoutManager linearLayoutManager, Toolbar fileSelectToolbar,
                                   View.OnTouchListener onTouchListener, IHfxEngine engine,
                                   int remoteFileSystem, String savedDir) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener,
                engine, remoteFileSystem, savedDir);
    }

    @Override
    public List<RemoteFile> listFiles(String path) throws Exception {
        IpcPayloads.FsListResult res = EngineIpcHelper.unwrap(
                engine.listFilesJson("remote", path), IpcPayloads.FsListResult.class);
        return EngineIpcHelper.toRemoteFiles(res);
    }

    @Override
    protected void handleListException(Throwable th) {
        onConnectionLost();
    }

    @Override
    protected String getDefaultDir() {
        return DEFAULT_REMOTE_ROOT_DIR;
    }

    @Override
    public boolean deleteFile(String file) throws RemoteException, IOException {
        IpcPayloads.OkResult res = EngineIpcHelper.unwrap(
                engine.deleteFile("remote", file), IpcPayloads.OkResult.class);
        return res != null && res.ok;
    }

    @Override
    public void mkdir(String parent, String child) {
        new Thread(() -> {
            try {
                IpcPayloads.OkResult res = EngineIpcHelper.unwrap(
                        engine.mkdir("remote", parent, child), IpcPayloads.OkResult.class);
                boolean success = res != null && res.ok;
                context.runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(context, R.string.chuang_jian_cheng_gong, Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(context, R.string.chuang_jian_shi_bai, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                context.runOnUiThread(this::onConnectionLost);
            }
        }).start();
    }

    private void onConnectionLost() {
        if (context.isFinishing() || context.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.chuang_jian_shi_bai)
                .setMessage(R.string.kong_tong_dao_de_lian_jie_yi_duan_kai__)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (dialog, which) -> context.finish()).show();
    }
}
