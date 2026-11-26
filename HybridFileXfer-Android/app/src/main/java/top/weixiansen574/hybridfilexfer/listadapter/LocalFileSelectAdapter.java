package top.weixiansen574.hybridfilexfer.listadapter;

import android.app.Activity;
import android.os.Environment;
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
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;

public class LocalFileSelectAdapter extends FileSelectAdapter {

    public LocalFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView,
                                  LinearLayoutManager linearLayoutManager, Toolbar fileSelectToolbar,
                                  View.OnTouchListener onTouchListener, IHfxEngine engine, String savedDir) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener,
                engine, Directory.getCurrentFileSystem(), savedDir);
    }

    @Override
    public List<RemoteFile> listFiles(String path) throws Exception {
        IpcPayloads.FsListResult res = EngineIpcHelper.unwrap(
                engine.listFilesJson("local", path), IpcPayloads.FsListResult.class);
        return EngineIpcHelper.toRemoteFiles(res);
    }

    @Override
    protected void handleListException(Throwable th) {
        context.runOnUiThread(() -> {
            cancelLoading();
            Toast.makeText(context, R.string.jin_ru_mu_lu_shi_bai, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected String getDefaultDir() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    @Override
    public boolean deleteFile(String file) throws RemoteException, IOException {
        IpcPayloads.OkResult res = EngineIpcHelper.unwrap(
                engine.deleteFile("local", file), IpcPayloads.OkResult.class);
        return res != null && res.ok;
    }

    @Override
    public void mkdir(String parent, String child) {
        new Thread(() -> {
            try {
                IpcPayloads.OkResult res = EngineIpcHelper.unwrap(
                        engine.mkdir("local", parent, child), IpcPayloads.OkResult.class);
                boolean ok = res != null && res.ok;
                context.runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(context, R.string.chuang_jian_cheng_gong, Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(context, R.string.chuang_jian_shi_bai, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                context.runOnUiThread(() ->
                        Toast.makeText(context, R.string.chuang_jian_shi_bai, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
