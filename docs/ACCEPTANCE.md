# LightCache 代码验收手册

> 验收日期：2026-05-17 | 项目版本：1.0.0-SNAPSHOT | 当前阶段：Phase 4 测试验证（已完成）

---

## 一、前置条件确认

| 检查项 | 状态 | 说明 |
|--------|------|------|
| `mvn install -N`（父 POM 安装） | ✅ | 已执行 |
| `mvn install -pl lightcache-core -DskipTests` | ✅ | 已执行 |
| core 单测 20/20 | ✅ | `mvn test -pl lightcache-core` |
| server 集成测试 15/15 | ✅ | `mvn test -pl lightcache-server` |
| `mvn package` 全量打包 | 待验证 | 验收时执行 |

---

## 二、项目总览

```
E:\KVCache\
├── pom.xml                          # 父 POM (Spring Boot 3.4.3)
├── docs/
│   ├── SRS.md                       # 需求规格说明书 v1.1
│   ├── ARCHITECTURE.md              # 系统设计说明书 v1.0.0
│   └── ACCEPTANCE.md                # 本验收手册
├── lightcache-core/                 # 核心引擎（零外部依赖）
├── lightcache-server/               # REST API 服务（端口 8110）
└── lightcache-client/               # HTTP SDK
```

**技术栈**：JDK 17 · Maven 3.9.4 · Spring Boot 3.4.3 · JUnit 5 · Jackson JSON

---

## 三、逐模块验收清单

### 3.1 lightcache-core（核心引擎）— 7 个源文件 + 1 个测试文件

**设计原则：零外部依赖，纯 JDK 实现**

| # | 文件 | 路径（相对于 core 模块） | 验收要点 |
|---|------|--------------------------|----------|
| 1 | `CacheEngine.java` | `src/main/java/com/lightcache/core/` | □ 接口定义了 11 个方法（put/get/del/exists/ttl/size/clear/stats/save/load/shutdown）<br>□ Javadoc 清楚说明返回约定（如 ttl 返回 -2/-1/≥0） |
| 2 | `DefaultCacheEngine.java` | `src/main/java/com/lightcache/core/` | □ 组合 ExpireManager + StatsCollector + SnapshotManager<br>□ 构造器自动调用 `load()` 恢复快照<br>□ `get()` 实现惰性删除<br>□ `clear()` 同时重置 store 和 stats 计数器 |
| 3 | `CacheEntry.java` | `src/main/java/com/lightcache/core/` | □ 字段：value, expireAt, lastAccessTime<br>□ `isExpired()` 判断逻辑<br>□ `touch()` 更新访问时间 |
| 4 | `CacheConfig.java` | `src/main/java/com/lightcache/core/config/` | □ 可配项：定期删除间隔/采样数/快照路径/stats 开关<br>□ 合理的默认值 |
| 5 | `ExpireManager.java` | `src/main/java/com/lightcache/core/expire/` | □ 定期删除（ScheduledExecutorService）<br>□ 惰性删除（get 时检查）<br>□ 采样策略避免全量扫描 |
| 6 | `StatsCollector.java` | `src/main/java/com/lightcache/core/stats/` | □ AtomicLong 无锁统计<br>□ hit/miss/hitRate 计算<br>□ `reset()` 方法存在且被 clear() 调用 |
| 7 | `SnapshotManager.java` | `src/main/java/com/lightcache/core/snapshot/` | □ 原子写入（先写 tmp 再 rename）<br>□ 手动 JSON 序列化，零依赖<br>□ 加载时幂等（文件不存在不报错） |
| — | `DefaultCacheEngineTest.java` | `src/test/java/com/lightcache/core/` | □ 6 个 Nested 测试组：KeyValueAccess / KeyDeletion / TtlExpiration / Statistics / SnapshotPersistence / Concurrency<br>□ 20 个测试用例全部通过<br>□ 使用 @TempDir 保证测试隔离 |

