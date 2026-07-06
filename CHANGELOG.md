# 变更日志 (CHANGELOG)

## [Unreleased]

### 新增

#### 4. 新增 SB3/SB4 测试验证模块（P1）— 2026-07-06

新增两个测试验证模块，分别验证 starter 在 Spring Boot 3.5.x 和 Spring Boot 4.0.x 下的兼容性：
- `conditional-refresh-test-sample-v3/` — Spring Boot 3.5.x + Spring Cloud 2023.0.x + Spring Cloud Alibaba 2023.0.3.0
- `conditional-refresh-test-sample-v4/` — Spring Boot 4.0.x + Spring Cloud 2023.0.x + Spring Cloud Alibaba 2023.0.3.0

**设计**：
- 独立项目（不继承父 POM），各自管理完整的 Spring Boot BOM
- 依赖已发布的 starter 1.0.0 artifact（Java 1.8 编译，通过 javax.annotation.Resource 兼容 SB3+/SB4 类加载器）
- Nacos 连接信息通过 `spring.config.import: nacos:` + `spring.cloud.bootstrap.enabled=true` 双模式注入
- Maven 资源过滤占位符注入 Nacos 地址，切换环境只改 pom 中三个 property

> **注**：构建 v3/v4 模块需 Maven 镜像包含 Spring Cloud Alibaba 2023.x BOM。如 corporate mirror 不可用，可通过命令行 `-D` 覆盖版本。

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `conditional-refresh-test-sample-v3/pom.xml` | 新增 | SB3 模块 POM |
| `conditional-refresh-test-sample-v3/src/main/resources/application.yml` | 新增 | Nacos + 条件刷新配置 |
| `conditional-refresh-test-sample-v3/src/main/java/.../TestV3*.java` | 新增 | 应用入口 + Beans + Controller |
| `conditional-refresh-test-sample-v3/src/test/java/.../ConditionalRefreshV3SampleTest.java` | 新增 | JUnit 集成测试（7 用例） |
| `conditional-refresh-test-sample-v4/pom.xml` | 新增 | SB4 模块 POM |
| `conditional-refresh-test-sample-v4/src/main/resources/application.yml` | 新增 | Nacos + 条件刷新配置 |
| `conditional-refresh-test-sample-v4/src/main/java/.../TestV4*.java` | 新增 | 应用入口 + Beans + Controller |
| `conditional-refresh-test-sample-v4/src/test/java/.../ConditionalRefreshV4SampleTest.java` | 新增 | JUnit 集成测试（7 用例） |
| `CLAUDE.md` | 修改 | 版本矩阵 + 构建命令 |
| `README.md` | 修改 | 版本说明 |

#### 3. 项目重构为多模块 Maven 项目（P1）— 2026-07-03

将项目从单模块重构为多模块结构，新增 `conditional-refresh-test-sample` 测试验证模块：

- 父 POM：`conditional-refresh-spring-boot-starter-parent`（packaging=pom）
- starter 子模块：现有核心保持不变
- test-sample 子模块：可运行应用 + JUnit 集成测试

**test-sample 模块特性**：
- 可运行 Spring Boot 应用（`TestSampleApplication`），提供 REST 端点
- JUnit 集成测试（`ConditionalRefreshSampleTest`），使用真实 Nacos 服务器，覆盖 7 个场景
- Nacos 连接信息通过 Maven 资源过滤占位符注入，切换环境只需修改 pom.xml 中三个 `<property>` 值

**测试结果**：
```
starter 模块: Tests run: 48, Failures: 0, Errors: 0, Skipped: 0 (单元测试)
BUILD SUCCESS
```

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 根 POM 改为父 POM（packaging=pom），聚合两个子模块 |
| `conditional-refresh-spring-boot-starter/pom.xml` | 新增 | starter 子模块 POM |
| `conditional-refresh-test-sample/pom.xml` | 新增 | test-sample 模块 POM |
| `conditional-refresh-test-sample/src/main/resources/bootstrap.yml` | 新增 | Nacos 连接配置（使用占位符） |
| `conditional-refresh-test-sample/src/main/resources/application.yml` | 新增 | 应用配置 |
| `conditional-refresh-test-sample/src/main/java/.../TestSampleApplication.java` | 新增 | 应用入口 |
| `conditional-refresh-test-sample/src/main/java/.../TestBeans.java` | 新增 | @RefreshOnKeys 示例 Bean |
| `conditional-refresh-test-sample/src/main/java/.../TestController.java` | 新增 | REST 端点 |
| `conditional-refresh-test-sample/src/test/java/.../ConditionalRefreshSampleTest.java` | 新增 | JUnit 集成测试 |
| `conditional-refresh-test-sample/src/test/resources/application-test.yml` | 新增 | 测试配置 |
| `CLAUDE.md` | 修改 | 更新模块结构、构建命令、测试覆盖 |

#### 2. 真实 Nacos 端到端集成测试套件（P1）— 2026-07-03

