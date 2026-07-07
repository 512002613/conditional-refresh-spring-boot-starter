# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供本仓库的代码工作指引。

## 会话启动必读

每次新会话开始时，**必须首先读取 [`.claude-session-history.md`](./.claude-session-history.md)**。该文件记录了项目的完整开发历程、架构决策、踩坑经验和技术债务，不在 VCS 控制中（已加入 .gitignore），是跨会话恢复上下文的核心文档。

当用户发送"压缩历史"或"更新会话历史"指令时，应根据当前会话的实际进展**更新该文档**（追加或修正内容），确保其始终反映项目最新状态。

## 提交信息规范

**禁止**在提交信息中添加 `Co-Authored-By` 行（包括 `Co-Authored-By: Claude <noreply@anthropic.com>` 或任何变体）。本仓库的所有提交**仅保留真实人类贡献者信息**，不要写入任何 AI 协作者 trailer。

## 变更日志维护

每次**新需求完成**或 **bug 修复变更**完成后，必须在 [`CHANGELOG.md`](./CHANGELOG.md) 顶部 `[Unreleased]` 段落中追加对应的变更记录。

### 变更记录格式

每个变更条目应包含以下三部分：

#### 1. 标题与分类

按变更类型归类为 `### 新增` / `### 修复` / `### 重构` / `### 破坏性变更`，每个条目使用四级标题：

```markdown
#### N. 简短概述（优先级）
```

优先级标签：
- **P0**：影响核心功能的阻断性 bug 或重大缺陷
- **P1**：重要功能缺失或已知 workaround 的兼容性问题
- **P2**：代码质量、文档、测试覆盖等改善性变更

#### 2. 背景与原因

简要说明**为什么**要做这个变更：
- 对于 bug 修复：描述触发场景、影响范围、根因分析
- 对于新需求：说明业务价值或用户诉求
- 对于重构：阐述技术债务或设计缺陷

#### 3. 变更内容

采用无序列表说明具体变更点，不要省略关键细节：
- 修改了哪些方法/类，改了什么逻辑
- 新增了哪些文件/方法/配置
- 删除或废弃了哪些 API

#### 4. 测试结果（如适用）

粘贴 `mvn test` 输出摘要：

```
Tests run: N, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

#### 5. 文件变更清单（多文件变更时必备）

| 文件 | 类型 | 说明 |
|------|------|------|
| `相对路径` | 新增/修改/重构/删除 | 一句话说明 |

### 示例条目

参见现有 [`CHANGELOG.md`](./CHANGELOG.md) 中的 [Unreleased] 段落作为范本。

## 项目概述

一个 Spring Boot Starter，通过 `@RefreshOnKeys` 注解提供**条件配置刷新**能力。被 `@RefreshOnKeys` 标记的 Bean 仅在其监听的特定配置 Key 发生变更时才会重建 —— 与全局性的 `@RefreshScope`（任意环境变化即刷新）不同。设计目标为与 Nacos Config（Spring Cloud Alibaba）协同工作。

## 模块结构

```
conditional-refresh-spring-boot-starter-parent/   ← 父 POM（聚合构建）
├── conditional-refresh-spring-boot-starter/      ← starter 核心模块（jar）
├── conditional-refresh-test-sample/              ← SB2.7 + SC2021.0.8 测试验证模块
├── conditional-refresh-test-sample-v2/           ← SB2.7 + SC2022.0.5 测试验证模块（独立项目）
├── conditional-refresh-test-sample-v3/           ← SB3.5 测试验证模块（独立项目）
└── conditional-refresh-test-sample-v4/           ← SB4.0 测试验证模块（独立项目）
```

### Spring Boot 版本矩阵

| 模块 | Spring Boot | Spring Cloud | Spring Cloud Alibaba | Java |
|------|-------------|--------------|---------------------|------|
| starter | 2.7.18 | 2021.0.8 | 2021.0.5.0 | 1.8 |
| test-sample | 2.7.18 | 2021.0.8 | 2021.0.5.0 | 1.8 |
| test-sample-v2 | 2.7.18 | 2022.0.5 | 2022.0.0.0 | 1.8 |
| test-sample-v3 | 3.5.x | 2023.0.x | 2023.0.3.0 | 17 |
| test-sample-v4 | 4.0.x | 2023.0.x | 2023.0.3.0 | 17 |

> **注意**：v3/v4 模块使用独立的 Spring Boot BOM，不继承父 POM。构建时需确保 Maven 镜像包含对应版本的 Spring Cloud Alibaba BOM。如 corporate mirror 不可用，可通过 `-Dspring-cloud-alibaba.version=...` 覆盖版本号。

### test-sample 模块

- **可运行应用**：`TestSampleApplication.java`，提供 REST 端点手动验证刷新行为
- **集成测试**：`ConditionalRefreshSampleTest.java`，使用真实 Nacos 服务器自动化验证
- **Nacos 连接信息**：通过 Maven 资源过滤占位符注入，切换环境只需修改 `conditional-refresh-test-sample/pom.xml` 中的 `<nacos.server-addr>`、`<nacos.namespace>`、`<nacos.group>` 三个属性

## 构建与测试命令

```bash
# 完整构建（编译 + 测试 + 打包，所有模块）
cd /d/devworkspace/conditional-refresh-spring-boot-starter && mvn clean package

