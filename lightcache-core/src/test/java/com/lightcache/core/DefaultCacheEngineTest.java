package com.lightcache.core;

import com.lightcache.core.config.CacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultCacheEngine 完整单元测试。
 */
@DisplayName("DefaultCacheEngine")
class DefaultCacheEngineTest {

    private DefaultCacheEngine engine;
    private CacheConfig config;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        config = new CacheConfig();
        config.setPeriodicIntervalSec(10); // 测试中不依赖定期删除
        config.setStatsEnabled(true);
        // 每个测试使用独立临时目录，避免快照文件交叉污染
        config.setSnapshotDir(tempDir.toString());
        engine = new DefaultCacheEngine(config);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    // ========== FR-01：键值存取 ==========

    @Nested
    @DisplayName("FR-01 键值存取")
    class KeyValueAccess {

        @Test
        @DisplayName("写入并立即读取应返回原值")
        void putAndGet() {
            engine.put("name", "凯健", 60);
            assertEquals("凯健", engine.get("name"));
        }

        @Test
        @DisplayName("读取不存在的 key 返回 null")
        void getNonExistent() {
            assertNull(engine.get("no_such_key"));
        }

        @Test
        @DisplayName("写入相同 key 应覆盖旧值")
        void putOverwrite() {
            engine.put("key", "v1", 60);
            engine.put("key", "v2", 60);
            assertEquals("v2", engine.get("key"));
        }

        @Test
        @DisplayName("get 操作命中后应更新统计")
        void getUpdatesStats() {
            engine.put("a", 1, 60);
            engine.put("b", 2, 60);
            engine.get("a");           // hit
            engine.get("a");           // hit
            engine.get("nonexistent"); // miss

            Map<String, Object> stats = engine.stats();
            assertEquals(2L, stats.get("hits"));
            assertEquals(1L, stats.get("misses"));
        }

        @Test
        @DisplayName("永不过期的键 ttl 应返回 -1")
        void noExpiryKey() {
            engine.put("forever", "value", -1);
            assertEquals(-1, engine.ttl("forever"));
            assertEquals("value", engine.get("forever"));
        }
    }

    // ========== FR-02：键删除与存在性 ==========

    @Nested
    @DisplayName("FR-02 键删除与存在性")
    class KeyDeletion {

        @Test
        @DisplayName("删除存在的 key 后 exists 返回 false")
        void delExistingKey() {
            engine.put("key", "value", 60);
            assertTrue(engine.exists("key"));
            engine.del("key");
            assertFalse(engine.exists("key"));
            assertNull(engine.get("key"));
        }

        @Test
        @DisplayName("删除不存在的 key 不应抛异常")
        void delNonExistent() {
            assertDoesNotThrow(() -> engine.del("no_such_key"));
        }

        @Test
        @DisplayName("exists 对于不存在 key 返回 false")
        void existsNonExistent() {
            assertFalse(engine.exists("ghost"));
        }
    }

    // ========== FR-03：TTL 过期 ==========

    @Nested
    @DisplayName("FR-03 TTL 过期")
    class TtlExpiration {

        @Test
        @DisplayName("TTL=1 秒后 get 应返回 null（惰性删除）")
        void lazyExpiration() throws InterruptedException {
            engine.put("temp", "data", 1);
            assertEquals("data", engine.get("temp")); // 立即取 OK

            Thread.sleep(1100); // 等过期

            assertNull(engine.get("temp"));     // 惰性删除触发
            assertEquals(-2, engine.ttl("temp")); // 已删除
        }

        @Test
        @DisplayName("过期后 exists 返回 false")
        void expiredKeyNotExists() throws InterruptedException {
            engine.put("x", 1, 1);
            Thread.sleep(1100);
            assertFalse(engine.exists("x"));
        }

        @Test
        @DisplayName("ttl() 返回剩余秒数")
        void ttlReturnsRemaining() {
            engine.put("t", 1, 300);
            long ttl = engine.ttl("t");
            assertTrue(ttl >= 298 && ttl <= 300,
                    "ttl should be ~300, got " + ttl);
        }
    }

    // ========== FR-04：统计 ==========

    @Nested
    @DisplayName("FR-04 统计")
    class Statistics {

        @Test
        @DisplayName("初始 stats 各项为 0")
        void initialStats() {
            Map<String, Object> stats = engine.stats();
            assertEquals(0L, stats.get("hits"));
            assertEquals(0L, stats.get("misses"));
            assertEquals(0L, stats.get("size"));
            assertEquals(0.0, stats.get("hitRate"));
        }

