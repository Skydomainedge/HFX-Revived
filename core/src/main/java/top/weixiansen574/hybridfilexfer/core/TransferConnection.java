package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.nio.DataByteChannel;

public class TransferConnection {
    public final int railId;
    public final int streamIndex;
    public final String iName;
    public final RailType railType;
    public final DataByteChannel channel;

    private TrafficInfo currentTraffic;
    private TrafficInfo totalTraffic;

    public TransferConnection(String iName, DataByteChannel channel) throws IOException {
        this(0, 0, iName, RailType.inferFromInterfaceName(iName), channel);
    }

    public TransferConnection(int railId, int streamIndex, String iName, RailType railType,
                              DataByteChannel channel) {
        this.railId = railId;
        this.streamIndex = streamIndex;
        this.iName = iName;
        this.railType = railType != null ? railType : RailType.inferFromInterfaceName(iName);
        this.channel = channel;
        this.currentTraffic = new TrafficInfo(iName);
        this.totalTraffic = new TrafficInfo(iName);
    }

    public String getStreamLabel() {
        return streamIndex == 0 ? iName : (iName + "#" + streamIndex);
    }

    public synchronized void addUploadedBytes(long byteCount) {
        currentTraffic.uploadTraffic += byteCount;
        totalTraffic.uploadTraffic += byteCount;
    }

    public synchronized void addDownloadedBytes(long byteCount) {
        currentTraffic.downloadTraffic += byteCount;
        totalTraffic.downloadTraffic += byteCount;
    }

    public synchronized TrafficInfo resetCurrentTrafficInfo() {
        TrafficInfo info = currentTraffic;
        currentTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo resetTotalTrafficInfo() {
        TrafficInfo info = totalTraffic;
        totalTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo getTotalTraffic() {
        return new TrafficInfo(iName, totalTraffic.uploadTraffic, totalTraffic.downloadTraffic);
    }

    public void close() throws IOException {
        channel.close();
    }
}
