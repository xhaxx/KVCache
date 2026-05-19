# LightCache vs Redis 差距对比 & 提升路线图

> 版本：v1.0.0 | 日期：2026-05-19 | 阶段：Phase 4 之后

---

## 一、差距总览

| 维度 | LightCache (当前) | Redis 7.x | 差距等级 |
|------|-------------------|-----------|----------|
| 数据结构 | String only（JSON 承载） | String, List, Set, ZSet, Hash, Stream, Geospatial, Bitmap, HyperLogLog | 🔴 核心差距 |
| 持久化 | 手动快照（JSON Lines），原子写入 | RDB 快照 + AOF 日志 + 混合模式，fork/delta 写入 | 🟡 功能差距 |
| 高可用 | 单节点 | 主从复制 + Sentinel 哨兵 + Cluster 分片 | 🔴 架构差距 |
| 网络协议 | HTTP REST（JSON 序列化） | RESP 二进制协议，IO 多路复用 (epoll/kqueue) | 🟡 性能差距 |
| 内存管理 | 无上限，无淘汰策略 | zmalloc/jemalloc，LRU/LFU 淘汰，active defrag | 🟡 功能差距 |
| 过期策略 | 惰性 + 固定间隔定期采样 | 惰性 + 自适应定期删除（activeExpireCycle 动态调速） | 🟢 接近 |
| 事务 | 无 | MULTI/EXEC/WATCH，原子性批量操作 | 🟡 功能差距 |
| Lua 脚本 | 无 | Lua 5.1 引擎，原子脚本执行 | 🟡 功能差距 |
| 发布订阅 | 无 | PUBLISH/SUBSCRIBE，模式匹配，Sharded Pub/Sub | 🟡 功能差距 |
| Pipeline | 无（HTTP/1.1 串行） | 批量命令管道，减少 RTT | 🟡 性能差距 |
| 安全 | 无 | ACL 用户体系，TLS 加密，rename-command | 🟡 功能差距 |
| 监控 | hits/misses/size/hitRate | INFO 全量指标，SLOWLOG 慢查询，MONITOR 实时监控，LATENCY | 🟡 功能差距 |
| 键空间通知 | 无 | 键过期/删除/修改事件推送 | 🟢 可选 |
| 模块系统 | 无 | Redis Modules API（RediSearch, RedisJSON 等） | 🟢 可选 |
| 性能 | 单线程 HTTP 处理 | 单线程事件驱动 + IO 多路复用，10W+ QPS | 🔴 量级差距 |

> **等级说明**：🔴 核心差距（架构级） 🟡 功能/性能差距（可渐进补齐） 🟢 接近或可选

---

## 二、逐维度详细对比

### 2.1 数据结构

| | LightCache | Redis |
|------|------------|-------|
| 基础模型 | `Map<String, Object>`，Object 在 JSON 语境下退化为 String/Number/Boolean/null | 每种类型有独立编码实现（ziplist, skiplist, dict, quicklist 等） |
| 能力边界 | GET/SET 语义，无法做列表 push/pop、集合交并 | 完整集合操作（LPUSH, SADD, ZADD, ZRANGEBYSCORE, XADD 等） |
| 内存效率 | JSON 字符串存储，无紧凑编码 | 根据数据量自适应切换内部编码（如 ziplist→skiplist） |
| 序列化开销 | 每次 get 返回 JSON 字符串 | 二进制 RESP，无序列化中间层 |

**根因**：LightCache 将 value 统一视为 `Object` + JSON，相当于 Redis 的 String 类型。要支持数据结构，需要全新设计 type-aware 存储层。

### 2.2 持久化