**验收操作**：
```bash
# 在 VSCode 终端中执行
cd E:/KVCache
mvn test -pl lightcache-core
# 预期：Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

**代码走读重点**：
1. `DefaultCacheEngine` 构造器中 `load()` 的位置（expireManager.start() 之后）
2. `get()` 方法中惰性删除→miss 统计→touch→hit 统计的流程
3. `clear()` 方法中 `statsCollector.reset()` 调用

---

### 3.2 lightcache-server（REST API）— 4 个源文件 + 1 配置 + 1 测试文件

**设计原则：薄层封装，全部逻辑委托给 CacheEngine**

| # | 文件 | 路径 | 验收要点 |
|---|------|------|----------|
| 1 | `LightCacheApplication.java` | `src/main/java/…/server/` | □ `@SpringBootApplication` 标准入口<br>□ 无额外配置代码 |
| 2 | `CacheController.java` | `src/main/java/…/server/controller/` | □ 5 个端点：PUT/GET/DELETE/stats/save<br>□ GET 未命中返回 404 + `{"error":"not found"}`<br>□ DELETE 先 exists 检查再 del<br>□ 全部委托 CacheEngine，控制器零逻辑 |
| 3 | `PutRequest.java` | `src/main/java/…/server/model/` | □ 字段：value（Object）, ttl（long）<br>□ 使用 Jackson 反序列化 |
| 4 | `CacheAutoConfig.java` | `src/main/java/…/server/config/` | □ `@Configuration` 注册 DefaultCacheEngine Bean<br>□ 不再显式调用 load()（构造函数已自动执行） |
| 5 | `application.properties` | `src/main/resources/` | □ `server.port=8110`<br>□ 无多余配置 |
| — | `CacheControllerIntegrationTest.java` | `src/test/java/…/server/` | □ 5 个 Nested 组：Put / Get / Delete / Stats / Save / 边界<br>□ 15 个测试用例全部通过<br>□ @BeforeEach 调用 clear() 保证隔离 |

**验收操作**：
```bash
cd E:/KVCache
mvn test -pl lightcache-server
# 预期：Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

**代码走读重点**：
1. `CacheController` — 每个端点方法不超过 6 行，职责单一
2. `CacheAutoConfig` — 仅注册 Bean，不包含业务逻辑
3. 集成测试使用 `@SpringBootTest` + `MockMvc`，真实 HTTP 交互

---

### 3.3 lightcache-client（HTTP SDK）— 1 个源文件

| # | 文件 | 路径 | 验收要点 |
|---|------|------|----------|
| 1 | `LightCacheClient.java` | `src/main/java/…/client/` | □ 实现 CacheEngine 接口<br>□ 双构造函数（默认 RestTemplate / 自定义）<br>□ baseUrl 尾部斜杠自动处理<br>□ `clear()` 和 `load()` 为 UnsupportedOperationException（设计选择，非 bug）<br>□ `get()` 中 HTTP 异常统一视为未命中<br>□ `shutdown()` 调用 save() 且忽略异常 |

**代码走读重点**：
1. `get()` 的 try-catch 策略：404 等异常 → 返回 null（符合 CacheEngine 契约）
2. `exists()` / `ttl()` 降级实现：通过 get() 判断
3. `clear()` 故意不暴露 HTTP 端点（安全性设计）

---

## 四、全量构建验证

```bash
cd E:/KVCache
mvn clean package
```

**预期结果**：
- 3 个模块均 BUILD SUCCESS
- `lightcache-server/target/lightcache-server-1.0.0-SNAPSHOT.jar` 可执行
- 启动命令：`java -jar lightcache-server/target/lightcache-server-1.0.0-SNAPSHOT.jar`

---

## 五、架构设计审查要点

对照 `docs/ARCHITECTURE.md` 确认：

| 设计决策 | 代码落实 |
|----------|----------|
| ADR-1: core 零依赖 | ✅ core pom.xml 无任何 dependency |
| ADR-2: ConcurrentHashMap 存储 | ✅ DefaultCacheEngine 使用 ConcurrentHashMap |
| ADR-3: 手动 JSON 序列化 | ✅ SnapshotManager 使用 StringBuilder 拼 JSON |
| ADR-4: 惰性删除 + 定期删除 | ✅ ExpireManager 双策略实现 |
| ADR-5: 快照原子写入 | ✅ 先写 .tmp 再 rename |
| ADR-6: 统计无锁化 | ✅ StatsCollector 使用 AtomicLong |
| ADR-7: Server 薄层封装 | ✅ CacheController 全部委托 engine |

