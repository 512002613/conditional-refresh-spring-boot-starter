# 条件刷新框架 — 代码阅读顺序指南

> 本文档为开发者提供**由浅入深**的代码阅读路径，帮助理解 `@RefreshOnKeys` 条件刷新框架的设计思路、核心机制与实现细节。

---

## 一、阅读路线总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  ① 入口：注解定义         → 了解框架对外暴露的 API                            │
│       ↓                                                                     │
│  ② 配置绑定              → 了解可配置项                                      │
│       ↓                                                                     │
│  ③ 自动配置              → 了解 Bean 装配顺序和条件                           │
│       ↓                                                                     │
│  ④ 作用域 + 注册器        → 了解自定义作用域的生命周期                         │
│       ↓                                                                     │
│  ⑤ 后处理器（扫描改写）    → 了解 @RefreshOnKeys 如何被识别并改写              │
│       ↓                                                                     │
│  ⑥ 元数据收集器           → 了解反向索引的数据结构                             │
│       ↓                                                                     │
│  ⑦ 监听器（核心调度）      → 了解 Nacos 回调如何驱动刷新                       │
│       ↓                                                                     │
│  ⑧ 工具类（diff/去抖/异常）→ 了解基础工具                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、分步阅读指引

---

### 第 1 步：`RefreshOnKeys.java` — 注解定义

**路径**: `src/main/java/com/liu/conditionalrefresh/annotation/RefreshOnKeys.java`

**阅读目标**: 理解框架对外暴露的 API 形态。

| 关注点 | 说明 |
|--------|------|
| `@Target(TYPE, METHOD)` | 可用于类（@Component）和方法（@Bean） |
| `value()` | 必填，配置键名数组 |
| `dataId()` | 可选，默认空（自动回退到 `spring.application.name`） |
| `group()` | 可选，默认空（自动回退到 `DEFAULT_GROUP`） |
| 占位符支持 | 三个属性均支持 `${...}`，运行时解析 |

**关键设计决策**：
- 注解本身**不含** `@Scope`，作用域设置由后处理器自动完成。这样做的原因：
  - 避免用户忘记设置 `@Scope("conditionalRefresh")`
  - 避免与 `@RefreshScope` 语义混淆
  - 注解扫描阶段可以更灵活地控制处理顺序

---

### 第 2 步：`ConditionalRefreshProperties.java` — 配置绑定

**路径**: `src/main/java/com/liu/conditionalrefresh/config/ConditionalRefreshProperties.java`

**阅读目标**: 了解所有可配置项及其默认值。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `conditional.refresh.enabled` | `true` | 全局开关 |
| `conditional.refresh.debounce.delay` | `500` ms | 去抖静默窗口 |
| `conditional.refresh.metrics-enabled` | `true` | 是否暴露 Micrometer 指标 |
| `conditional.refresh.initial-snapshot-enabled` | `true` | 是否启用初始快照 |

**阅读要点**：
- 类级别 Javadoc 中包含完整的 YAML 配置示例
- 所有 getter/setter 都有完整 Javadoc

---

### 第 3 步：`ConditionalRefreshAutoConfiguration.java` — 自动配置

**路径**: `src/main/java/com/liu/conditionalrefresh/config/ConditionalRefreshAutoConfiguration.java`

**阅读目标**: 理解框架的 Bean 装配条件和顺序。

```
装配条件（同时满足）：
  1. classpath 存在 NacosConfigManager
  2. Spring 容器中已有 NacosConfigManager Bean
  3. conditional.refresh.enabled 不为 false

装配顺序（@AutoConfigureAfter 保证）：
  ① NacosConfigAutoConfiguration（Spring Cloud Alibaba 原生）
  ② ConditionalRefreshScope         —— 作用域 Bean
  ③ ConditionalScopeRegistrar        —— BeanFactoryPostProcessor，注册作用域
  ④ RefreshOnKeysPostProcessor       —— BDRPP，扫描改写 Bean 定义
  ⑤ ConditionalRefreshListener       —— ApplicationListener，注册 Nacos 监听器
```

**关键理解**：
- `@ConditionalOnMissingBean` 全部使用，允许用户替换框架默认实现
- `ConditionalScopeRegistrar` 通过构造注入 `ConditionalRefreshScope`，保证注册器与监听器引用**同一个** scope 实例（修复历史 Bug）

---

