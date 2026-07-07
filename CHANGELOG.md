# 变更日志 (CHANGELOG)

## [Unreleased]

### 破坏性变更

#### 12. ConditionalRefreshListener 事件源从 Nacos SDK 切换为 Spring EnvironmentChangeEvent（P1）— 2026-07-07

**背景**：原实现通过 Nacos SDK 原始监听器（`configService.addListener`）直接响应配置推送，导致 Bean 销毁/重建发生在 Spring `PropertySource` 更新**之前**（日志证据：Nacos 推送后 190ms 才完成 PropertySource 更新）。新实例惰性创建时读到 stale 值，条件刷新等于白做。

**变更**：
- `ConditionalRefreshListener` 从 `ApplicationListener<ApplicationReadyEvent>` 重构为 `SmartApplicationListener`，监听两个事件：
  1. `EnvironmentChangeEvent`（主路径，SC 2022.0.x+）— 在 PropertySource 更新后发布，携带 `Set<String> changedKeys`
  2. `RefreshScopeRefreshedEvent`（fallback，SC 2021.0.x）— `LegacyContextRefresher` 全量 restart 后触发，不携带 keys → 全量刷新所有 `@RefreshOnKeys` Bean
- 移除 `NacosConfigManager` 依赖注入、`addNacosListener()`、`fetchInitialSnapshot()`、`contexts` 映射、snapshot 原子替换逻辑
- 删除 `ListenerContext` 类（不再需要 per-(dataId,group) snapshot 管理）
- 实现 `Ordered` 接口返回 `Ordered.LOWEST_PRECEDENCE`，确保在 `ConfigurationPropertiesRebinder` **之后**执行（保证 `@ConfigurationProperties` Bean 已 rebind 后，conditional Bean 才销毁重建）
- `ConditionalRefreshAutoConfiguration.conditionalRefreshListener()` 签名移除 `NacosConfigManager` 参数

**版本适配**：
- SC 2022.0.x+（v2/v3/v4 模块）：精确模式 + 前缀模式均正常工作
- SC 2021.0.x（test-sample 模块）：降级为 fallback 全量刷新（`LegacyContextRefresher` 全量 restart 的固有局限）

**测试结果**：
```
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 新增

#### 11. @RefreshOnKeys 新增 prefix() 前缀监听模式（P1）— 2026-07-07

**背景**：用户期望支持"自动监听"模式 — 无需显式列出每个 key，而是通过前缀匹配实现粗粒监听。典型场景：`@ConfigurationProperties(prefix = "channel.sign")` 绑定的 Bean，当任何 `channel.sign.*` key 变更时自动触发刷新。

**变更**：
- `@RefreshOnKeys` 新增 `String prefix() default ""` 属性
- `value()` 与 `prefix()` 互斥校验（在 `RefreshOnKeysPostProcessor` 启动时验证）：
  - 两者都非空 → 启动异常
  - 两者都为空 → 启动异常
- `MetadataCollector.IndexEntry` 新增 `prefixToBeanNames: Map<String, Set<String>>` 双索引
- `findAffectedBeans()` 同时遍历精确索引和前缀索引，前缀匹配规则：`changedKey.startsWith(prefix + ".")`
- 支持 `@ConfigurationProperties` Bean 注入工厂方法参数（prefix 模式下推荐做法）

**使用示例**：
```java
@ConfigurationProperties(prefix = "channel.sign")
public class ChannelSignProperties { private String secret; private String token; }

