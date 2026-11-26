package top.weixiansen574.hybridfilexfer.core.callback;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public interface TransferFileCallback {
    int ERROR_TYPE_EXCEPTION = 1;
    int ERROR_TYPE_INTERRUPT = 2;
    int ERROR_TYPE_READ_ERROR = 3;
    int ERROR_TYPE_WRITE_ERROR = 4;

    void onFileUploading(String iName, String path, long targetSize, long totalSize);

    void onFileDownloading(String iName, String path, long targetSize, long totalSize);

    void onSpeedInfo(List<TrafficInfo> trafficInfoList);

    void onChannelComplete(String iName, long traffic, long time);

    void onChannelError(String iName, int errorType, String message);

    void onReadFileError(String message);

    void onWriteFileError(String message);

    void onComplete(boolean isUpload, long traffic, long time);

    void onIncomplete();
}