### 第 4 步：`ConditionalRefreshScope.java` + `ConditionalScopeRegistrar.java`

**路径**:
- `src/main/java/com/liu/conditionalrefresh/scope/ConditionalRefreshScope.java`
- `src/main/java/com/liu/conditionalrefresh/scope/ConditionalScopeRegistrar.java`

**阅读目标**: 理解自定义作用域的生命周期。

#### 4.1 `ConditionalRefreshScope` — 作用域实现

```
继承 GenericScope（Spring Cloud 提供）
  ↓
作用域名称: "conditionalRefresh"
  ↓
refresh(String beanName)  →  从缓存移除旧实例（惰性重建）
  ↓
removeBeanSafely()        →  安全移除（destroyMethod 异常不阻断）
```

**惰性重建语义**：
- `refresh()` 仅销毁旧实例
- 新实例在下一次通过 scoped-proxy 访问时由 `GenericScope.get()` 惰性创建
- 好处：不阻塞 Nacos 回调线程、对齐原生 RefreshScope 行为

#### 4.2 `ConditionalScopeRegistrar` — 作用域注册器

```
BeanFactory.postProcessBeanFactory()
  ↓
beanFactory.registerScope("conditionalRefresh", scope)
  ↓
scope 实例来自容器注入（非 new），保证全局唯一
```

---

### 第 5 步：`RefreshOnKeysPostProcessor.java` — Bean 定义扫描改写

**路径**: `src/main/java/com/liu/conditionalrefresh/processor/RefreshOnKeysPostProcessor.java`

**阅读目标**: 理解 `@RefreshOnKeys` 如何被识别、改写和收集。

#### 处理流程

```
postProcessBeanDefinitionRegistry()
  ├─ 遍历所有 Bean 定义
  │   └─ processBeanDefinition(beanName, bd)
  │       ├─ ① findAnnotation(bd)         → 查找 @RefreshOnKeys
  │       │   ├─ 场景 A：@Bean 方法 → 读取工厂方法元数据
  │       │   └─ 场景 B：@Component 类 → 读取类级元数据
  │       │
  │       ├─ ② checkRefreshScopeConflict()  → 互斥校验
  │       │   └─ 若同时有 @RefreshScope → 抛出 BeanDefinitionStoreException
  │       │
  │       ├─ ③ rewriteBeanDefinition()      → 改写 scope + proxyMode
  │       │   ├─ abd.setScope("conditionalRefresh")
  │       │   └─ 反射设置 proxyMode = TARGET_CLASS
  │       │
  │       └─ ④ collector.add(...)            → 收集元数据（不触发实例化）
  │
  └─ postProcessBeanFactory()  → 空实现（所有逻辑在 BDRPP 阶段完成）
```

#### 核心反射逻辑

```java
// proxyMode 字段为 int 类型，需要：
// 1. 反射访问
// 2. 使用 setInt() 而非 set()
// 3. 传 ScopedProxyMode.TARGET_CLASS.ordinal()
Field proxyModeField = AbstractBeanDefinition.class.getDeclaredField("proxyMode");
proxyModeField.setInt(abd, ScopedProxyMode.TARGET_CLASS.ordinal());
```

**为什么不用 `setProxyMode()` 方法？**
- Spring 的 `AbstractBeanDefinition` 在部分版本中 `setProxyMode()` 访问受限
- 反射方式兼容 Spring 5.x 全系列版本

---

### 第 6 步：`MetadataCollector.java` — 元数据收集器

**路径**: `src/main/java/com/liu/conditionalrefresh/processor/MetadataCollector.java`

**阅读目标**: 理解反向索引的数据结构和构建时机。

#### 数据结构（两层）

```
原始数据（raw）—— BDRPP 阶段收集，占位符未解析
  dataId → group → (beanName → Set<rawKeys>)

反向索引（committedIndex）—— ApplicationReadyEvent 后构建，占位符已解析
  dataId → group → IndexEntry(keyToBeanNames)
                              └─ key → Set<beanName>
```

#### 构建流程

```
add() —— 多次调用，轻量收集
  ↓
buildCommittedIndex(env) —— 一次性构建
  ├─ 解析空 dataId → spring.cloud.nacos.config.prefix → spring.application.name
  ├─ 解析空 group  → spring.cloud.nacos.config.group → DEFAULT_GROUP
  ├─ 解析每个 key 中的 ${...} 占位符
  ├─ 检测不存在的 key → 输出 warn（继续注册）
  └─ 构建完成后：raw.clear() 释放内存
```

