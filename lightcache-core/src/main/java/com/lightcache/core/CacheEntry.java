package com.lightcache.core;

/**
 * 缓存存储单元。
 * <p>
 * 封装值、过期时间戳、创建/访问时间，提供过期判断与访问更新。
 *
 * @param <V> 值类型
 */
public class CacheEntry<V> {

    private final V value;
    private final long expireAt;      // -1 表示永不过期
    private final long createTime;
    private volatile long lastAccessTime;

    public CacheEntry(V value, long ttlMillis) {
        this.value = value;
        this.createTime = System.currentTimeMillis();
        this.lastAccessTime = this.createTime;
        this.expireAt = ttlMillis <= 0 ? -1 : this.createTime + ttlMillis;
    }

    /**
     * 判断此 entry 是否已过期。
     */
    public boolean isExpired() {
        if (expireAt == -1) return false;
        return System.currentTimeMillis() > expireAt;
    }

    /**
     * 更新最后访问时间戳。
     */
    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 获取剩余存活时间（秒）。
     *
     * @return -1 永不过期；正数=剩余秒数；0=立即过期
     */
    public long ttlSeconds() {
        if (expireAt == -1) return -1;
        long remain = expireAt - System.currentTimeMillis();
        return Math.max(0, remain / 1000);
    }

    // ---- getters ----

    public V getValue() { return value; }
    public long getExpireAt() { return expireAt; }
    public long getCreateTime() { return createTime; }
    public long getLastAccessTime() { return lastAccessTime; }
}
