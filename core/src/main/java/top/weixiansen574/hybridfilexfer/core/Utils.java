package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public final class Utils {
    public static final String ILLEGAL_CHARACTERS = "<>:\"/\\|?*";

    private Utils() {
    }

    public static void sortFiles(List<RemoteFile> fileList) {
        Collections.sort(fileList, new Comparator<RemoteFile>() {
            @Override
            public int compare(RemoteFile f1, RemoteFile f2) {
                if (f1.isDirectory() && !f2.isDirectory()) {
                    return -1;
                } else if (!f1.isDirectory() && f2.isDirectory()) {
                    return 1;
                } else {
                    return f1.getName().compareTo(f2.getName());
                }
            }
        });
    }

    public static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + "B/s";
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2fKB/s", bytesPerSecond / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.2fMB/s", bytesPerSecond / (1024.0 * 1024.0));
    }

    public static String formatDateTime(long milliseconds) {
        return new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()).format(new Date(milliseconds));
    }

    public static String formatTime(long milliseconds) {
        if (milliseconds < 0) {
            throw new IllegalArgumentException("milliseconds must not be negative");
        }
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        }
        long totalSeconds = milliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours == 0) {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        }
    }

    public static String formatFileSize(long size) {
        if (size < 1024) {
            return size + "B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2fKB", size / 1024.0);
        }
        if (size < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.2fMB", size / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.2fGB", size / (1024.0 * 1024.0 * 1024.0));
    }

    public static boolean containsIllegalCharacters(String text) {
        for (int i = 0; i < ILLEGAL_CHARACTERS.length(); i++) {
            if (text.indexOf(ILLEGAL_CHARACTERS.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    public static List<RemoteFile> listRemoteFiles(String path) {
        File[] files = new File(path).listFiles();
        if (files == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(files.length);
        for (File file : files) {
            remoteFiles.add(new RemoteFile(file));
        }
        return remoteFiles;
    }
}
