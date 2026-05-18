package com.lightcache.client;

import com.lightcache.core.CacheEngine;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * LightCache HTTP 客户端 SDK。
 * <p>
 * 实现 CacheEngine 接口，底层通过 REST API 与 LightCache Server 通信。
 * save()/load()/shutdown() 调用 server 对应端点；clear() 未提供 server 端点故抛异常。
 */
public class LightCacheClient implements CacheEngine {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public LightCacheClient(String baseUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public LightCacheClient(String baseUrl, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("value", value);
        body.put("ttl", ttlSeconds);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body);
        restTemplate.exchange(baseUrl + "/cache/" + key, HttpMethod.PUT, request, Map.class);
    }

    @Override
    public Object get(String key) {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    baseUrl + "/cache/" + key, Map.class);
            if (resp.getBody() != null) {
                return resp.getBody().get("value");
            }
        } catch (Exception e) {
            // 404 等异常视为未命中
        }
        return null;
    }

    @Override
    public void del(String key) {
        restTemplate.delete(baseUrl + "/cache/" + key);
    }

    @Override
    public boolean exists(String key) {
        Object value = get(key);
        return value != null;
    }

    @Override
    public long ttl(String key) {
        // 简化实现：通过 get 是否命中判断
        Object value = get(key);
        return value != null ? -1 : -2;
    }

    @Override
    public long size() {
        Map<String, Object> stats = stats();
        Object size = stats.get("size");
        return size instanceof Number ? ((Number) size).longValue() : -1;
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("clear() is not exposed via HTTP for safety");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> stats() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                baseUrl + "/cache/stats", Map.class);
        return resp.getBody();
    }

    @Override
    public void save() {
        restTemplate.postForEntity(baseUrl + "/cache/save", null, Map.class);
    }

    @Override
    public void load() {
        throw new UnsupportedOperationException("load() is server-side only");
    }

    @Override
    public void shutdown() {
        try {
            save();
        } catch (Exception ignored) {
            // 忽略 shutdown 中的异常
        }
    }
}