| | LightCache | Redis |
|------|------------|-------|
| 快照方式 | 全量遍历 store 写入 JSON Lines（`synchronized` 阻塞） | `fork()` 子进程写 RDB，利用 COW 不阻塞主线程 |
| 增量持久化 | 无 | AOF 追加写命令日志，支持 fsync 策略（always/everysec/no） |
| 恢复能力 | 全量恢复，最后一次快照之后的数据丢失 | AOF 可恢复到秒级，RDB+AOF 混合模式兼顾恢复速度和数据完整性 |
| 写入原子性 | `tmp + rename`（文件系统级） | AOF 追加写（append-only）天然原子 |

**根因**：LightCache 只有全量快照，Redis 有 fork + COW 做非阻塞快照 + AOF 做增量。前者受限于 JVM 无法 fork。

### 2.3 高可用

| | LightCache | Redis |
|------|------------|-------|
| 拓扑 | 单节点 | 主从（读写分离）→ Sentinel（自动故障转移）→ Cluster（16384 slot 分片） |
| 数据冗余 | 无 | 从节点异步/半同步复制 |
| 故障恢复 | 手动重启 + 快照恢复 | Sentinel 自动选主，Cluster 自动 failover |
| 扩容 | 无 | Cluster 在线 reshard，slot 迁移 |

**根因**：LightCache 是单机学习项目，未设计复制协议和分布式一致性。

### 2.4 网络协议

| | LightCache | Redis |
|------|------------|-------|
| 协议 | HTTP/1.1 + JSON | RESP（REdis Serialization Protocol），纯二进制/文本混合 |
| 连接模型 | Servlet 容器（Tomcat），每连接一线程 | 单线程事件循环 + IO 多路复用（epoll_wait） |
| 请求开销 | HTTP 头 + JSON 解析 + 序列化 | RESP 极简格式，`*3\r\n$3\r\nSET\r\n...` |
| 并发模型 | Tomcat 线程池（200 线程） ≈ 200 并发 | 单线程处理命令，无锁无竞争 |

**根因**：Spring Boot 默认 Tomcat 是 thread-per-request 模型；Redis 单线程 + epoll 是 cache-friendly 的极致设计。

### 2.5 内存管理

| | LightCache | Redis |
|------|------------|-------|
| 分配器 | JVM 堆（GC 管理） | zmalloc（可对接 jemalloc/tcmalloc），减少碎片 |
| 淘汰策略 | 无（内存无限增长） | noeviction / allkeys-lru / volatile-lfu 等 8 种策略 |
| 过期删除 | 采样 20/10s，固定频率 | `activeExpireCycle` 自适应调速（根据过期键比例加速） |
| 内存回收 | GC 自动，但存在 STW | 主动碎片整理（activedefrag），增量式 |
| 数据压缩 | 无 | listpack 紧凑编码，对短 list/hash 省内存 |

**根因**：JVM GC 与 Redis 手动内存管理哲学不同，淘汰策略可在应用层实现。

### 2.6 事务与原子性

| | LightCache | Redis |
|------|------------|-------|
| 批处理 | 无 | `MULTI/EXEC` 批量执行，`WATCH` 乐观锁 |
| 脚本 | 无 | Lua 脚本原子执行（单线程天然串行） |
| 条件操作 | 无 | `SET NX`、`SET EX` 等原子条件命令 |

### 2.7 监控与可观测性

| | LightCache | Redis |
|------|------------|-------|
| 基础指标 | hits, misses, size, hitRate | `INFO` 包含 server/clients/memory/stats/replication/cpu 等 |
| 慢查询 | 无 | `SLOWLOG` 记录执行时间超阈值的命令 |
| 实时监控 | 无 | `MONITOR` 实时回显所有命令 |
| 延迟诊断 | 无 | `LATENCY` 子命令集，定位延迟尖刺 |

---

## 三、提升计划（分 5 个 Phase）

