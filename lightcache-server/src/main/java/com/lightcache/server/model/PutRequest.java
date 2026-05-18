package com.lightcache.server.model;

/**
 * PUT 请求体 DTO。
 */
public class PutRequest {

    private Object value;
    private long ttl;

    public PutRequest() {}

    public PutRequest(Object value, long ttl) {
        this.value = value;
        this.ttl = ttl;
    }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public long getTtl() { return ttl; }
    public void setTtl(long ttl) { this.ttl = ttl; }
}
