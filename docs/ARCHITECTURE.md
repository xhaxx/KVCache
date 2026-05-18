# LightCache 系统设计说明书

> **版本:** v1.0.0 | **日期:** 2026-05-17 | **阶段:** Phase 2 — 规划设计

---

## 1. 设计目标

基于 SRS v1.1 的功能与非功能需求，设计一个模块化、可测试、易维护的单机键值缓存系统。

**核心原则：**
- Core 模块零外部依赖（纯 JDK 17 标准库）
- 模块间接口契约清晰，依赖方向单向（client → server → core）
- 并发安全优先使用 java.util.concurrent 工具类
- 持久化采用原子写入，保证数据完整性

---

## 2. 系统架构

### 2.1 模块依赖关系

```
┌─────────────────────────────────────────────────────────────────┐
│                     LightCache System (8110)                      │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────┐    ┌──────────────────────┐             │
│  │ lightcache-client   │    │ lightcache-server     │             │
│  │ (Java SDK)          │───>│ (Spring Boot 3.4.3)   │             │
│  │                     │    │                       │             │
│  │  LightCacheClient   │    │  CacheController      │             │
│  │  (RestTemplate)     │    │  (REST API)           │             │
│  └─────────────────────┘    └──────────┬────────────┘             │
│                                        │                          │
│                                        ▼                          │
│                            ┌──────────────────────┐              │
│                            │ lightcache-core      │              │
│                            │ (Zero Dependency)    │              │
│                            │                      │              │
│                            │  DefaultCacheEngine  │              │
│                            │  ├─ ExpireManager    │              │
│                            │  ├─ StatsCollector   │              │
│                            │  └─ SnapshotManager  │              │
│                            └──────────────────────┘              │
│                                                                   │
│  ┌─────────────────────┐                                         │
│  │ lightcache-example  │── uses ──> client & server              │
│  │ (Demo Application)  │                                         │
│  └─────────────────────┘                                         │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 模块职责

| 模块 | 职责 | 对外接口 | 依赖 |
|------|------|---------|------|
| `lightcache-core` | 缓存引擎核心逻辑 | `com.lightcache.core.CacheEngine` | 无 |
| `lightcache-server` | HTTP REST API 服务 | REST endpoints (8110 端口) | core |
| `lightcache-client` | Java SDK 客户端 | `com.lightcache.client.LightCacheClient` | 无（仅用 HTTP） |
| `lightcache-example` | 使用示例 | — | client, server |

---

## 3. 核心设计

### 3.1 包结构

```
com.lightcache.core
├── CacheEngine.java          # 核心接口定义
├── DefaultCacheEngine.java   # 接口实现（门面）
├── CacheEntry.java           # 存储单元
├── expire
│   ├── ExpireManager.java    # 过期管理器（内部类或独立）
│   └── ExpirePolicy.java     # 过期策略枚举
├── stats
│   └── StatsCollector.java   # 命中率统计
├── snapshot
│   └── SnapshotManager.java  # 快照持久化
└── config
    └── CacheConfig.java      # 配置对象

com.lightcache.server
├── LightCacheApplication.java  # Spring Boot 入口
├── controller
│   └── CacheController.java    # REST Controller
├── model
│   └── PutRequest.java         # 写入请求 DTO
└── config
    └── CacheAutoConfig.java    # 缓存引擎自动装配

com.lightcache.client
└── LightCacheClient.java       # HTTP 客户端封装
```

### 3.2 领域模型

```
CacheEntry<V>
├── value: V                     # 存储值
├── expireAt: long               # 过期时间戳（-1 = 永不过期）
├── createTime: long             # 创建时间戳
├── lastAccessTime: long         # 最后访问时间
├── isExpired(): boolean         # 判断是否过期
│   └── 逻辑：expireAt > 0 && System.currentTimeMillis() > expireAt
└── touch(): void                # 更新 lastAccessTime

CacheConfig
├── periodicIntervalSec: int     # 定期删除间隔（默认 10）
├── periodicSampleSize: int      # 每次采样数（默认 20）
├── snapshotPath: String         # 快照文件路径
└── statsEnabled: boolean        # 是否启用统计（默认 true）
```

### 3.3 CacheEngine 接口定义

```java
public interface CacheEngine {

    /**
     * 写入键值对，自动覆盖已存在的 key
     * @param key        键
     * @param value      值（必须可序列化）
     * @param ttlSeconds 存活时间（秒），-1 或 ≤0 表示永不过期
     */
    void put(String key, Object value, long ttlSeconds);

    /**
     * 读取键的值，触发惰性删除
     * @return 命中返回值，未命中/已过期返回 null
     */
    Object get(String key);

    /**
     * 主动删除键
     */
    void del(String key);

    /**
     * 检查键是否存在且未过期
     */
    boolean exists(String key);