#### 内部类

| 类 | 作用 |
|----|------|
| `DataGroupKey` | (dataId, group) 组合键，不可变 |
| `IndexEntry` | 反向索引条目，提供 `findAffectedBeans()` 方法 |

---

### 第 7 步：`ConditionalRefreshListener.java` — 核心调度器

**路径**: `src/main/java/com/liu/conditionalrefresh/listener/ConditionalRefreshListener.java`

**阅读目标**: 理解配置变更如何驱动 Bean 刷新。

#### 启动注册流程

```
onApplicationEvent(ApplicationReadyEvent)
  ├─ ① isEnabled() 检查全局开关
  ├─ ② collector.isEmpty() 检查是否有 Bean 需要监听
  ├─ ③ collector.buildCommittedIndex(env) 构建最终索引
  └─ ④ 遍历 (dataId, group) 组合 → registerListener()
      ├─ 4a. fetchInitialSnapshot()  → 获取初始快照（避免首次全量刷新）
      ├─ 4b. new ListenerContext()    → 创建上下文（含快照、去抖器）
      ├─ 4c. addNacosListener(dataId, group)  → 注册原始 dataId 监听器
      └─ 4d. 若 file-extension 非空且 dataId 未以后缀结尾
          └─ addNacosListener(dataId + "." + ext, group)  → 同时注册带后缀监听器
```

#### file-extension 双监听器机制

Nacos 2.x 在 `file-extension: yaml` 时，实际存储/推送的 dataId 会追加 `.yaml` 后缀。框架通过以下机制兼容：

1. **注册阶段**：`registerListener()` 自动检测 `spring.cloud.nacos.config.file-extension`，若存在则同时注册 `dataId` 与 `dataId.fileExtension` 两个 Nacos 监听器
2. **回调阶段**：`getContext()` 先按原始 dataId 查找，若失败且 dataId 以扩展名后缀结尾，则去除后缀后回退查找

这保证了无论 Nacos 服务端以哪种 dataId 形式推送，框架均能正确路由到对应的 `ListenerContext`。

#### 运行时处理流程（每次配置变更）

```
receiveConfigInfo(configInfo)
  ↓
handleChange(dataId, group, configInfo)
  ├─ ① ConfigDiffUtils.parse()        → 解析新快照
  ├─ ② ctx.replaceSnapshotAndGetOld() → 原子替换快照（getAndSet）
  ├─ ③ ConfigDiffUtils.diff()         → 计算变更 keys
  ├─ ④ ctx.findAffectedBeans()        → 反向索引定位受影响 Bean
  └─ ⑤ debouncer.debounce()           → 去抖调度
      ↓ （延迟执行后）
  refreshBean(beanName)
      ├─ ReentrantLock.lock()          → Bean 级锁
      ├─ scope.refresh(beanName)       → 销毁旧实例
      └─ lock.unlock()
```

#### 并发安全机制

| 机制 | 作用 |
|------|------|
| `AtomicReference<Map>` (snapshot) | 保证快照读-写原子性，避免并发覆盖 |
| `ConcurrentHashMap` (contexts, beanLocks) | 线程安全的集合操作 |
| `ReentrantLock` (per-bean) | 同一 Bean 串行刷新，不同 Bean 并行 |
| `Debouncer` (per-context) | 抑制短时间内的重复刷新 |
| `volatile committedIndex` | 保证初始化时的内存可见性 |

---

### 第 8 步：工具类详解

#### 8.1 `ConfigDiffUtils.java` — 配置解析与 diff

**路径**: `src/main/java/com/liu/conditionalrefresh/listener/ConfigDiffUtils.java`

```
parse(configText)
  ├─ null/blank 检查
  ├─ isYamlStyle() 启发式判断格式
  ├─ parseYaml()  → YAMLPropertySourceLoader → flattenMap()
  │   └─ 失败 → log.warn() + 回退 parseProperties()
  └─ parseProperties() → java.util.Properties.load()

diff(oldSnapshot, newSnapshot)
  ├─ 遍历 newSnapshot
  │   ├─ old 不含 key → 新增 → 加入 changed
  │   └─ old 含 key 但值不等 → 修改 → 加入 changed
  └─ 旧键被删除 → 忽略（减少无效刷新）
```

