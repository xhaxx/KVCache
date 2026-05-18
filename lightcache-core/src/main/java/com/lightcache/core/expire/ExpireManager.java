package com.lightcache.core.expire;

import com.lightcache.core.CacheEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 过期管理器：组合惰性删除与定期删除。
 * <p>
 * 惰性删除：每次 get(key) 时检查 entry 是否过期。<br>
 * 定期删除：后台线程每 N 秒随机采样 M 个键，删除其中已过期的。
 */
public class ExpireManager {

    private static final Logger LOG = Logger.getLogger(ExpireManager.class.getName());

    private final ConcurrentHashMap<String, CacheEntry<Object>> store;
    private final int sampleSize;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /**
     * @param store     共享的存储 Map
     * @param intervalSec 定期删除间隔（秒）
     * @param sampleSize  每次扫描的键数量
     */
    public ExpireManager(ConcurrentHashMap<String, CacheEntry<Object>> store, int intervalSec, int sampleSize) {
        this.store = store;
        this.sampleSize = sampleSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lightcache-expirer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动定期删除线程。
     */
    public void start() {
        if (running) return;
        running = true;
        scheduler.scheduleAtFixedRate(this::periodicScan, 10, 10, TimeUnit.SECONDS);
        LOG.info("ExpireManager started: interval=10s, sampleSize=" + sampleSize);
    }

    /**
     * 停止定期删除线程。
     */
    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("ExpireManager shutdown complete");
    }

    /**
     * 惰性删除：在 get 路径调用。
     *
     * @param key   键
     * @param entry 对应的 entry
     * @return true 表示已过期并被删除
     */
    public boolean lazyDelete(String key, CacheEntry<Object> entry) {
        if (entry != null && entry.isExpired()) {
            store.remove(key);
            return true;
        }
        return false;
    }

    /**
     * 定期删除：随机采样一批键并删除过期的。
     */
    void periodicScan() {
        try {
            // 收集所有 key 的快照
            List<String> allKeys = new ArrayList<>(store.keySet());
            if (allKeys.isEmpty()) return;

            int count = Math.min(sampleSize, allKeys.size());
            int deleted = 0;
            for (int i = 0; i < count; i++) {
                int idx = ThreadLocalRandom.current().nextInt(allKeys.size());
                String key = allKeys.get(idx);
                CacheEntry<Object> entry = store.get(key);
                if (entry != null && entry.isExpired()) {
                    store.remove(key);
                    deleted++;
                }
            }
            if (deleted > 0) {
                LOG.fine("Periodic scan deleted " + deleted + " expired keys (scanned=" + count + ")");
            }
        } catch (Exception e) {
            LOG.warning("Periodic scan error: " + e.getMessage());
        }
    }

    public boolean isRunning() { return running; }
}
