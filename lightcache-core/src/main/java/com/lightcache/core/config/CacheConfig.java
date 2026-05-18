package com.lightcache.core.config;

/**
 * 缓存配置对象。
 * <p>
 * 控制定期删除的频率与采样数、快照路径、统计开关。
 */
public class CacheConfig {

    /** 定期删除间隔（秒），默认 10 */
    private int periodicIntervalSec = 10;

    /** 每次定期删除扫描的键数量，默认 20 */
    private int periodicSampleSize = 20;

    /** 快照文件存放目录，默认 ./data */
    private String snapshotDir = "./data";

    /** 快照文件名，默认 snapshot.data */
    private String snapshotFileName = "snapshot.data";

    /** 临时快照文件名（原子写入用） */
    private String snapshotTempFileName = "snapshot.tmp";

    /** 是否启用命中率统计，默认 true */
    private boolean statsEnabled = true;

    // ---- boilerplate ----

    public int getPeriodicIntervalSec() { return periodicIntervalSec; }
    public void setPeriodicIntervalSec(int periodicIntervalSec) { this.periodicIntervalSec = periodicIntervalSec; }

    public int getPeriodicSampleSize() { return periodicSampleSize; }
    public void setPeriodicSampleSize(int periodicSampleSize) { this.periodicSampleSize = periodicSampleSize; }

    public String getSnapshotDir() { return snapshotDir; }
    public void setSnapshotDir(String snapshotDir) { this.snapshotDir = snapshotDir; }

    public String getSnapshotFileName() { return snapshotFileName; }
    public void setSnapshotFileName(String snapshotFileName) { this.snapshotFileName = snapshotFileName; }

    public String getSnapshotTempFileName() { return snapshotTempFileName; }
    public void setSnapshotTempFileName(String snapshotTempFileName) { this.snapshotTempFileName = snapshotTempFileName; }

    public boolean isStatsEnabled() { return statsEnabled; }
    public void setStatsEnabled(boolean statsEnabled) { this.statsEnabled = statsEnabled; }
}