@Bean(destroyMethod = "destroy")
@RefreshOnKeys(prefix = "channel.sign")
public ChannelSignService channelSignService(ChannelSignProperties props) {
    return new ChannelSignService(props.getSecret(), props.getToken());
}
```

**测试结果**：
```
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `conditional-refresh-spring-boot-starter/src/main/java/.../annotation/RefreshOnKeys.java` | 修改 | 新增 `prefix()` 属性，`value()` 改为 `default {}` |
| `conditional-refresh-spring-boot-starter/src/main/java/.../processor/MetadataCollector.java` | 修改 | `add()` 新增 prefix 参数；`IndexEntry` 新增 `prefixToBeanNames` 双索引；`findAffectedBeans()` 支持前缀匹配 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../processor/RefreshOnKeysPostProcessor.java` | 修改 | 提取 prefix；新增 value/prefix 互斥校验 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ConditionalRefreshListener.java` | 重构 | 从 Nacos SDK 监听改为 SmartApplicationListener 双事件监听（EnvironmentChangeEvent + RefreshScopeRefreshedEvent）；移除 NacosConfigManager 依赖；实现 Ordered |
| `conditional-refresh-spring-boot-starter/src/main/java/.../config/ConditionalRefreshAutoConfiguration.java` | 修改 | `conditionalRefreshListener()` 签名移除 NacosConfigManager 参数 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ListenerContext.java` | 删除 | 不再需要 per-(dataId,group) snapshot 管理 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConditionalRefreshListenerTest.java` | 重构 | 移除 Nacos mock；改为 EnvironmentChangeEvent + RefreshScopeRefreshedEvent 测试 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ListenerContextTest.java` | 删除 | 跟随 ListenerContext |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConditionalRefreshListenerRefreshEventTest.java` | 新增 | 双模式覆盖测试（精确 + 前缀 + 混合 + fallback） |
| `conditional-refresh-spring-boot-starter/src/test/java/.../MetadataCollectorTest.java` | 修改 | 新增 prefix 模式测试 |
| `conditional-refresh-test-sample-v2/src/main/java/.../TestV2Beans.java` | 修改 | 抽取 @ConfigurationProperties 类；TemplateService 改为 prefix 模式 |
| `conditional-refresh-test-sample-v3/src/main/java/.../TestV3Beans.java` | 修改 | 同上 |
| `conditional-refresh-test-sample-v4/src/main/java/.../TestV4Beans.java` | 修改 | 同上 |

### 修复

#### 8. RefreshOnKeysPostProcessor proxyMode 设置方式错误导致 scoped-proxy 未创建（P0）— 2026-07-07

`RefreshOnKeysPostProcessor.rewriteBeanDefinition()` 在 Spring Framework 5.x（starter 编译目标）上无法正确设置 `ScopedProxyMode.TARGET_CLASS`。原代码通过反射设置 `proxyMode` 字段：
- Spring 5.3.x 的 `AbstractBeanDefinition` 没有 `proxyMode` 字段 → 反射抛 `NoSuchFieldException`
- 回退到 `BeanDefinition.setAttribute("ScopedProxyUtils.proxyMode", TARGET_CLASS)` — 但 Spring 的 `ScopedProxyCreator` 不读取这个 attribute key 来创建代理

**现象**（v2 模块 SB3.0 + SC 2022.0.x 端到端验证）：
```
c.l.c.p.RefreshOnKeysPostProcessor : Bean 'channelSignService' scope='conditionalRefresh', proxyMode set via attribute fallback.
```
Bean 被创建为普通单例（非 scoped-proxy），注入 Controller 的是原始实例而非代理。配置变更时：
- `scope.refresh("channelSignService")` 找不到缓存实例（因为从未通过 `GenericScope.get()` 路径创建）
- 即便让 `refresh()` 返回 `true`，下次访问 proxy 也不会触发新实例创建（因为没有 proxy）

**修复**：
- 改用 `ScopedProxyCreator.createScopedProxy(BeanDefinitionHolder, BeanDefinitionRegistry, boolean)` 显式触发代理创建
- `ScopedProxyCreator` 是 package-private，通过反射调用；方法签名在 Spring 5.3.x / 6.0.x 中保持一致

**测试结果**：
```
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

#### 6. MetadataCollector 多 Bean 同 dataId+group 覆盖问题（P0）— 2026-07-07

当多个 `@RefreshOnKeys` Bean 的 dataId/group 解析为同一值时（常见于不显式指定 dataId 的情况，均回退到 `spring.application.name`），`buildCommittedIndex()` 每次循环都创建新 `IndexEntry` 替换前一个，导致 committed index 只保留最后一个 Bean 的 key 映射，其他 Bean 的 key 完全丢失。

**现象**：Nacos 配置变更后，部分 Bean 永远无法被条件刷新定位到，日志显示 `Changed keys [...] not watched by any bean`。

**修复**：
- 将 `buildCommittedIndex()` 改为两阶段构建：先用中间 `Map` 累积 `key → Set<beanName}` 映射，再统一转为不可变 `IndexEntry`
- 多个原始条目映射到同一 `(dataId, group)` 时执行合并而非替换

**测试结果**：
```
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

#### 7. ConditionalRefreshScope.refresh() 返回值语义修正（P1）— 2026-07-07

`ConditionalRefreshScope.refresh()` 在 scope 中无该 Bean 缓存实例时（例如 Bean 从未通过 proxy 访问、或先前推送已销毁旧实例且新实例尚未惰性创建），原实现返回 `false`，导致调用方误判为"刷新失败"。