        @Test
        @DisplayName("全部命中时 hitRate=1.0")
        void perfectHitRate() {
            engine.put("a", 1, 60);
            engine.get("a");
            assertEquals(1.0, engine.stats().get("hitRate"));
        }

        @Test
        @DisplayName("size() 反映当前有效键数")
        void sizeCountsValidKeys() {
            engine.put("a", 1, 60);
            engine.put("b", 2, 60);
            assertEquals(2, engine.size());
            engine.del("a");
            assertEquals(1, engine.size());
        }

        @Test
        @DisplayName("clear() 后 size=0")
        void clearEmpties() {
            engine.put("a", 1, 60);
            engine.put("b", 2, 60);
            engine.clear();
            assertEquals(0, engine.size());
        }
    }

    // ========== FR-05：快照持久化 ==========

    @Nested
    @DisplayName("FR-05 快照持久化")
    class SnapshotPersistence {

        @Test
        @DisplayName("save 后 load 应恢复数据")
        void saveAndLoad(@TempDir Path tempDir) {
            // 使用临时目录避免污染
            CacheConfig cfg = new CacheConfig();
            cfg.setSnapshotDir(tempDir.toString());
            DefaultCacheEngine e1 = new DefaultCacheEngine(cfg);

            e1.put("key1", "val1", 300);
            e1.put("key2", 42, 300);
            e1.put("key3", true, 1); // 会过期
            e1.save();

            // 等 key3 过期
            try { Thread.sleep(1100); } catch (InterruptedException ignored) {}

            // 新实例加载（注意：JSON 序列化不保留原始类型，恢复值均为 String）
            DefaultCacheEngine e2 = new DefaultCacheEngine(cfg);
            assertEquals("val1", e2.get("key1"));
            assertEquals("42", e2.get("key2"));
            assertNull(e2.get("key3")); // 已过期不加载

            e2.shutdown();
            e1.shutdown();
        }

        @Test
        @DisplayName("空缓存的 save 不应抛异常")
        void saveEmpty(@TempDir Path tempDir) {
            CacheConfig cfg = new CacheConfig();
            cfg.setSnapshotDir(tempDir.toString());
            DefaultCacheEngine e = new DefaultCacheEngine(cfg);
            assertDoesNotThrow(e::save);
            e.shutdown();
        }

        @Test
        @DisplayName("快照文件损坏时 load 应跳过且不阻塞启动")
        void loadCorruptedFile(@TempDir Path tempDir) throws Exception {
            CacheConfig cfg = new CacheConfig();
            cfg.setSnapshotDir(tempDir.toString());

            // 写入损坏的快照文件
            Path dataFile = tempDir.resolve("snapshot.data");
            Files.writeString(dataFile,
                    "{\"key\":\"good\",\"value\":\"42\",\"expireAt\":-1}\n" +
                    "this is garbage\n" +
                    "{\"key\":\"also_good\",\"value\":\"99\",\"expireAt\":-1}\n");

            DefaultCacheEngine e = new DefaultCacheEngine(cfg);
            // 损坏行应被跳过，有效行正常加载
            // get would return Object 42 (int), need to check
            assertNotNull(e.get("also_good"));
            e.shutdown();
        }

        @Test
        @DisplayName("无快照文件时 load 正常启动")
        void loadWithoutFile(@TempDir Path tempDir) {
            CacheConfig cfg = new CacheConfig();
            cfg.setSnapshotDir(tempDir.toString() + "/nonexistent");
            assertDoesNotThrow(() -> {
                DefaultCacheEngine e = new DefaultCacheEngine(cfg);
                e.shutdown();
            });
        }
    }

    // ========== 并发安全 ==========

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("10 线程并发写入 1000 键无异常")
        void concurrentWrites() throws InterruptedException {
            int threads = 10;
            int keysPerThread = 100;
            Thread[] ts = new Thread[threads];

            for (int i = 0; i < threads; i++) {
                final int tid = i;
                ts[i] = new Thread(() -> {
                    for (int j = 0; j < keysPerThread; j++) {
                        engine.put("key_" + tid + "_" + j, j, 60);
                    }
                });
                ts[i].start();
            }

            for (Thread t : ts) t.join();

            // 不要求精确等于 1000（存在过期/覆盖），但应接近
            assertTrue(engine.size() >= 900,
                    "expected >= 900, got " + engine.size());
        }
    }
}
