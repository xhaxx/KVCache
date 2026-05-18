package com.lightcache.server.controller;

import com.lightcache.core.CacheEngine;
import com.lightcache.server.model.PutRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 缓存 REST API 控制器。
 * <p>
 * 暴露键值缓存的全部 HTTP 接口，端口 8110。
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private final CacheEngine engine;

    public CacheController(CacheEngine engine) {
        this.engine = engine;
    }

    /**
     * PUT /cache/{key} — 写入或覆盖键值对。
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> put(
            @PathVariable String key,
            @RequestBody PutRequest request) {
        engine.put(key, request.getValue(), request.getTtl());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * GET /cache/{key} — 读取键的值。
     */
    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        Object value = engine.get(key);
        if (value == null) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    /**
     * DELETE /cache/{key} — 删除键。
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String key) {
        if (!engine.exists(key)) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        engine.del(key);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * GET /cache/stats — 查看运行统计。
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(engine.stats());
    }

    /**
     * POST /cache/save — 手动触发快照持久化。
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save() {
        engine.save();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