新建 `ConditionalRefreshE2ETest.java`（位于 `tornado-facade-service` 项目），使用真实 Nacos 服务器（`nacos01.dev02.wyc.ws.srv:8848`），通过 `ConfigService.publishConfig()` 模拟 Nacos 服务端推送，完整覆盖 7 个场景：
- Context 加载
- 反向索引构建
- Nacos Listener 注册
- channel.sign.* 精准刷新
- 未监听 Key 不影响刷新
- 多 dataId/group 独立刷新
- destroyMethod + 惰性实例化

**测试结果**：
```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 (E2E)
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0 (单元测试)
BUILD SUCCESS
```

#### 1. Nacos file-extension 兼容（P1）— 2026-07-03

Nacos 2.x 客户端在 `file-extension: yaml` 时，实际存储/监听的 dataId 会自动追加 `.yaml` 后缀，但 starter 注册条件刷新监听器时仅使用原始 dataId（不含后缀），导致监听器无法收到 Nacos 服务端的推送通知。

**修复**：
- `ConditionalRefreshListener.addListener()` 新增 file-extension 感知逻辑，自动同时注册 `dataId` 与 `dataId.fileExtension` 两个 Nacos 监听器
- `getContext()` 方法新增"去除扩展名后回退查找"逻辑，使 dataId 回调无论带不带扩展名均能找到已注册的 ListenerContext

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ConditionalRefreshListener.java` | 修改 | addNacosListener 拆分 + file-extension 兼容处理 |

### 修复（第二轮 P2 清理）— 2026-07-03

#### pom.xml Java 版本属性对齐（P2）

`pom.xml` 中 `java.version`、`maven.compiler.source`、`maven.compiler.target` 均声明为 `11`，但 `maven-compiler-plugin` 实际配置为 `1.8`。已统一改为 `1.8`。

#### 移除未使用的 import（P2）

`ConditionalRefreshListener` 中 import 了 `org.springframework.beans.ObjectProvider` 但从未使用，已移除。

#### RefreshFailedException 标注为废弃（P2）

`RefreshFailedException` 声明为"预留用于未来扩展"但既无 Javadoc 说明也不会被当前代码抛出。已添加 `@Deprecated` 注解和完善 Javadoc。

#### 测试代码清理（P2）

`DebouncerConcurrencyTest` 中声明了 `startLatch` 但未实际使用，已移除该无用变量。

---

## [1.0.1] - 2026-07-03

### 修复

#### 1. Debouncer 线程模型优化（P0）— 2026-07-03

`Debouncer` 使用单线程调度器导致不同 Bean 的刷新任务被强制串行化，与 Bean 级 `ReentrantLock` 设计意图冲突。

**修复**：
- 将调度器改为 `Executors.newScheduledThreadPool(poolSize)`，默认线程池大小为 `max(2, CPU核心数)`
- 新增三参数构造函数 `Debouncer(long delay, TimeUnit unit, int poolSize)`

#### 2. ConditionalRefreshScope.refresh() 返回值语义澄清（P0）— 2026-07-03

`refresh()` 返回 `true` 仅表示旧实例被销毁，但调用方误记录为"刷新成功"。已修复日志语义。

#### 3. ListenerContext.findAffectedBeans() 重复代码消除（P2）— 2026-07-03

`ListenerContext.findAffectedBeans()` 与 `MetadataCollector.IndexEntry.findAffectedBeans()` 逻辑完全重复。已改为直接委托。

#### 4. JDK 版本兼容性问题修复（P0）— 2026-07-03

源代码使用了 Java 9+ API（`Map.of()`, `String.isBlank()`），但编译目标为 1.8。已替换为 Java 8 兼容写法。

#### 5. 测试覆盖不足（P1）— 2026-07-03

新增 4 个测试类共 22 个测试用例，覆盖 ConditionalRefreshScope、ListenerContext、Debouncer 并发、ConditionalRefreshListener。

**测试结果**：
```
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/Debouncer.java` | 修改 | 线程池改为多线程，新增带 poolSize 的构造函数 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../scope/ConditionalRefreshScope.java` | 修改 | 添加返回值语义 Javadoc，更新日志文案 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ListenerContext.java` | 重构 | 内部委托 IndexEntry，移除重复代码 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ConditionalRefreshListener.java` | 修改 | 更新 refreshBean 日志语义，移除未使用 import |
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ConfigDiffUtils.java` | 修改 | isBlank() → trim().isEmpty() |
| `conditional-refresh-spring-boot-starter/src/main/java/.../exception/RefreshFailedException.java` | 修改 | 添加 @Deprecated |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConfigDiffUtilsTest.java` | 修改 | 替换 Map.of() |
| `conditional-refresh-spring-bootstarter/src/test/java/.../MetadataCollectorTest.java` | 修改 | 替换 Set.of()，添加 toSet 辅助方法 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../DebouncerConcurrencyTest.java` | 新增/修改 | 3 个测试用例 + 清理未使用变量 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConditionalRefreshScopeTest.java` | 新增 | 7 个测试用例 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ListenerContextTest.java` | 新增 | 7 个测试用例 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConditionalRefreshListenerTest.java` | 新增 | 5 个测试用例 |
| `pom.xml` | 修改 | 移除 toolchains plugin，统一为 1.8 |
| `CLAUDE.md` | 修改 | 更新 Java 版本说明、测试覆盖、并发安全章节 |
