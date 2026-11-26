package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import top.weixiansen574.hybridfilexfer.bean.BookMark;
import top.weixiansen574.hybridfilexfer.engine.TrustedPcStore;

@SuppressLint("Range")
public class ConfigDB extends SQLiteOpenHelper {
    public static final int VERSION = 2;
    private static final String TABLE_TRUSTED_PCS = "trusted_pcs";
    private static ConfigDB instance;

    private ConfigDB(@Nullable Context context) {
        super(context, "config", null, VERSION);
    }

    public synchronized static ConfigDB getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigDB(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    NOT NULL\n" +
                "                 UNIQUE\n" +
                ");");
        db.execSQL("CREATE TABLE remote_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    UNIQUE\n" +
                "                 NOT NULL\n" +
                ");");
        createTrustedPcsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createTrustedPcsTable(db);
        }
    }

    private static void createTrustedPcsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_TRUSTED_PCS + " (\n" +
                "    pc_id        TEXT    PRIMARY KEY NOT NULL,\n" +
                "    pc_name      TEXT    NOT NULL,\n" +
                "    pair_secret  TEXT    NOT NULL,\n" +
                "    last_ips     TEXT,\n" +
                "    paired_at    INTEGER NOT NULL,\n" +
                "    last_seen_at INTEGER NOT NULL\n" +
                ");");
    }

    public long addLocalBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase().insert("local_dir_bookmarks", null, cv);
    }

    public long addRemoteBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase().insert("remote_dir_bookmarks", null, cv);
    }

    public boolean checkLocalBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM local_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }

    public boolean checkRemoteBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM remote_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }

    public long removeLocalBookmark(int id) {
        return getWritableDatabase()
                .delete("local_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }

    public long removeRemoteBookmark(int id) {
        return getWritableDatabase()
                .delete("remote_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }

    public List<BookMark> getAllLocalBookmark() {
        return getAllBookmarks("local_dir_bookmarks");
    }

    public List<BookMark> getAllRemoteBookmark() {
        return getAllBookmarks("remote_dir_bookmarks");
    }

    private List<BookMark> getAllBookmarks(String tableName) {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM " + tableName, null);
        List<BookMark> bookmarks = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            bookmarks.add(new BookMark(
                    cursor.getInt(cursor.getColumnIndex("id")),
                    cursor.getString(cursor.getColumnIndex("path"))
            ));
        }
        cursor.close();
        return bookmarks;
    }

    // ---- M4 Trusted PCs Persistence ----

    public List<TrustedPcStore.TrustedPcRecord> loadAllTrustedPcs() {
        List<TrustedPcStore.TrustedPcRecord> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT pc_id, pc_name, pair_secret, last_ips, paired_at, last_seen_at FROM " + TABLE_TRUSTED_PCS,
                null);
        try {
            while (cursor.moveToNext()) {
                TrustedPcStore.TrustedPcRecord rec = new TrustedPcStore.TrustedPcRecord();
                rec.pcId = cursor.getString(0);
                rec.pcName = cursor.getString(1);
                rec.pairSecret = cursor.getString(2);
                String ipsRaw = cursor.getString(3);
                if (ipsRaw != null && !ipsRaw.trim().isEmpty()) {
                    rec.lastIps = new ArrayList<>(Arrays.asList(ipsRaw.split(",")));
                } else {
                    rec.lastIps = new ArrayList<>(Collections.<String>emptyList());
                }
                rec.pairedAt = cursor.getLong(4);
                rec.lastSeenAt = cursor.getLong(5);
                result.add(rec);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public void upsertTrustedPc(TrustedPcStore.TrustedPcRecord record) {
        ContentValues cv = new ContentValues();
        cv.put("pc_id", record.pcId);
        cv.put("pc_name", record.pcName != null ? record.pcName : record.pcId);
        cv.put("pair_secret", record.pairSecret);
        cv.put("last_ips", joinIps(record.lastIps));
        cv.put("paired_at", record.pairedAt);
        cv.put("last_seen_at", record.lastSeenAt);
        getWritableDatabase().insertWithOnConflict(
                TABLE_TRUSTED_PCS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean deleteTrustedPc(String pcId) {
        int rows = getWritableDatabase().delete(TABLE_TRUSTED_PCS, "pc_id = ?", new String[]{pcId});
        return rows > 0;
    }

    private static String joinIps(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ips.get(i));
        }
        return sb.toString();
    }
}
