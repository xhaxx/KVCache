# Phase A 需求规格说明书

> **所属项目**：LightCache 提升路线图 — Phase A  
> **版本**：v1.0.0  
> **日期**：2026-05-19  
> **状态**：待确认  
> **依赖**：ROADMAP.md 确定的 Phase A 范围

---

## 1. 引言

### 1.1 背景

LightCache 当前已实现基础键值缓存（String 类型 + HTTP REST API + 手动 JSON 快照），但对照 Redis 存在三处架构级/功能级差距。Phase A 的目标是补齐最具学习价值的 3 项能力，让 LightCache 从"HTTP 键值服务"升级为"支持多种类型、有 WAL 持久化、可用 redis-cli 操作的缓存系统"。

### 1.2 范围

本阶段仅包含以下 3 项：

| 编号 | 模块 | 描述 | 优先级 |
|------|------|------|--------|
| PA-01 | AOF 增量持久化 | 每次写操作追加日志到 `.aof` 文件，启动时重放恢复 | P0 |
| PA-02 | Hash + List 数据结构 | 新增 HSET/HGET/LPUSH/RPUSH 等命令，丰富类型系统 | P0 |
| PA-03 | RESP 协议 Server | 在现有 server 模块增加 Java NIO Server，解析 RESP 协议，兼容 redis-cli | P1 |

### 1.3 排他声明

本阶段**不包括**以下内容（留待 Phase B+）：
- Set、ZSet、Stream 数据结构
- LRU/LFU 淘汰策略
- 主从复制、Sentinel、Cluster
- MULTI/EXEC 事务、Lua 脚本
- PUB/SUB
- TLS/ACL 安全功能

---

## 2. 功能需求

### PA-01：AOF 增量持久化

#### FR-A01-1 命令追加写入
- 每次 `put` 或 `del` 操作完成后，将操作序列化为一行命令追加写入 AOF 文件
- 格式为 RESP 或 JSON Lines（设计阶段决策）
- 写入后调用 fsync（可配置 always / everysec / no）

#### FR-A01-2 AOF 文件管理
- AOF 文件路径可配置，默认 `./data/lightcache.aof`
- 写入采用追加模式（append-only），不覆盖已有内容

#### FR-A01-3 启动自动恢复
- 服务启动时检测 AOF 文件是否存在
- 若存在，逐行读取并重放命令，恢复到内存
- 重放过程中跳过硬解析的行（单行损坏不阻塞整体恢复）
- 恢复完成后记录恢复的键数

#### FR-A01-4 AOF 重写（基本版）
- 提供 `BGREWRITEAOF` 命令或 `save` 端点触发
- 重写逻辑：遍历当前全部有效键，生成一份紧凑的 AOF 文件
- 重写采用 tmp + rename 原子模式（复用现有 SnapshotManager 原子写入逻辑）

#### FR-A01-5 配置项
- `aof-enabled`：是否启用 AOF，默认 true
- `aof-fsync`：fsync 策略，可选 `always` / `everysec` / `no`，默认 `everysec`
- `aof-path`：AOF 文件路径，默认 `./data/lightcache.aof`

---

### PA-02：Hash + List 数据结构

#### FR-A02-1 Hash 类型支持
命令集合：

| 命令 | 说明 | 返回值约定 |
|------|------|-----------|
| `HSET key field value` | 设置 hash 字段 | 返回 1（新字段）或 0（覆盖） |
| `HGET key field` | 获取字段值 | 命中返回值，未命中 null |
| `HDEL key field` | 删除字段 | 返回 1（成功）或 0（不存在） |
| `HEXISTS key field` | 判断字段存在 | 返回 1 或 0 |
| `HGETALL key` | 获取全部字段值 | 返回 Map |
| `HKEYS key` | 获取全部字段名 | 返回 List |
| `HVALS key` | 获取全部字段值 | 返回 List |
| `HLEN key` | 获取字段数量 | 返回 int |

- Hash 内部存储使用 `ConcurrentHashMap<String, String>`，挂载到顶层 key 下
- Hash 整体可设置 TTL（与 String 共用过期机制）

#### FR-A02-2 List 类型支持
命令集合：

| 命令 | 说明 | 返回值约定 |
|------|------|-----------|
| `LPUSH key value [value...]` | 左侧插入 | 返回插入后长度 |
| `RPUSH key value [value...]` | 右侧插入 | 返回插入后长度 |
| `LPOP key` | 左侧弹出 | 返回弹出值或 null |
| `RPOP key` | 右侧弹出 | 返回弹出值或 null |
| `LRANGE key start stop` | 范围查询 | 返回截取的 List |
| `LLEN key` | 获取长度 | 返回 int |

- List 内部存储使用 `LinkedList<String>`（简化版，不做 quicklist 优化）
- List 整体可设置 TTL

#### FR-A02-3 类型系统重构
- 顶层 `CacheEntry` 增加 `type` 字段：`STRING` / `HASH` / `LIST`
- `CacheEngine.get(key)` 对非 String 类型的行为：返回字符串表示或 null
- 类型冲突检测：put String → put Hash 同名 key 应报错或覆盖

