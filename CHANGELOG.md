# 变更日志 (CHANGELOG)

## [Unreleased] - 2026-07-03

### 修复（第二轮 P2 清理）

#### 1. pom.xml Java 版本属性对齐（P2）

**问题**：`pom.xml` 中 `java.version`、`maven.compiler.source`、`maven.compiler.target` 均声明为 `11`，但 `maven-compiler-plugin` 实际配置为 `1.8`，造成配置不一致和开发者误导。

**修复**：
- 将 properties 中的三个版本声明统一改为 `1.8`
- 添加注释说明设定为 1.8 的原因（最大化部署兼容性）

#### 2. 移除未使用的 import（P2）

**问题**：`ConditionalRefreshListener` 中 import 了 `org.springframework.beans.ObjectProvider` 但从未使用。

**修复**：移除该无用 import。

#### 3. RefreshFailedException 标注为废弃（P2）

**问题**：`RefreshFailedException` 声明为"预留用于未来扩展"但既无 Javadoc 说明也不会被当前代码抛出，给维护者造成困惑。

**修复**：
- 添加 `@Deprecated` 注解
- Javadoc 详细说明保留原因和启用条件
- 增加指向未来启用位置的 `@see` 引用

#### 4. 测试代码清理（P2）

**问题**：`DebouncerConcurrencyTest` 中声明了 `startLatch` 但未实际使用（测试用 `Thread.sleep` 替代了精确同步）。

**修复**：移除未使用的 `startLatch` 变量，保持代码整洁。

---

## [1.0.1] - 2026-07-03

### 修复

#### 1. Debouncer 线程模型优化（P0）

**问题**：`Debouncer` 使用单线程 `Executors.newSingleThreadScheduledExecutor()`，导致同一 (dataId, group) 下不同 Bean 的刷新任务被强制串行化，与上层的 Bean 级 `ReentrantLock` 设计意图冲突（Bean 级锁旨在实现不同 Bean 并行刷新）。

**修复**：
- 将调度器改为 `Executors.newScheduledThreadPool(poolSize)`，默认线程池大小为 `max(2, CPU核心数)`
- 新增三参数构造函数 `Debouncer(long delay, TimeUnit unit, int poolSize)` 支持测试和自定义场景
- 同一 key 的去抖语义保持不变（去重逻辑由 `pending ConcurrentHashMap` 保证）
- 外层 Bean 级锁仍然保证同一 Bean 串行，不同 Bean 现在可真正并行

#### 2. ConditionalRefreshScope.refresh() 返回值语义澄清（P0）

**问题**：`refresh()` 返回 `true` 仅表示旧实例被销毁，但实际上新实例尚未创建（惰性重建）。调用方 `ConditionalRefreshListener` 在 `true` 分支记录了 `conditional.refresh.success` 指标，存在指标语义误导。

**修复**：
- 在 `ConditionalRefreshScope.refresh()` Javadoc 中添加 "返回值语义" 章节，明确说明 `true` 不代表新实例创建成功
- 在 `ConditionalRefreshListener.refreshBean()` 的日志和注释中明确区分"销毁"和"惰性重建"两个阶段
- 日志从 "refreshed successfully" 改为 "destroyed. New instance will be created lazily on next access"

#### 3. ListenerContext.findAffectedBeans() 重复代码消除（P2）

**问题**：`ListenerContext.findAffectedBeans()` 与 `MetadataCollector.IndexEntry.findAffectedBeans()` 逻辑完全重复。

**修复**：
- `ListenerContext` 改为直接委托给 `MetadataCollector.IndexEntry.findAffectedBeans()`
- 新增 `indexEntry` 字段，移除独立的 `keyToBeans` 字段

#### 4. JDK 版本兼容性问题修复（P0）

**问题**：项目 pom.xml 声明 Java 11 编译目标，但 `maven-toolchains-plugin` 未配置且本地 JDK 为 1.8，导致编译器实际以 1.8 目标编译。同时源代码使用了 Java 9+ API（`Map.of()`, `String.isBlank()`）。

**修复**：
- 移除 `maven-toolchains-plugin`（无实际 JDK 11 工具链配置，反而覆盖编译目标）
- 显式设置 `maven-compiler-plugin` 的 `<source>1.8</source>` 和 `<target>1.8</target>`
- 将 `ConfigDiffUtils.isBlank()` 替换为 `trim().isEmpty()`
- 将 `ConditionalRefreshListener` 中的 `Map.of()` 替换为 `Collections.emptyMap()`
- 修正 ConfigDiffUtils unchecked 警告（`parseYaml` 中的泛型转换添加 `instanceof` 前置检查）

#### 5. 测试覆盖不足（P1）

**新增测试类**：
- `ConditionalRefreshScopeTest.java` — 7 个测试用例覆盖：常量验证、不存在 Bean 刷新、空/null 名称异常、正常刷新后再次刷新、惰性重建
- `ListenerContextTest.java` — 7 个测试用例覆盖：委托行为、单 key 影响、未监听 key、输入、快照原子替换、不可变视图
- `DebouncerConcurrencyTest.java` — 3 个测试用例覆盖：不同 key 并行执行、同 key 去重、单线程池退化行为
- `ConditionalRefreshListenerTest.java` — 5 个测试用例覆盖：监听器注册、配置变更触发、全局开关关闭、无 Bean 场景、close 释放资源

**原有测试 Java 8 兼容化**：
- `ConfigDiffUtilsTest.java` — 替换 `Map.of()` 为 `createMap()` 辅助方法，增加空白配置测试
- `MetadataCollectorTest.java` — 替换 `Set.of()` 为 `toSet()` 辅助方法

### 测试结果

```
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 文件变更清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `src/main/java/.../listener/Debouncer.java` | 修改 | 线程池改为多线程，新增带 poolSize 的构造函数 |
| `src/main/java/.../scope/ConditionalRefreshScope.java` | 修改 | 添加返回值语义 Javadoc，更新日志文案 |
| `src/main/java/.../listener/ListenerContext.java` | 重构 | 内部委托 IndexEntry，移除重复代码 |
| `src/main/java/.../listener/ConditionalRefreshListener.java` | 修改 | 更新 refreshBean 日志语义，移除未使用 import |
| `src/main/java/.../listener/ConfigDiffUtils.java` | 修改 | isBlank() → trim().isEmpty()，优化 unchecked 警告 |
| `src/main/java/.../exception/RefreshFailedException.java` | 修改 | 添加 @Deprecated，完善 Javadoc 说明保留原因 |
| `src/test/java/.../ConfigDiffUtilsTest.java` | 修改 | 替换 Map.of()，增加空白文本测试 |
| `src/test/java/.../MetadataCollectorTest.java` | 修改 | 替换 Set.of()，添加 toSet 辅助方法 |
| `src/test/java/.../DebouncerConcurrencyTest.java` | 修改 | 清理未使用变量 |
| `src/test/java/.../ConditionalRefreshScopeTest.java` | 新增 | 7 个测试用例 |
| `src/test/java/.../ListenerContextTest.java` | 新增 | 7 个测试用例 |
| `src/test/java/.../DebouncerConcurrencyTest.java` | 新增 | 3 个测试用例 |
| `src/test/java/.../ConditionalRefreshListenerTest.java` | 新增 | 5 个测试用例 |
| `pom.xml` | 修改 | 移除 toolchains plugin，统一为 1.8，properties 对齐 |
| `CLAUDE.md` | 修改 | 更新 Java 版本说明、测试覆盖、并发安全章节 |
| `README.md` | 不变 | — |
| `docs/code-reading-guide.md` | 不变 | — |
