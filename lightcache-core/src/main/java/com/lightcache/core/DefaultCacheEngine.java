package com.lightcache.core;

import com.lightcache.core.config.CacheConfig;
import com.lightcache.core.expire.ExpireManager;
import com.lightcache.core.snapshot.SnapshotManager;
import com.lightcache.core.stats.StatsCollector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 默认缓存引擎实现。
 * <p>
 * 组合 ExpireManager、StatsCollector、SnapshotManager 提供完整的键值缓存功能。
 * 基于 ConcurrentHashMap 存储，保证线程安全。
 */
public class DefaultCacheEngine implements CacheEngine {

    private static final Logger LOG = Logger.getLogger(DefaultCacheEngine.class.getName());

    private final ConcurrentHashMap<String, CacheEntry<Object>> store;
    private final ExpireManager expireManager;
    private final StatsCollector statsCollector;
    private final SnapshotManager snapshotManager;
    private final CacheConfig config;

    public DefaultCacheEngine() {
        this(new CacheConfig());
    }

    public DefaultCacheEngine(CacheConfig config) {
        this.config = config;
        this.store = new ConcurrentHashMap<>();
        this.statsCollector = new StatsCollector();
        this.expireManager = new ExpireManager(store,
                config.getPeriodicIntervalSec(),
                config.getPeriodicSampleSize());
        this.snapshotManager = new SnapshotManager(store,
                config.getSnapshotDir(),
                config.getSnapshotFileName(),
                config.getSnapshotTempFileName());

        // 启动定期删除
        expireManager.start();

        // 启动时自动加载已有快照
        load();
    }

    // ========== CacheEngine 接口实现 ==========

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        long ttlMillis = ttlSeconds <= 0 ? -1 : ttlSeconds * 1000;
        store.put(key, new CacheEntry<>(value, ttlMillis));
    }

    @Override
    public Object get(String key) {
        CacheEntry<Object> entry = store.get(key);
        if (entry == null) {
            if (config.isStatsEnabled()) statsCollector.miss();
            return null;
        }

        // 惰性删除
        if (expireManager.lazyDelete(key, entry)) {
            if (config.isStatsEnabled()) statsCollector.miss();
            return null;
        }

        entry.touch();
        if (config.isStatsEnabled()) statsCollector.hit();
        return entry.getValue();
    }

    @Override
    public void del(String key) {
        store.remove(key);
    }

    @Override
    public boolean exists(String key) {
        CacheEntry<Object> entry = store.get(key);
        if (entry == null) return false;
        if (entry.isExpired()) {
            store.remove(key);
            return false;
        }
        return true;
    }

    @Override
    public long ttl(String key) {
        CacheEntry<Object> entry = store.get(key);
        if (entry == null) return -2;  // key 不存在
        if (entry.isExpired()) {
            store.remove(key);
            return -2;
        }
        return entry.ttlSeconds();
    }

    @Override
    public long size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
        statsCollector.reset();
    }

    @Override
    public Map<String, Object> stats() {
        return statsCollector.snapshot(size());
    }

    @Override
    public void save() {
        int count = snapshotManager.save();
        LOG.info("Manual snapshot: " + count + " keys saved");
    }

    @Override
    public void load() {
        int count = snapshotManager.load();
        LOG.info("Snapshot load complete: " + count + " keys restored");
    }

    @Override
    public void shutdown() {
        save();
        expireManager.shutdown();
        LOG.info("DefaultCacheEngine shutdown complete");
    }
}
