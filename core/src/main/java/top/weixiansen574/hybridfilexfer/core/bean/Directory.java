package top.weixiansen574.hybridfilexfer.core.bean;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Directory {
    public static final int FILE_SYSTEM_UNIX = 0;
    public static final int FILE_SYSTEM_WINDOWS = 1;
    @NotNull
    public final String path;
    public final int fileSystem;

    public Directory(String path, int fileSystem) {
        this.fileSystem = fileSystem;
        this.path = normalizePath(path);
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (path.equals("/")) return "/";
        String separator = (fileSystem == FILE_SYSTEM_UNIX) ? "/" : "\\";

        if (fileSystem == FILE_SYSTEM_WINDOWS) {
            path = path.replace("/", separator);
        }

        if (fileSystem == FILE_SYSTEM_WINDOWS && path.matches("^[A-Za-z]:$")) {
            path += separator;
        }

        if (!path.equals(separator) && !path.endsWith(separator)) {
            path += separator;
        }

        return path;
    }

    public Directory parent() {
        if (fileSystem == FILE_SYSTEM_UNIX) {
            if ("/".equals(path)) return null;
            String trimmed = path.substring(0, path.length() - 1);
            int idx = trimmed.lastIndexOf("/");
            String parentPath = (idx <= 0) ? "/" : trimmed.substring(0, idx + 1);
            return new Directory(parentPath, fileSystem);
        } else {
            if ("/".equals(path)) return null;
            String norm = path.substring(0, path.length() - 1);
            int idx = norm.lastIndexOf("\\");
            if (idx < 2) return new Directory("/", fileSystem);
            return new Directory(norm.substring(0, idx + 1), fileSystem);
        }
    }

    public Directory append(String child) {
        if (child == null || child.isEmpty()) return this;
        while (child.startsWith("/") || child.startsWith("\\")) {
            child = child.substring(1);
        }
        return new Directory(path + child, fileSystem);
    }

    public String generateTransferPath(String file, Directory remote) {
        String localSep = (this.fileSystem == FILE_SYSTEM_UNIX) ? "/" : "\\";
        String remoteSep = (remote.fileSystem == FILE_SYSTEM_UNIX) ? "/" : "\\";

        String normalizedFile;
        if (this.fileSystem == FILE_SYSTEM_UNIX) {
            normalizedFile = file;
        } else {
            normalizedFile = file.replace("/", localSep);
        }

        String localFolder = this.path;
        String relativePath;
        if (normalizedFile.startsWith(localFolder)) {
            relativePath = normalizedFile.substring(localFolder.length());
        } else if (normalizedFile.startsWith(localSep)) {
            relativePath = normalizedFile.substring(1);
        } else {
            relativePath = normalizedFile;
        }

        String[] segments = relativePath.split(Pattern.quote(localSep));
        List<String> sanitizedSegments = new ArrayList<>();
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            sanitizedSegments.add(seg.replaceAll("[\\\\:*?\"<>|]", "_"));
        }

        if (sanitizedSegments.isEmpty()) {
            return remote.path;
        }
        StringBuilder sb = new StringBuilder(remote.path);
        for (int i = 0; i < sanitizedSegments.size(); i++) {
            if (i > 0) {
                sb.append(remoteSep);
            }
            sb.append(sanitizedSegments.get(i));
        }
        return sb.toString();
    }

    public static int getCurrentFileSystem() {
        if (File.separator.equals("\\")) {
            return FILE_SYSTEM_WINDOWS;
        } else {
            return FILE_SYSTEM_UNIX;
        }
    }

    @NotNull
    @Override
    public String toString() {
        return "Directory{" +
                "path='" + path + '\'' +
                ", fileSystem=" + (fileSystem == FILE_SYSTEM_UNIX ? "UNIX" : "WINDOWS") +
                '}';
    }
}