```
           当前 ──────────────── Phase A ──────────────── Phase B ──────────────── Phase C ──────────────── Phase D
           │                    │                        │                        │                        │
数据结构    String              + Hash + List            + Set + ZSet              + Stream                 + Geospatial/Bitmap
持久化     手动 JSON 快照       增量 AOF                  非阻塞 RDB                AOF 重写                 -
高可用     单节点               RESP 协议                 主从复制                 Sentinel                 Cluster 分片
内存       无淘汰               TTL 精准到期             LRU 淘汰                 LFU 淘汰                内存碎片整理
事务       -                    MULTI/EXEC               WATCH 乐观锁             Lua 脚本                  -
监控       basic stats          SLOWLOG                  INFO 扩展                延迟监控                  -
Pub/Sub    -                    -                        PUB/SUB 基础             模式匹配                  -
```

---

### Phase A：夯实基础（2-3 周）

> **目标**：补充最影响可用性的短板——增量持久化、新数据结构、基本淘汰策略

| 任务 | 内容 | 优先级 | 落点 |
|------|------|--------|------|
| A-1 | **AOF 增量持久化**：每次 put/del 追加写命令日志到 `.aof` 文件，支持 fsync 配置 | 🔴 P0 | core |
| A-2 | **Hash 数据结构**：添加 `HSet/HGet/HDel/HExists/HKeys/HVals` 命令，内部用 ConcurrentHashMap | 🔴 P0 | core |
| A-3 | **List 数据结构**：添加 `LPush/RPush/LPop/RPop/LRange/LLen`，内部用 LinkedList | 🟡 P1 | core |
| A-4 | **TTL 精准过期**：修复 lock-step 固定间隔，改为自适应调速（类似 activeExpireCycle） | 🟡 P1 | core |
| A-5 | **内存上限 + noeviction 淘汰**：`maxmemory` 配置，超限时拒绝写入 | 🟡 P1 | core |
| A-6 | **RESP 协议 Server**：在 server 模块增加 NIO Server，解析 RESP 协议，兼容 `redis-cli` 直连 | 🟡 P1 | server |

**验收标准**：AOF 可回放恢复 → Hash/List 数据结构可用 → `redis-cli` 能连上执行 SET/GET/HSET

---

### Phase B：丰富能力（3-4 周）

> **目标**：补全常用数据类型和基础高可用

| 任务 | 内容 | 优先级 | 落点 |
|------|------|--------|------|
| B-1 | **Set + ZSet**：`SAdd/SMembers/SInter` 等，ZSet 用 `ConcurrentSkipListMap` | 🟡 P1 | core |
| B-2 | **LRU 淘汰**：基于 `LinkedHashMap` access-order 或采样近似 LRU | 🟡 P1 | core |
| B-3 | **MULTI/EXEC 事务**：批量命令入队 + 顺序执行，单线程模型天然隔离 | 🟡 P1 | core |
| B-4 | **WATCH 乐观锁**：监控 key 版本号，EXEC 时检查 | 🟢 P2 | core |
| B-5 | **主从复制**：全量 RDB + 增量命令流，从节点只读 | 🟡 P1 | core/server |
| B-6 | **SLOWLOG**：记录执行时间 > 阈值的命令及调用栈 | 🟢 P2 | core |
| B-7 | **RDB 非阻塞快照**：利用 copy-on-write 思想，快照时标记 store 做增量拷贝（或直接用 AOF 重构） | 🟢 P2 | core |

**验收标准**：ZSet 排序查询可用 → LRU 淘汰生效 → MULTI/EXEC 原子执行 → 主从同步正常

---

### Phase C：向分布式演进（4-6 周）

> **目标**：Sentinel 自动故障转移 + Cluster 分片雏形

| 任务 | 内容 | 优先级 | 落点 |
|------|------|--------|------|
| C-1 | **Sentinel 哨兵**：独立进程，监控主节点、自动选主、通知客户端 | 🟡 P1 | 新模块 |
| C-2 | **LFU 淘汰**：基于访问频率的淘汰，维护对数计数器 | 🟢 P2 | core |
| C-3 | **Lua 脚本**：集成 LuaJ 引擎，原子执行脚本 | 🟢 P2 | core |
| C-4 | **PUB/SUB**：多播通道，订阅者列表管理，`PUBLISH/SUBSCRIBE/UNSUBSCRIBE` | 🟢 P2 | core |
| C-5 | **Cluster 分片（基础）**：16384 slot + CRC16，gossip 协议节点发现 | 🟢 P2 | core/server |
| C-6 | **INFO 命令扩展**：对齐 Redis INFO sections（server/clients/memory/replication 等） | 🟢 P2 | server |