**根因**：v2 模块（SB3.0 + SC 2022.0.x）端到端验证时发现，`@Bean` 工厂方法创建的 Bean 直接被 Spring 容器持有，不经过 `GenericScope.get()` 路径注册到 scope 缓存。当第二次 key 推送触发 `refresh()` 时，缓存已空（第一次推送已销毁旧实例），返回 `false`。

**修复**：
- `ConditionalRefreshScope.refresh()` 在无缓存实例时**返回 `true`**（而非 `false`），
  并在 DEBUG 日志中标注"config change will take effect on next proxy access"
- `removeBeanSafely()` 不再吞掉 `destroyMethod` 抛出的异常，改为直接委托给 `super.remove()`，
  让调用方感知真正的失败
- `ConditionalRefreshListener.refreshBean()` 中的 `destroyed` 变量重命名为 `effective`，
  INFO 日志改为"config change applied"，与"配置变更已生效"语义对齐
- 更新 `ConditionalRefreshScopeTest` 和 `ConditionalRefreshScopeSurvivalTest` 中的断言：
  无缓存实例时预期返回 `true`，第二次刷新也预期返回 `true`

### 新增

#### 5. v2 测试模块升级到 SB3.0 + SC 2022.0.x（P1）— 2026-07-07

`conditional-refresh-test-sample-v2` 从 SB2.7 + SC 2021.0.x（全量 context restart 模式）升级到 SB3.0.13 + SC 2022.0.5 + SCA 2022.0.0.0（轻量 RefreshEvent 模式），验证条件刷新在新一代 Spring Cloud 刷新机制下是否正常工作。

**关键发现**：
- SC 2022.0.x 的 `RefreshEventListener` 只触发轻量环境刷新，**不再触发全量 context restart**
- 条件刷新在该场景下工作正常：key 变更 → diff → 反向索引 → 精准刷新关联 Bean
- 需要 Java 17 编译和运行

**验证日志**：
```
o.s.c.e.event.RefreshEventListener       : Refresh keys changed: [channel.sign.token]  ← 轻量事件
c.l.c.l.ConditionalRefreshListener       : Affected beans ...: [channelSignService]    ← 精准定位
c.l.c.scope.ConditionalRefreshScope      : Bean 'channelSignService' destroyed ...     ← 条件刷新
```

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `conditional-refresh-spring-boot-starter/src/main/java/.../processor/RefreshOnKeysPostProcessor.java` | 修复 | 改用 `ScopedProxyCreator.createScopedProxy` 反射调用，正确创建 CGLIB scoped-proxy |
| `conditional-refresh-spring-boot-starter/src/main/java/.../processor/MetadataCollector.java` | 修复 | buildCommittedIndex 改为两阶段合并模式 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../listener/ConditionalRefreshListener.java` | 修复 | refreshBean 变量语义从"destroyed"改为"effective"，对齐新返回值 |
| `conditional-refresh-spring-boot-starter/src/main/java/.../scope/ConditionalRefreshScope.java` | 修复 | `refresh()` 无缓存实例时返回 `true`；`removeBeanSafely()` 不再吞异常 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConditionalRefreshScopeTest.java` | 修改 | 更新断言匹配新返回值语义 |
| `conditional-refresh-spring-boot-starter/src/test/java/.../ConditionalRefreshScopeSurvivalTest.java` | 修改 | 更新断言匹配新返回值语义 |
| `conditional-refresh-test-sample-v2/pom.xml` | 修改 | 升级到 SB3.0.13 + SC 2022.0.5 + SCA 2022.0.0.0 + Java 17 |
| `conditional-refresh-test-sample-v2/src/main/resources/application.yml` | 修改 | 改用 `spring.config.import: nacos:` 新 API |
| `conditional-refresh-test-sample-v2/src/main/resources/bootstrap.yml` | 删除 | SC 2022.0.x 不再需要 |
| `conditional-refresh-test-sample-v2/src/main/java/.../TestV2Controller.java` | 修改 | 更新 Javadoc 和 health 字符串 |
| `conditional-refresh-test-sample-v2/src/main/java/.../TestV2Beans.java` | 修改 | 更新 Javadoc |
| `conditional-refresh-test-sample-v2/src/test/java/.../ConditionalRefreshV2SampleTest.java` | 修改 | 更新 Javadoc |
| `CLAUDE.md` | 修改 | 更新 v2 模块版本信息 |

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