# 构建 v2 测试模块（独立项目，不继承父 POM）
mvn clean package -pl conditional-refresh-test-sample-v2

# 跳过测试
mvn clean package -DskipTests

# 仅运行 starter 模块的单元测试
mvn test -pl conditional-refresh-spring-boot-starter

# 运行 test-sample 模块的集成测试（需要真实 Nacos 服务器）
mvn test -pl conditional-refresh-test-sample

# 运行单个测试类
mvn test -Dtest=ConfigDiffUtilsTest

# 运行单个测试方法
mvn test -Dtest=MetadataCollectorTest#buildCommittedIndex_placeholderResolved

# 仅编译（不运行测试）
mvn clean compile

# 安装到本地仓库（供下游项目使用）
mvn clean install
```

**构建要求：** JDK 1.8+，Maven 3.6+。显式使用 `maven-surefire-plugin` 2.22.2 以兼容 JUnit 5。

**测试框架：** JUnit 5 (Jupiter)，通过 `spring-boot-starter-test` 引入。Mockito 4.5.1 用于 mock。

## 架构

### 包结构

```
com.liu.conditionalrefresh
├── annotation/       → @RefreshOnKeys（公共 API）
├── config/           → 自动配置 + 属性绑定
├── processor/        → Bean 定义扫描 + 元数据收集
├── scope/            → 自定义作用域生命周期（GenericScope 扩展）
├── listener/         → EnvironmentChangeEvent → 反向索引 → 去抖刷新
└── exception/        → RefreshFailedException（@Deprecated，预留）
```

### 生命周期流程

```
@RefreshOnKeys 标记的 Bean
    │
    ▼（BeanDefinitionRegistryPostProcessor 阶段）
RefreshOnKeysPostProcessor
  ├─ 改写：scope="conditionalRefresh"，创建 CGLIB scoped-proxy
  ├─ 校验：value/prefix 互斥；不得同时标注 @RefreshScope
  └─ 收集原始元数据 → MetadataCollector
    │
    ▼（Bean 实例化）
Bean 以 scoped-proxy 形式创建（惰性 —— 仅在首次访问时实例化）
    │
    ▼（ApplicationReadyEvent）
ConditionalRefreshListener.onApplicationReady()
  └─ MetadataCollector.buildCommittedIndex(env)
       ├─ 解析 keys/dataId/group/prefix 中的 ${...} 占位符
       ├─ 回退规则：dataId → spring.application.name，group → DEFAULT_GROUP
       └─ 构建双索引：dataId → group → IndexEntry(keyToBeanNames + prefixToBeanNames)
    │
    ▼（Nacos 推送 → PropertySource 更新 → EnvironmentChangeEvent）