**验收标准**：Sentinel 检测到主宕机自动切换 → PUB/SUB 通道可用 → Cluster 手动 reshard 成功

---

### Phase D：性能与工程化（持续）

> **目标**：缩小性能差距，完善工程化

| 任务 | 内容 | 优先级 |
|------|------|--------|
| D-1 | **IO 多路复用**：用 Java NIO Selector 替代 thread-per-request 模型 | 🔴 P0 |
| D-2 | **内存碎片整理**：定期扫描 + 紧凑存储 | 🟢 P2 |
| D-3 | **ACL 安全**：用户/密码/命令白名单 | 🟢 P2 |
| D-4 | **TLS 加密**：接入 SSL/TLS | 🟢 P2 |
| D-5 | **Benchmark 工具**：对标 `redis-benchmark` | 🟢 P2 |
| D-6 | **Stream 数据结构**：消费组、消息队列语义 | 🟢 P2 |

---

## 四、推荐执行策略

### 策略 A：学习优先（推荐）

只做 **Phase A**，聚焦核心机制的复现价值最大的 3 项：
- A-1 增量 AOF（理解 WAL 思想）
- A-2 Hash 数据结构（理解类型系统设计）
- A-6 RESP 协议 Server（理解协议层抽象）

> 投入 2-3 周，获得持久化、类型系统、协议层三个维度的深度理解。

### 策略 B：能力对齐

做完 **Phase A + Phase B**，使 LightCache 在基本功能上接近 Redis 单机版：
- 完整的数据结构（String/Hash/List/Set/ZSet）
- AOF 持久化 + 主从复制
- LRU 淘汰 + 事务 + 慢查询

> 投入 6-8 周，实现一个"迷你但自洽"的缓存系统。

### 策略 C：野心路线

做完 **A→B→C→D**，完整对标 Redis：
- 分布式集群 + Sentinel
- Lua 脚本 + PUB/SUB
- IO 多路复用 + 安全层

> 投入 20+ 周，产出接近生产可用水平，但性价比需要凯健自己权衡。

---

## 五、技术决策前置

以下决策需要在启动 Phase A 前明确：

| # | 决策点 | 选项 |
|---|--------|------|
| 1 | 类型系统设计：每种类型独立 Map，还是顶层 Map 存 type tag？ | `Map<String, ValueObject>` + ValueObject 含 type enum |
| 2 | AOF 格式：沿用 JSON Lines，还是 RESP 格式？ | 建议 RESP 格式（与协议统一，重放即执行） |
| 3 | RESP Server：在现有 Spring Boot 旁加 NIO Server，还是替换？ | 建议并存：8110 HTTP + 6379 RESP，渐进切换 |
| 4 | 内存上限判断：用 JVM Runtime.freeMemory() 近似，还是手动计？ | 建议手动计数器（不受 GC 波动影响） |

---

## 六、总结

```
LightCache vs Redis 差距总结：

🔴 架构级差距（3 项）：数据结构、高可用、性能模型
🟡 功能级差距（9 项）：持久化、淘汰、事务、脚本、PUB/SUB、安全、监控、Pipeline、协议
🟢 已接近/可选（3 项）：过期策略、键空间通知、模块系统
```

**核心建议**：以 Phase A 为下一阶段目标，用 2-3 周补齐持久化、Hash/List、RESP 协议三项，让 LightCache 成为一个"可以用 redis-cli 操作的、支持多种数据结构的、有 WAL 持久化的缓存系统"——这会让整个项目的完成度和学习价值再上一个台阶。

下一步由凯健决定走哪条策略。
