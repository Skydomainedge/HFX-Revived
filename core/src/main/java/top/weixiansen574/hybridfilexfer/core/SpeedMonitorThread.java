package top.weixiansen574.hybridfilexfer.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;

/**
 * Periodically samples per-stream counters and aggregates throughput per physical rail
 * ({@code iName}), normalizing values to bytes/second.
 */
public class SpeedMonitorThread extends Thread {
    public static final long DEFAULT_INTERVAL_MS = 1000L;

    private volatile boolean isRun = true;
    private final List<TransferConnection> connections;
    private final TransferFileCallback callback;
    private final long intervalMs;

    public SpeedMonitorThread(List<TransferConnection> connections, TransferFileCallback callback) {
        this(connections, callback, DEFAULT_INTERVAL_MS);
    }

    public SpeedMonitorThread(List<TransferConnection> connections, TransferFileCallback callback, long intervalMs) {
        this.connections = connections;
        this.callback = callback;
        this.intervalMs = Math.max(100L, intervalMs);
    }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        long lastSampleNs = System.nanoTime();
        while (isRun) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
            if (!isRun) {
                break;
            }
            long nowNs = System.nanoTime();
            long elapsedMs = Math.max(1L, (nowNs - lastSampleNs) / 1_000_000L);
            lastSampleNs = nowNs;

            List<TrafficInfo> aggregated = aggregateByRail(connections, elapsedMs);
            callback.onSpeedInfo(aggregated);
        }
    }

    public static List<TrafficInfo> aggregateByRail(List<TransferConnection> connections, long elapsedMs) {
        long safeElapsedMs = Math.max(1L, elapsedMs);
        Map<String, TrafficInfo> byRail = new LinkedHashMap<>();
        for (TransferConnection connection : connections) {
            TrafficInfo delta = connection.resetCurrentTrafficInfo();
            TrafficInfo acc = byRail.get(connection.iName);
            if (acc == null) {
                acc = new TrafficInfo(connection.iName, 0, 0);
                byRail.put(connection.iName, acc);
            }
            acc.uploadTraffic += delta.uploadTraffic;
            acc.downloadTraffic += delta.downloadTraffic;
        }
        List<TrafficInfo> result = new ArrayList<>(byRail.size());
        for (TrafficInfo info : byRail.values()) {
            long upBps = (info.uploadTraffic * 1000L) / safeElapsedMs;
            long downBps = (info.downloadTraffic * 1000L) / safeElapsedMs;
            result.add(new TrafficInfo(info.iName, upBps, downBps));
        }
        return result;
    }

    public void cancel() {
        isRun = false;
        interrupt();
    }
}