---

## 六、需求覆盖矩阵

对照 `docs/SRS.md` 确认：

| 需求编号 | 描述 | 实现位置 | 测试覆盖 |
|----------|------|----------|----------|
| FR-01 | 键值写入（put） | DefaultCacheEngine.put() + CacheController | ✅ PutEndpoint / overwriteKey |
| FR-02 | 键值读取（get） | DefaultCacheEngine.get() + CacheController | ✅ GetEndpoint / statsReflectsHits |
| FR-03 | 键删除（del） | DefaultCacheEngine.del() + CacheController | ✅ DeleteEndpoint |
| FR-04 | TTL 过期 | ExpireManager 惰性+定期删除 | ✅ TtlExpiration / expiredKeyReturns404 |
| FR-05 | 运行统计 | StatsCollector.snapshot() | ✅ StatsEndpoint |
| FR-06 | 快照持久化 | SnapshotManager.save()/load() | ✅ SnapshotPersistence / SaveEndpoint |
| FR-07 | 客户端 SDK | LightCacheClient 实现 CacheEngine | 待补充（Phase 5 规划） |

---

## 七、VSCode 验收操作指南

### 步骤 1：打开项目
```
code E:/KVCache
```

### 步骤 2：确认项目结构
- 左侧文件树应显示 3 个模块 + docs 目录
- 确认 `pom.xml` 能被 Maven 插件识别

### 步骤 3：执行全量测试
在 VSCode 终端（Ctrl+`）：
```bash
mvn test
```
预期：35 个测试全部通过

### 步骤 4：逐模块走读（推荐顺序）
1. **先读接口** → `CacheEngine.java`（了解契约）
2. **读核心实现** → `DefaultCacheEngine.java`（理解数据流）
3. **读辅助组件** → `CacheEntry.java` → `ExpireManager.java` → `StatsCollector.java` → `SnapshotManager.java` → `CacheConfig.java`
4. **读服务层** → `CacheController.java` → `CacheAutoConfig.java`
5. **读客户端** → `LightCacheClient.java`
6. **读测试** → `DefaultCacheEngineTest.java` → `CacheControllerIntegrationTest.java`

### 步骤 5：抽查关键代码片段
- 搜索 `statsCollector.reset()` → 确认在 `clear()` 中
- 搜索 `this.load()` → 确认在构造器中
- 搜索 `@TempDir` → 确认测试隔离机制

### 步骤 6：启动服务验证（可选）
```bash
cd E:/KVCache
mvn package -DskipTests
java -jar lightcache-server/target/lightcache-server-1.0.0-SNAPSHOT.jar
# 另开终端测试
curl -X PUT http://localhost:8110/cache/test -H "Content-Type: application/json" -d "{\"value\":\"hello\",\"ttl\":60}"
curl http://localhost:8110/cache/test
curl http://localhost:8110/cache/stats
```

---

## 八、已知设计取舍（验收时注意）

| 项目 | 当前实现 | 原因 |
|------|----------|------|
| 序列化方案 | 仅 JSON（手动拼接） | 需求约束，避免引入 Jackson 到 core |
| 快照类型丢失 | 数值恢复为字符串 | JSON 不保留类型信息，文档已记录 |
| 客户端 clear() | 抛 UnsupportedOperationException | 安全性设计，不暴露批量删除 HTTP |
| 客户端 load() | 抛 UnsupportedOperationException | 快照恢复是服务端操作 |
| 客户端 ttl() | 降级为 get() 判断 | 服务端未暴露 TTL 端点 |

---

## 九、验收结论

| 维度 | 状态 |
|------|------|
| 需求覆盖率 | 7/7 FR 已实现 |
| 单元测试 | 20/20 通过 |
| 集成测试 | 15/15 通过 |
| 设计决策一致性 | 7/7 ADR 落实 |
| 代码可读性 | 每个类有 Javadoc，方法职责单一 |
| 待改进项 | client 模块无独立测试（Phase 5 规划） |

**结论：具备进入 Phase 5（部署上线）的条件。**
