# LightCache

> 轻量级键值缓存中间件 — 亲手实现，理解缓存核心机制。

[![JDK](https://img.shields.io/badge/JDK-17-blue)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-green)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.9.4-c71a36)](https://maven.apache.org/)
[![Tests](https://img.shields.io/badge/tests-35%2F35%20passed-brightgreen)](https://github.com/xhaxx/KVCache)

---

## 项目定位

LightCache 是面向**学习与原型验证**场景的单机键值缓存中间件。不追求生产级高可用，但要求接口规范、代码可读、可测试。通过亲手实现 put/get/del/TTL 过期、快照持久化、命中率统计等机制，理解 Redis 等缓存中间件的核心设计。

## 架构

```
┌────────────────────────────────────────────────┐
│                LightCache System                │
├────────────────────────────────────────────────┤
│                                                │
│  ┌──────────────────┐   ┌───────────────────┐  │
│  │ lightcache-client│   │ lightcache-server  │  │
│  │  (Java SDK)      │──>│  (Spring Boot)     │  │
│  │  RestTemplate    │   │  Port 8110         │  │
│  └──────────────────┘   └────────┬──────────┘  │
│                                  │              │
│                                  ▼              │
│                        ┌───────────────────┐   │
│                        │ lightcache-core    │   │
│                        │  (Zero Dependency) │   │
│                        │                    │   │
│                        │  CacheEngine       │   │
│                        │  ExpireManager     │   │
│                        │  StatsCollector    │   │
│                        │  SnapshotManager   │   │
│                        └───────────────────┘   │
└────────────────────────────────────────────────┘
```

**核心原则**：Core 模块零外部依赖（纯 JDK 17） → Server 薄层封装 → Client 即用 SDK

## 快速开始

### 构建

```bash
git clone https://github.com/xhaxx/KVCache.git
cd KVCache
mvn clean package -DskipTests
```

### 启动服务

```bash
java -jar lightcache-server/target/lightcache-server-1.0.0-SNAPSHOT.jar
```

### 使用

```bash
# 写入
curl -X PUT http://localhost:8110/cache/name \
  -H "Content-Type: application/json" \
  -d '{"value":"凯健","ttl":300}'

# 读取
curl http://localhost:8110/cache/name
# → {"key":"name","value":"凯健"}

# 统计
curl http://localhost:8110/cache/stats
# → {"hits":1,"misses":0,"size":1,"hitRate":1.0}

# 手动快照
curl -X POST http://localhost:8110/cache/save
```

### SDK 方式

```java
CacheEngine client = new LightCacheClient("http://localhost:8110");
client.put("foo", "bar", 60);
Object value = client.get("foo");  // "bar"
Map<String, Object> stats = client.stats();
```

## API 端点

| 方法 | 路径 | 说明 | 成功 | 失败 |
|------|------|------|------|------|
| `PUT` | `/cache/{key}` | 写入/覆盖 | 200 `{"ok":true}` | — |
| `GET` | `/cache/{key}` | 读取 | 200 `{"key":"…","value":…}` | 404 |
| `DELETE` | `/cache/{key}` | 删除 | 200 `{"ok":true}` | 404 |
| `GET` | `/cache/stats` | 运行统计 | 200 `{hits,misses,size,hitRate}` | — |
| `POST` | `/cache/save` | 手动快照 | 200 `{"ok":true}` | — |

## 模块

| 模块 | 职责 | 依赖 |
|------|------|------|
| `lightcache-core` | 缓存引擎、过期管理、统计、快照 | 零外部依赖 |
| `lightcache-server` | Spring Boot REST API，端口 8110 | core |
| `lightcache-client` | Java SDK，基于 RestTemplate | core |

## 已实现特性

- **键值存取** — put / get / del / exists / ttl / size / clear
- **TTL 过期** — 惰性删除（读时检查）+ 定期删除（后台线程采样）
- **命中率统计** — AtomicLong 无锁并发安全，hit / miss / hitRate
- **快照持久化** — 原子写入（先写 .tmp 再 rename），启动自动恢复
- **线程安全** — ConcurrentHashMap + AtomicLong，全无锁设计
- **统计隔离** — clear() 同时重置 store 和 stats 计数器

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Apache Maven | 3.9.4 |
| Spring Boot | 3.4.3 |
| JUnit 5 | (via Spring Boot) |
| Jackson | (via Spring Boot Starter Web) |

## 测试

```bash
mvn test
```

| 测试集 | 用例 | 覆盖 |
|--------|------|------|
| `DefaultCacheEngineTest` | 20 | 存取、过期、删除、统计、快照、并发 |
| `CacheControllerIntegrationTest` | 15 | PUT/GET/DELETE/stats/save + 边界场景 |
| **合计** | **35/35 通过** | |

## 文档

- [需求规格说明书 (SRS)](docs/SRS.md)
- [系统设计说明书](docs/ARCHITECTURE.md)
- [代码验收手册](docs/ACCEPTANCE.md)

## 开发阶段

| 阶段 | 状态 |
|------|------|
| Phase 1 — 需求分析 | ✅ SRS v1.1 |
| Phase 2 — 规划设计 | ✅ 架构 v1.0.0 |
| Phase 3 — 开发编码 | ✅ 3 模块完整实现 |
| Phase 4 — 测试验证 | ✅ 35/35 通过 |
| Phase 5 — 部署上线 | 🔲 待推进 |

## License

MIT