#### FR-A02-4 API 端点扩展（Server 层）
- 新增 REST 端点对应 Hash/List 操作，或在新 RESP Server 中原生支持（见 PA-03）
- 决策：Hash/List 命令**优先在 RESP Server 中实现**，HTTP API 作为辅助封装

---

### PA-03：RESP 协议 Server

#### FR-A03-1 RESP 解析器
- 实现 RESP2 协议的完整解析：
  - Simple String (`+OK\r\n`)
  - Error (`-ERR message\r\n`)
  - Integer (`:1\r\n`)
  - Bulk String (`$5\r\nhello\r\n`)
  - Array (`*3\r\n...`)
  - Null Bulk String (`$-1\r\n`)
  - Null Array (`*-1\r\n`)
- 解析器为无状态工具类，输入字节流，输出结构化请求对象

#### FR-A03-2 NIO Server
- 基于 `java.nio.channels.Selector` 实现单线程事件循环
- 监听端口 6379（可配置，独立于端口 8110 的 HTTP Server）
- 连接管理：接收新连接 → 读取数据 → 解析 RESP → 执行命令 → 编码 RESP 响应
- 支持并发多连接（单线程轮询）
- 支持 `QUIT` 命令关闭连接

#### FR-A03-3 命令路由
- 命令解析后路由到 CacheEngine 的对应方法
- 命令表设计为可扩展的注册机制（Map<String, CommandHandler>）
- 支持的命令集合：
  - 基础键值：`SET key value [EX seconds]`、`GET key`、`DEL key`、`EXISTS key`、`TTL key`
  - Hash：`HSET`、`HGET`、`HDEL`、`HEXISTS`、`HGETALL`、`HKEYS`、`HVALS`、`HLEN`
  - List：`LPUSH`、`RPUSH`、`LPOP`、`RPOP`、`LRANGE`、`LLEN`
  - 管理：`PING`、`QUIT`、`COMMAND`、`SAVE`、`BGREWRITEAOF`
  - 统计：`INFO`→stats、`DBSIZE`→size

#### FR-A03-4 redis-cli 兼容性
- 可以使用标准 `redis-cli -p 6379` 直连
- `SET`、`GET`、`PING` 等基础命令行为与 Redis 一致
- 对于不支持的命令（如 `CONFIG`、`CLUSTER` 等），返回 `-ERR unknown command`

#### FR-A03-5 统计与监控
- `INFO` 命令返回与 HTTP stats 一致的信息
- 连接数统计、命令执行计数

---

## 3. 非功能需求

### 3.1 性能
| 指标 | 目标值 |
|------|--------|
| AOF `everysec` 写入延迟 | < 2ms（相对于无 AOF） |
| RESP Server 单连接吞吐 | > 5,000 QPS（本地 loopback） |
| 恢复 10,000 键的 AOF | < 3 秒 |

### 3.2 可靠性
- AOF 重写过程中系统崩溃，原 AOF 文件不受影响（tmp+rename）
- AOF 损坏行跳过不阻塞整体恢复

### 3.3 兼容性
- RESP Server 可与现有 HTTP Server 并存，互不干扰
- Hash/List 的 TTL 过期机制复用现有 ExpireManager

### 3.4 可维护性
- 命令注册机制支持后续 Phase 快速添加新命令
- AOF 解析器与命令执行器解耦

---

## 4. 需求优先级矩阵

| 编号 | 功能 | 优先级 | 依赖 |
|------|------|--------|------|
| PA-03 | RESP Server 基础框架 | P0（先行） | 无 |
| PA-02 | Hash + List 类型系统 | P0 | PA-03（命令入口） |
| PA-01 | AOF 增量持久化 | P1（后行） | PA-02（类型系统稳定后 AOF 才能记录所有命令） |

**执行顺序建议**：PA-03（RESP） → PA-02（类型系统） → PA-01（AOF）

---

## 5. 验收标准

### PA-03 验收
- [ ] `redis-cli -p 6379` 可连接并执行 PING→PONG
- [ ] SET/GET/DEL/EXISTS/TTL 基础命令正常
- [ ] 多次连接并发操作无问题
- [ ] 不支持的命令返回 `-ERR unknown command`

### PA-02 验收
- [ ] HSET/HGET/HDEL/HEXISTS/HGETALL/HKEYS/HVALS/HLEN 全部可用
- [ ] LPUSH/RPUSH/LPOP/RPOP/LRANGE/LLEN 全部可用
- [ ] Hash key 设置 TTL 后可正常过期删除
- [ ] 类型冲突检测正常（String key 上执行 HSET 应报错）

### PA-01 验收
- [ ] everysec 模式下写入 put/del 操作可见于 AOF 文件
- [ ] 重启服务后 AOF 重放恢复数据正确
- [ ] AOF 损坏行被跳过，剩余行正常加载
- [ ] BGREWRITEAOF 后 AOF 文件大小为当前数据量级
- [ ] 重写过程崩溃原文件不受影响
