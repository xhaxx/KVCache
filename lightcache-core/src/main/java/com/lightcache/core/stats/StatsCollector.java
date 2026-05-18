package com.lightcache.core.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 命中率统计收集器。
 * <p>
 * 使用 AtomicLong 保证无锁并发安全。
 */
public class StatsCollector {

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public void hit() {
        hits.incrementAndGet();
    }

    public void miss() {
        misses.incrementAndGet();
    }

    /**
     * 计算命中率。
     *
     * @return 0.0 ~ 1.0 之间的浮点数；无任何访问时返回 0.0
     */
    public double hitRate() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return total == 0 ? 0.0 : (double) h / total;
    }

    /**
     * 收集当前快照指标。
     *
     * @param currentSize 当前有效键数
     */
    public Map<String, Object> snapshot(long currentSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits.get());
        result.put("misses", misses.get());
        result.put("size", currentSize);
        result.put("hitRate", hitRate());
        return result;
    }

    public long getHits() { return hits.get(); }
    public long getMisses() { return misses.get(); }

    /**
     * 重置所有计数器（hits、misses 归零）。
     * <p>
     * 配合 CacheEngine.clear() 使用，保证测试隔离。
     */
    public void reset() {
        hits.set(0);
        misses.set(0);
    }
}