#### 8.2 `Debouncer.java` — 去抖器

**路径**: `src/main/java/com/liu/conditionalrefresh/listener/Debouncer.java`

```
debounce(key, task)
  ├─ 检查 shutdown 状态
  ├─ 取消 previous 任务
  └─ scheduler.schedule(newTask, delay, unit)
      └─ 执行时:
          ├─ pending.remove(key)
          ├─ task.run()
          └─ 异常→ log.error() + recordFailure()
```

**为什么是多线程池调度器？**
- 默认线程池大小为 `max(2, CPU 核心数)`
- 不同 (dataId, group) 的刷新任务可并行执行
- 同一 key 的去重由 `pending ConcurrentHashMap` 保证
- 配合外层 Bean 级锁，形成完整的并发控制（同一 Bean 串行，不同 Bean 并行）

#### 8.3 `ListenerContext.java` — 监听器上下文

**路径**: `src/main/java/com/liu/conditionalrefresh/listener/ListenerContext.java`

```
每个 (dataId, group) 对应一个 ListenerContext 实例：
  ├─ lastSnapshot:  AtomicReference<Map>     ← 原子快照
  ├─ indexEntry:    IndexEntry               ← 反向索引（委托 findAffectedBeans）
  └─ debouncer:     Debouncer                ← 去抖器（多线程池）
```

#### 8.4 `RefreshFailedException.java` — 刷新失败异常

**路径**: `src/main/java/com/liu/conditionalrefresh/exception/RefreshFailedException.java`

- 携带 `beanName` 和原始 `cause`
- **`@Deprecated`**：当前版本未主动抛出（预留用于未来扩展）
- 框架当前策略：刷新失败 → log.error + Micrometer 指标 → 不阻塞其他 Bean

---

## 三、启动时序图

```
Spring Boot 启动
  │
  ├─ 扫描 @Configuration → ConditionalRefreshAutoConfiguration
  │   ├─ 满足 @ConditionalOnClass + @ConditionalOnBean + @ConditionalOnProperty
  │   └─ 注册 5 个 Bean
  │
  ├─ BeanFactoryPostProcessor 执行阶段
  │   ├─ ConditionalScopeRegistrar.postProcessBeanFactory()
  │   │   └─ registerScope("conditionalRefresh", scope)
  │   │
  │   └─ RefreshOnKeysPostProcessor.postProcessBeanDefinitionRegistry()
  │       ├─ 扫描 @RefreshOnKeys 注解
  │       ├─ 改写 Bean 定义（scope + proxyMode）
  │       └─ 收集元数据到 MetadataCollector
  │
  ├─ Bean 实例化阶段
  │   ├─ @RefreshOnKeys Bean → scoped-proxy 代理
  │   └─ 其他 Bean → 正常创建
  │
  └─ ApplicationReadyEvent 发布
      └─ ConditionalRefreshListener.onApplicationEvent()
          ├─ buildCommittedIndex()  → 解析占位符 + 构建反向索引
          ├─ fetchInitialSnapshot() → 获取初始快照
          └─ configService.addListener() → 注册 Nacos 监听器
```

---

## 四、运行时调用链路（完整）

```
                     Nacos Server
                         │
                   推送 config change
                         │
              ┌──────────▼──────────┐
              │  Listener.receive()  │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │    handleChange()    │
              │  ① parse 新快照       │
              │  ② replaceSnapshot   │
              │  ③ diff 计算变更 keys │
              │  ④ findAffectedBeans │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │  Debouncer.debounce()│  ← 500ms 去抖窗口
              └──────────┬──────────┘
                         │ (延迟后执行)
              ┌──────────▼──────────┐
              │   refreshBean()      │
              │  ReentrantLock.lock()│
              │  scope.refresh()     │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │ GenericScope.remove()│
              │  destroyMethod()    │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │  下次 proxy 访问      │
              │  GenericScope.get() │
              │  ObjectFactory 重建  │
              └─────────────────────┘
```

---

## 五、关键设计决策索引

