package com.lightcache.core.snapshot;

import com.lightcache.core.CacheEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 快照持久化管理器。
 * <p>
 * 将当前有效键值对序列化为 JSON Lines 文件，写入采用 tmp + rename 原子模式。
 * 加载时按行容错解析，单行损坏不阻塞整体恢复。
 */
public class SnapshotManager {

    private static final Logger LOG = Logger.getLogger(SnapshotManager.class.getName());

    private final Path dataFile;
    private final Path tempFile;
    private final ConcurrentHashMap<String, CacheEntry<Object>> store;

    public SnapshotManager(ConcurrentHashMap<String, CacheEntry<Object>> store, String dir, String fileName, String tempName) {
        this.store = store;
        Path dirPath = Path.of(dir);
        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            LOG.warning("Failed to create snapshot directory: " + e.getMessage());
        }
        this.dataFile = dirPath.resolve(fileName);
        this.tempFile = dirPath.resolve(tempName);
    }

    /**
     * 将当前所有有效键值对写入快照文件（原子写入）。
     *
     * @return 成功写入的键数量，失败返回 -1
     */
    public synchronized int save() {
        int count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, CacheEntry<Object>> entry : store.entrySet()) {
                CacheEntry<Object> ce = entry.getValue();
                if (ce.isExpired()) continue;

                String json = toJsonLine(entry.getKey(), ce);
                writer.write(json);
                writer.newLine();
                count++;
            }
            writer.flush();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write snapshot: " + e.getMessage(), e);
            return -1;
        }

        // 原子 rename
        try {
            Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Snapshot saved: " + count + " keys -> " + dataFile);
            return count;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to commit snapshot: " + e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 从快照文件加载数据到 store。
     *
     * @return 成功加载的键数量
     */
    public int load() {
        if (!Files.exists(dataFile)) {
            LOG.info("No snapshot file found, starting fresh");
            return 0;
        }

        int loaded = 0;
        int errors = 0;
        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    SnapshotEntry se = parseJsonLine(line);
                    if (se == null) continue;
                    // 跳过已过期的条目
                    if (se.expireAt > 0 && System.currentTimeMillis() > se.expireAt) {
                        continue;
                    }
                    long ttlMillis = se.expireAt == -1 ? -1 : se.expireAt - System.currentTimeMillis();
                    store.put(se.key, new CacheEntry<>(se.value, ttlMillis));
                    loaded++;
                } catch (Exception e) {
                    errors++;
                    LOG.warning("Skipped corrupted snapshot line: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read snapshot file: " + e.getMessage(), e);
            return loaded;
        }

        LOG.info("Snapshot loaded: " + loaded + " keys" + (errors > 0 ? ", " + errors + " errors skipped" : ""));
        return loaded;
    }

    public Path getDataFile() { return dataFile; }

    // ========== 手动 JSON 序列化/反序列化（零外部依赖） ==========

    /**
     * 将 entry 序列化为一行 JSON。
     */
    static String toJsonLine(String key, CacheEntry<Object> ce) {
        return "{\"key\":\"" + escapeJson(key) + "\"," +
               "\"value\":\"" + escapeJson(String.valueOf(ce.getValue())) + "\"," +
               "\"expireAt\":" + ce.getExpireAt() + "}";
    }

    /**
     * 从一行 JSON 解析为临时对象。
     */
    static SnapshotEntry parseJsonLine(String line) {
        String json = line.trim();

        String key = extractJsonString(json, "key");
        String value = extractJsonString(json, "value");
        long expireAt = extractJsonLong(json, "expireAt");

        if (key == null || value == null) return null;

        return new SnapshotEntry(key, value, expireAt);
    }

    static String extractJsonString(String json, String field) {
        String prefix = "\"" + field + "\":\"";
        int start = json.indexOf(prefix);
        if (start == -1) return null;
        start += prefix.length();

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n') { sb.append('\n'); i++; }
                else if (next == 't') { sb.append('\t'); i++; }
                else { sb.append(c); }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null; // 未闭合引号
    }

    static long extractJsonLong(String json, String field) {
        String prefix = "\"" + field + "\":";
        int start = json.indexOf(prefix);
        if (start == -1) return 0;
        start += prefix.length();

        StringBuilder sb = new StringBuilder();
        boolean negative = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '-') { negative = true; continue; }
            if (c >= '0' && c <= '9') { sb.append(c); }
            else if (sb.length() > 0) { break; }
        }
        if (sb.length() == 0) return 0;
        long val = Long.parseLong(sb.toString());
        return negative ? -val : val;
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 快照文件中的单行条目 */
    static class SnapshotEntry {
        final String key;
        final String value;
        final long expireAt;

        SnapshotEntry(String key, String value, long expireAt) {
            this.key = key;
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