    /**
     * 获取剩余 TTL 秒数
     * @return -2=不存在, -1=永不过期, ≥0=剩余秒数
     */
    long ttl(String key);

    /**
     * 当前有效键数量
     */
    long size();

    /**
     * 清空所有键
     */
    void clear();

    /**
     * 获取统计信息
     * @return Map {"hits": N, "misses": N, "size": N, "hitRate": 0.0~1.0}
     */
    Map<String, Object> stats();

    /**
     * 触发快照持久化
     */
    void save();

    /**
     * 从快照文件加载数据
     */
    void load();
}
```

### 3.4 过期策略详细设计

#### 惰性删除（Lazy Expiration）

```
get(key):
    entry = store.get(key)
    if entry == null:
        stats.miss()
        return null
    if entry.isExpired():
        store.remove(key)
        stats.miss()
        return null
    entry.touch()          // 更新最后访问时间
    stats.hit()
    return entry.value
```

#### 定期删除（Periodic Expiration）

```
ScheduledExecutorService.scheduleAtFixedRate(10s):
    keys = randomSample(store.keySet(), 20)   // 随机选 20 个
    for key in keys:
        entry = store.get(key)
        if entry != null && entry.isExpired():
            store.remove(key)
```

**设计决策：**
- 为什么不用 `cleaner.schedule()` 按 key 做定时任务？→ key 多时线程爆炸
- 为什么不全量扫描？→ O(n) 遍历在数十万 key 下会卡主线程

### 3.5 并发模型

```
ConcurrentHashMap<String, CacheEntry<Object>>  // 线程安全的存储
AtomicLong hits, misses                        // 无锁计数器
ReadWriteLock 不采用                           // CHM 自带分段锁，够用
```

**线程安全分析：**

| 操作 | 安全性分析 |
|------|-----------|
| put() | CHM.put() 原子操作，覆盖安全 |
| get() | CHM.get() + CHM.remove() 是两步，存在并发读同一个过期键时重复 remove 的可能（无害） |
| del() | CHM.remove() 原子 |
| size() | CHM.size() 近似值（CHM 特性），满足统计需求 |
| 惰性删除 | 先判断后删除的 TOCTOU 问题：极端情况两个线程同时发现过期，都 remove（无害） |

### 3.6 快照持久化

#### 文件格式

```json
{"key":"name","value":"\"凯健\"","expireAt":1747486123456}
{"key":"count","value":"42","expireAt":-1}
```

- 每行一个 JSON 对象，字符编码 UTF-8
- 用 `BufferedWriter` 逐行写入

#### 原子写入流程

```
save():
    1. 遍历 store.entrySet()，跳过已过期 entry
    2. 写入 snapshot.tmp 文件
    3. 如果是首次写入，创建 snapshot.data
    4. Files.move(snapshot.tmp, snapshot.data, REPLACE_EXISTING)  // 原子 rename
    
load():
    1. 检查 snapshot.data 是否存在
    2. 逐行读取，每行解析 JSON
    3. 反序列化 value（String → Object）
    4. 跳过已过期的 entry
    5. 将有效 entry 加入 store
```

#### 容错机制

```
load() 异常处理:
    try:
        打开文件，逐行解析
    catch 文件不存在:
        记录 INFO 日志，返回（新实例正常行为）
    catch JSON 解析异常:
        记录 ERROR 日志，跳过当前行，继续解析
    catch 其他异常:
        记录 ERROR 日志，停止加载，已加载的数据不丢弃
```

---

## 4. 服务端设计

### 4.1 REST API 契约

端口：**8110**

| 方法 | 路径 | Content-Type | 请求体 | 成功响应 | 失败响应 |
|------|------|-------------|--------|---------|---------|
| PUT | `/cache/{key}` | application/json | `{"value": <any>, "ttl": <long>}` | 200 `{"ok":true}` | 500 |
| GET | `/cache/{key}` | — | — | 200 `{"key":"xx","value":<any>}` | 404 `{"error":"not found"}` |
| DELETE | `/cache/{key}` | — | — | 200 `{"ok":true}` | 404 `{"error":"not found"}` |
| GET | `/cache/stats` | — | — | 200 `{"hits":N,"misses":N,"size":N,"hitRate":0.0}` | 500 |
| POST | `/cache/save` | — | — | 200 `{"ok":true,"path":"…"}` | 500 |

### 4.2 启动流程

```
Spring Boot 启动:
    1. CacheAutoConfig 实例化 CacheEngine Bean
    2. 调用 engine.load() 加载快照
    3. 启动 ExpireManager 定期删除线程
    4. 注册 JVM ShutdownHook → engine.save() → engine.shutdown()
    5. 记录启动日志（INFO：loaded N keys, port 8110）
```

---

## 5. 客户端设计

### 5.1 LightCacheClient

```java
public class LightCacheClient implements CacheEngine {
    private final RestTemplate restTemplate;
    private final String baseUrl;  // e.g. "http://localhost:8110"