ConditionalRefreshListener.onEnvironmentChanged()  ← 主路径（SC 2022.0.x+）
  ├─ event.getKeys() 获取变更 keys
  ├─ IndexEntry.findAffectedBeans(changedKeys) → 受影响的 bean 名称集合
  │    ├─ 精确匹配：changedKey 存在于 keyToBeanNames
  │    └─ 前缀匹配：changedKey.startsWith(prefix + ".")
  └─ 按 bean：Debouncer.debounce(beanName, refreshTask)
       │（去抖窗口后，在线程池中执行）
       ▼
  ConditionalRefreshListener.refreshBean(beanName)
    ├─ ReentrantLock.lock()（按 bean 粒度）
    ├─ scope.refresh(beanName) 仅销毁旧实例（新实例惰性创建）
    └─ recordSuccess / recordFailure（Micrometer）
    │
    ▼（SC 2021.0.x fallback — RefreshScopeRefreshedEvent）
ConditionalRefreshListener.onRefreshScopeRefreshed()
  └─ 全量刷新所有 @RefreshOnKeys Bean（无法获取 changedKeys 的降级方案）
```

### 核心组件

**`@RefreshOnKeys`** — 注解，两种互斥模式：`String[] value()`（精确模式，显式列出监听的 keys）或 `String prefix()`（前缀模式，监听 `prefix.*`），可选 `dataId()` 和 `group()`（均为空时自动解析）。不含 `@Scope`，作用域通过编程方式设置。

**`RefreshOnKeysPostProcessor`**（BDRPP）— 在 Bean 定义注册后处理阶段扫描所有 Bean 定义。通过 `ScopedProxyCreator.createScopedProxy()` 显式创建 CGLIB scoped-proxy。Order = `HIGHEST_PRECEDENCE + 10`。

**`MetadataCollector`** — 两阶段数据结构：BDRPP 阶段收集原始数据，`ApplicationReadyEvent` 时一次性构建提交索引。使用 `ConcurrentHashMap` 保证线程安全，`volatile committedIndex` 保证安全发布。内部类：`DataGroupKey`（不可变的 (dataId, group) 对）、`IndexEntry`（含 `keyToBeanNames` + `prefixToBeanNames` 双索引）。

**`ConditionalRefreshScope`** — 扩展 Spring Cloud 的 `GenericScope`，名称为 `"conditionalRefresh"`。`refresh()` 仅销毁旧实例（通过 `"scopedTarget." + beanName` 定位缓存）；新实例在下次 scoped-proxy 访问时惰性创建。**返回值 `true` 表示"配置变更已生效"（旧实例已销毁，或无需销毁）。** 含 survivor cache 支持跨 context restart 场景。

**`ConditionalRefreshListener`** — 实现 `SmartApplicationListener` + `Ordered`，双事件监听：
- `EnvironmentChangeEvent`（主路径）— PropertySource 更新后发布，携带 changedKeys，精准刷新
- `RefreshScopeRefreshedEvent`（fallback）— SC 2021.0.x 全量 restart 后触发，全量刷新所有 @RefreshOnKeys Bean
- `getOrder()` 返回 `Ordered.LOWEST_PRECEDENCE`，确保在 `ConfigurationPropertiesRebinder` 之后执行

使用按 bean 粒度的 `ReentrantLock`（不同 bean 并行刷新，同一 bean 串行）。通过 `Metrics.globalRegistry` 集成 Micrometer（可选 — 无硬依赖）。

**`Debouncer`** — 全局单一 `ScheduledExecutorService`（默认线程池 = `max(2, CPU 核心数)`）。允许多个 Bean 并行刷新，同一 Bean 并发由外层 `ReentrantLock` 控制。默认去抖窗口 500ms，可通过 `conditional.refresh.debounce.delay` 配置。

**`ConfigDiffUtils`** — 同时解析 Properties 和 YAML 格式（启发式规则：含 `:` 且不以 `[` 开头 → YAML）。YAML 解析失败时回退到 Properties。`diff()` 将 key 删除视为无变更，以减少不必要的刷新。注意：当前架构下此类仅作为工具保留，不再在主链路中使用（因为框架不再直接处理 Nacos 推送）。

**`RefreshFailedException`** — `@Deprecated`，预留。当前框架使用日志 + Micrometer 指标替代刷新失败时的异常抛出。

### 自动配置条件

`ConditionalRefreshAutoConfiguration` 中全部 5 个 Bean 均受以下条件约束：
- `@ConditionalOnClass(NacosConfigManager)` — classpath 中存在 Nacos Config
- `@ConditionalOnBean(NacosConfigManager)` — Nacos 自动配置已激活
- `@ConditionalOnProperty(conditional.refresh.enabled=true, matchIfMissing)`
- `@AutoConfigureAfter(NacosConfigAutoConfiguration)`

所有 Bean 使用 `@ConditionalOnMissingBean`，允许用户覆盖任意组件。

### 配置属性（前缀：`conditional.refresh`）

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 总开关 |
| `debounce.delay` | `500` ms | 去抖窗口 |
| `metrics-enabled` | `true` | 是否暴露 Micrometer 计数器 |
| `initial-snapshot-enabled` | `true` | 注册监听器前先获取快照（旧版兼容属性，当前未使用） |

### Spring Boot 版本兼容性

双注册自动配置：
- `META-INF/spring.factories`（Spring Boot 2.x）
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Spring Boot 3.x）

目标版本：Spring Boot 2.7.18，Spring Cloud 2021.0.8，Spring Cloud Alibaba 2021.0.5.0。

### 并发安全设计

| 机制 | 用途 |
|------|------|
| `ConcurrentHashMap`（beanLocks） | Bean 级锁的线程安全惰性初始化 |
| `ReentrantLock` per bean | 同一 bean 串行，不同 bean 并行 |
| `Debouncer`（全局单一） | 抑制快速重复刷新触发；线程池支持并行执行 |
| `volatile committedIndex` | 构建完成的索引的安全发布 |

### Micrometer 指标（可选，无硬 classpath 依赖）

- `conditional.refresh.success` 计数器（tag: bean 名称）— 旧实例销毁后递增
- `conditional.refresh.failure` 计数器（tag: bean 名称）— 刷新异常时递增
- 通过 `Metrics.globalRegistry` 访问，带 try/catch 保护 — 框架在 Micrometer 缺失时绝不抛异常

### 测试覆盖

#### starter 模块单元测试（位于 `conditional-refresh-spring-boot-starter/src/test/`）

| 测试类 | 覆盖组件 | 用例数 |
|--------|----------|--------|
| `ConfigDiffUtilsTest` | `ConfigDiffUtils` | 11（parse、diff、e2e、blank） |
| `DebouncerTest` | `Debouncer` | 6（dedup、exception、shutdown） |
| `DebouncerConcurrencyTest` | `Debouncer` 并发 | 3（parallel、dedup、single-thread） |
| `MetadataCollectorTest` | `MetadataCollector` | 13（index、fallback、placeholder、prefix 双索引） |
| `ConditionalRefreshScopeTest` | `ConditionalRefreshScope` | 7（destroy、lazy recreate、edge cases） |
| `ConditionalRefreshListenerTest` | `ConditionalRefreshListener` | 5（register、disable、close） |
| `ConditionalRefreshListenerRefreshEventTest` | `ConditionalRefreshListener` 事件驱动 | 8（精确 + 前缀 + 混合 + fallback + 开关） |

#### test-sample 模块集成测试（位于 `conditional-refresh-test-sample/src/test/`）

| 测试类 | 覆盖场景 | 用例数 |
|--------|----------|--------|
| `ConditionalRefreshSampleTest` | 真实 Nacos 端到端（Context 加载、索引构建、精准刷新、多组独立、destroyMethod） | 7 |
