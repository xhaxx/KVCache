package com.lightcache.core;

import java.util.Map;

/**
 * 缓存引擎核心接口。
 * <p>
 * 定义键值缓存的所有原子操作契约。实现类需保证线程安全。
 */
public interface CacheEngine {

    /**
     * 写入键值对，自动覆盖已存在的 key。
     *
     * @param key        键
     * @param value      值
     * @param ttlSeconds 存活时间（秒），≤0 表示永不过期
     */
    void put(String key, Object value, long ttlSeconds);

    /**
     * 读取键的值，触发惰性删除。
     *
     * @return 命中返回值，未命中/已过期返回 null
     */
    Object get(String key);

    /**
     * 主动删除键。
     */
    void del(String key);

    /**
     * 检查键是否存在且未过期。
     */
    boolean exists(String key);

    /**
     * 获取剩余 TTL（秒）。
     *
     * @return -2 = 不存在，-1 = 永不过期，≥0 = 剩余秒数
     */
    long ttl(String key);

    /**
     * 当前有效键的近似数量。
     */
    long size();

    /**
     * 清空所有键。
     */
    void clear();

    /**
     * 获取运行统计。
     *
     * @return Map 包含 hits, misses, size, hitRate 等字段
     */
    Map<String, Object> stats();

    /**
     * 触发快照持久化（原子写入磁盘）。
     */
    void save();

    /**
     * 从快照文件恢复数据。
     */
    void load();

    /**
     * 优雅关闭：停止后台线程并做最后一次持久化。
     */
    void shutdown();
}