    // 每个方法通过 HTTP 调用 server
    public void put(String key, Object value, long ttlSeconds) { ... }
    public Object get(String key) { ... }
    // ... 其余方法类似
}
```

**注意：** `LightCacheClient` 实现 `CacheEngine` 接口，但 `save()` / `load()` / `clear()` 不适用（或调用 server 对应端点）。

---

## 6. 数据流

### 6.1 完整读流程

```
Client HTTP GET /cache/user:1
    │
    ▼
CacheController.get("user:1")
    │
    ▼
CacheEngine.get("user:1")
    │
    ▼
DefaultCacheEngine:
    ├─ store.get("user:1")        → CacheEntry
    ├─ entry.isExpired()?         → expired → store.remove() → return null
    │                              → valid   → entry.touch() → return value
    └─ stats.hits++ / stats.misses++
    │
    ▼
CacheController 返回 ResponseEntity
```

### 6.2 完整写流程

```
Client HTTP PUT /cache/user:1 {"value":"凯健","ttl":300}
    │
    ▼
CacheController.put("user:1", PutRequest)
    │
    ▼
CacheEngine.put("user:1", "凯健", 300)
    │
    ▼
DefaultCacheEngine:
    └─ store.put("user:1", new CacheEntry("凯健", now + 300s))
    │
    ▼
CacheController 返回 200 OK
```

### 6.3 持久化流程

```
触发方式：手动 POST /cache/save 或 ShutdownHook
    │
    ▼
SnapshotManager.save()
    ├─ 遍历 store.entrySet()
    ├─ 跳过 entry.isExpired() == true
    ├─ 序列化为 JSON 行
    ├─ 写入 snapshot.tmp
    └─ Files.move(tmp → data, ATOMIC_MOVE)
```

---

## 7. 类图概览

```
┌─────────────────────┐         ┌──────────────────┐
│   CacheEngine       │         │  CacheEntry<V>   │
│   <<interface>>     │         ├──────────────────┤
├─────────────────────┤         │ +value: V        │
│ +put(k, v, ttl)     │         │ +expireAt: long  │
│ +get(k): Object     │         │ +createTime: long│
│ +del(k)             │         │ +lastAccessTime  │
│ +exists(k): boolean │         │ +isExpired()     │
│ +ttl(k): long       │         │ +touch()         │
│ +size(): long       │         └──────────────────┘
│ +clear()            │
│ +stats(): Map       │         ┌──────────────────┐
│ +save()             │         │  CacheConfig     │
│ +load()             │         ├──────────────────┤
└─────────┬───────────┘         │ +periodicInterval │
          │                     │ +sampleSize: int │
          ▼                     │ +snapshotPath    │
┌─────────────────────┐         │ +statsEnabled    │
│ DefaultCacheEngine  │         └──────────────────┘
├─────────────────────┤
│ -store: CHM         │────────── owns ──────┐
│ -expireManager      │                      │
│ -statsCollector     │         ┌────────────▼──────┐
│ -snapshotManager    │         │  ExpireManager    │
│ -config             │         ├───────────────────┤
└─────────────────────┘         │ +lazyDelete(k)    │
                                │ +start()          │
        owns ──────┐            │ +shutdown()       │
                   │            └───────────────────┘
       ┌───────────▼───────┐    ┌───────────────────┐
       │  StatsCollector   │    │  SnapshotManager  │
       ├───────────────────┤    ├───────────────────┤
       │ -hits: AtomicLong │    │ +save()           │
       │ -misses: AtomicLong│   │ +load()           │
       │ +hit()            │    │ -atomicWrite()    │
       │ +miss()           │    └───────────────────┘
       │ +hitRate(): double│
       └───────────────────┘
```

---

## 8. 关键设计决策记录 (ADR)

| 编号 | 决策项 | 选择 | 理由 |
|------|--------|------|------|
| ADR-01 | 存储结构 | ConcurrentHashMap | JDK 内置，分段锁，无需引入外部依赖 |
| ADR-02 | 过期策略 | 惰性+定期组合 | 惰性保证读时不返回过期数据；定期兜底清理"被遗忘"的过期键 |
| ADR-03 | 快照格式 | JSON Lines | 人类可读、逐行容错、Jackson 成熟 |
| ADR-04 | 原子写入 | 临时文件 + rename | POSIX rename 是原子操作，crash 后半成品文件不影响原数据 |
| ADR-05 | 序列化 | Jackson JSON | 生态成熟，Spring Boot 内置，JSON 可读性好 |
| ADR-06 | HTTP 框架 | Spring Boot 3.4.3 | 满足约束，内置 Tomcat，自动装配 |
| ADR-07 | size() 语义 | 近似值 | CHM.size() 弱一致，日志/统计场景可接受 |

---

*本文档为 Phase 2 交付物。与 SRS v1.1 对齐，作为 Phase 3 编码的基准。*