| 决策 | 文件 | 原因 |
|------|------|------|
| 注解不含 `@Scope` | `RefreshOnKeys` | 由后处理器统一设置，避免遗漏 |
| 作用域实例注入而非 new | `ConditionalScopeRegistrar` | 避免注册器/监听器引用不同实例 |
| 初始快照 | `ConditionalRefreshListener` | 避免首次推送触发全量刷新 |
| 反向索引（key→beans） | `MetadataCollector` | O(1) 定位受影响 Bean |
| 原子快照替换 | `ListenerContext` | 避免并发覆盖导致 diff 丢失 |
| Bean 级锁而非全局锁 | `ConditionalRefreshListener` | 不同 Bean 可并行刷新 |
| 删除 key 不触发刷新 | `ConfigDiffUtils` | 减少无效刷新 |
| 惰性重建（不立即创建） | `ConditionalRefreshScope` | 不阻塞 Nacos 回调线程 |
| Micrometer 弱依赖 | `Debouncer`, `Listener` | 框架不要求必须引入 Micrometer |
| proxyMode 反射设置 | `RefreshOnKeysPostProcessor` | 兼容 Spring 5.x 全系列 |
| file-extension 双监听器 | `ConditionalRefreshListener` | 兼容 Nacos 2.x 自动追加扩展名行为 |

---

## 六、测试对应关系

| 测试类 | 覆盖模块 | 关键测试点 |
|--------|----------|------------|
| `ConfigDiffUtilsTest` | `ConfigDiffUtils` | Properties/YAML 解析、diff 语义、空配置边界（11 用例） |
| `DebouncerTest` | `Debouncer` | 正常执行、去抖去重、异常不阻断、shutdown 行为（6 用例） |
| `DebouncerConcurrencyTest` | `Debouncer` 并发 | 不同 key 并行、同 key 去重、单线程池退化（3 用例） |
| `MetadataCollectorTest` | `MetadataCollector` | 空检查、占位符解析、反向索引正确性、重复构建保护（9 用例） |
| `ConditionalRefreshScopeTest` | `ConditionalRefreshScope` | 常量验证、不存在 Bean、空/null 名称、惰性重建（7 用例） |
| `ListenerContextTest` | `ListenerContext` | 委托行为、单 key 影响、未监听 key、快照原子替换（7 用例） |
| `ConditionalRefreshListenerTest` | `ConditionalRefreshListener` | 监听器注册、配置变更触发、全局开关、close 释放（5 用例） |
| `ConditionalRefreshEndToEndTest` | 集成测试（Mock） | 7 个场景全覆盖（Mock Nacos + 可变 PropertySource） |
| `ConditionalRefreshE2ETest` | 端到端测试（真实 Nacos） | 7 个场景、真实 `ConfigService.publishConfig()` 推送 |

---

## 七、扩展点速查

| 需求 | 扩展方式 |
|------|----------|
| 替换默认作用域实现 | `@Bean @ConditionalOnMissingBean ConditionalRefreshScope` |
| 替换后处理器逻辑 | `@Bean @ConditionalOnMissingBean RefreshOnKeysPostProcessor` |
| 替换监听器实现 | `@Bean @ConditionalOnMissingBean ConditionalRefreshListener` |
| 禁用整个框架 | `conditional.refresh.enabled: false` |
| 调整去抖窗口 | `conditional.refresh.debounce.delay: 1000` |
| 自定义监控指标 | 注入 `MeterRegistry`，Micrometer 会自动集成 |

---

## 八、常见调试日志速查

| 日志级别 | 消息模式 | 含义 |
|----------|----------|------|
| INFO | `Custom scope 'conditionalRefresh' registered...` | 作用域注册成功 |
| INFO | `Conditional refresh auto-configuration activated...` | 自动配置已启用 |
| INFO | `Registered Nacos listener for dataId=...` | 监听器注册成功 |
| INFO | `Config changed for dataId=...: [keys]` | 检测到配置变更 |
| INFO | `Affected beans for changed keys ...` | 定位到受影响 Bean |
| WARN | `Key '...' not found in current Environment` | 注解引用的 key 不存在（可能后续引入） |
| WARN | `YAML parsing failed, falling back to Properties...` | 格式解析降级 |
| WARN | `Failed to fetch initial snapshot...` | 初始快照获取失败 |
| DEBUG | `Bean '...' registered for conditional refresh` | 单个 Bean 被扫描并改写 |
| ERROR | `Failed to register Nacos listener...` | 监听器注册异常 |
| ERROR | `Failed to refresh bean '...'` | 刷新过程异常 |

---

> **阅读建议**：按本指南顺序阅读源码，每步先理解数据结构和调用方向，再关注异常处理与并发控制细节。
