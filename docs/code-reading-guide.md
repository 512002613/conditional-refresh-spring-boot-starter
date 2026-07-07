# 条件刷新框架 — 原理与代码解读

> 本文档面向**初学者和希望深入理解框架设计的新人开发者**，用通俗的语言讲解 `@RefreshOnKeys` 条件刷新框架的设计动机、核心原理、关键组件和代码实现。
>
> 阅读完本文后，你应能回答：
> - 为什么需要"条件刷新"？它和 Spring Cloud 自带的 `@RefreshScope` 有什么本质区别？
> - 框架是如何知道"哪些 Bean 受哪些配置 Key 影响"的？
> - 配置变更后，框架如何精准地只刷新受影响的 Bean？
> - 为什么必须监听 `EnvironmentChangeEvent` 而不是直接监听 Nacos 推送？

---

## 目录

1. [先搞懂背景：什么是配置刷新？](#一先搞懂背景什么是配置刷新)
2. [核心概念速览](#二核心概念速览)
3. [框架能做什么？](#三框架能做什么)
4. [整体架构一览](#四整体架构一览)
5. [代码阅读顺序（由浅入深）](#五代码阅读顺序由浅入深)
6. [运行时完整调用链路](#六运行时完整调用链路)
7. [关键设计决策解读](#七关键设计决策解读)
8. [测试覆盖速查](#八测试覆盖速查)

---

## 一、先搞懂背景：什么是配置刷新？

### 1.1 问题场景

想象你有一个 Spring Boot 应用，连接了腾讯云的 COS 存储。COS 的密钥配置在 Nacos 配置中心：

```yaml
cos:
  tencent:
    secretId:  AKVxxx...
    secretKey: zzzzzz...
```

应用中有一个 `COSClient` Bean，启动时读取这两个密钥创建连接：

```java
@Bean
public COSClient cosClient(Environment env) {
    String id  = env.getProperty("cos.tencent.secretId");
    String key = env.getProperty("cos.tencent.secretKey");
    return new COSClient(id, key);  // 创建连接池
}
```

**痛点**：有一天密钥泄漏了，你在 Nacos 上更新了密钥。但应用里的 `COSClient` 还是用旧密钥创建的连接——它**不知道**配置变了，除非重启应用。

**传统做法**：每次改配置就重启应用。但重启意味着：
- 服务短暂不可用
- 所有 Bean 都要重建（包括那些跟配置变更无关的）
- 连接池、线程池等重资源反复销毁创建

### 1.2 Spring Cloud 的解决方案：`@RefreshScope`

Spring Cloud 提供了 `@RefreshScope` 注解。标记了这个注解的 Bean，在环境变更时会被销毁重建：

```java
@Bean
@RefreshScope  // 任何环境变更都会触发这个 Bean 重建
public COSClient cosClient(Environment env) { ... }
```

**新问题**：`@RefreshScope` 是"无脑全刷"——**任意**一个配置 key 变了，所有标记了 `@RefreshScope` 的 Bean 都会被重建。

假设你有 100 个 `@RefreshScope` Bean，其中一个连 Redis 的 Bean 和 COS 的 Bean 都在里面。你改了 COS 的密钥，Redis 的连接池也被销毁重建了——这完全没必要！

### 1.3 本框架的方案：`@RefreshOnKeys`

**核心思路**：让每个 Bean 声明"我只对这几个配置 Key 感兴趣"，只有这些 Key 变更时才重建我：

```java
@Bean
@RefreshOnKeys({"cos.tencent.secretId", "cos.tencent.secretKey"})
public COSClient cosClient(Environment env) { ... }
```

- 改了 `cos.tencent.secretId` → ✅ 重建 `COSClient`
- 改了 `redis.password` → ❌ 不重建 `COSClient`

这就是"条件刷新"——按条件（配置 Key）决定是否刷新。

---

## 二、核心概念速览

在深入代码前，先统一术语：

| 概念 | 通俗解释 |
|------|----------|
| **Nacos** | 阿里云/Alibaba 开源的配置中心。应用从 Nacos 拉取配置，Nacos 推送配置变更 |
| **PropertySource** | Spring 内部管理配置的"数据源"。Nacos 推送后，Spring 先更新 PropertySource |
| **EnvironmentChangeEvent** | Spring Cloud 在 PropertySource 更新**后**发布的事件，携带"哪些 key 变了" |
| **scoped-proxy** | Spring 的一种代理模式。每次访问代理对象时，从作用域缓存中取真实实例 |
| **GenericScope** | Spring Cloud 提供的作用域基础设施，`@RefreshScope` 底层就是它 |
| **反向索引** | 框架的核心数据结构：`key → Set<beanName>`。从"变更的 key"反查"受影响的 Bean" |
| **去抖（Debounce）** | 短时间内多次触发只执行最后一次。类似电梯关门——最后一个人进来后等几秒才关 |
| **Bean 级锁** | 每个 Bean 一把独立锁。不同 Bean 可并行刷新，同一 Bean 不会并发刷新 |

---

## 三、框架能做什么？

### 3.1 两种监听模式

**精确模式**——显式列出每个监听的 key：

```java
@Bean
@RefreshOnKeys({"cos.tencent.secretId", "cos.tencent.secretKey"})
public COSClient cosClient(
    @Value("${cos.tencent.secretId}") String id,
    @Value("${cos.tencent.secretKey}") String key) {
    return new COSClient(id, key);
}
```

**前缀模式**——监听某个前缀下的所有 key（常用于 `@ConfigurationProperties`）：

```java
@ConfigurationProperties(prefix = "channel.sign")
public class ChannelSignProperties {
    private String secret;
    private String token;
    // getters/setters
}

@Bean
@RefreshOnKeys(prefix = "channel.sign")  // 任何 channel.sign.* 变更都触发
public ChannelSignService channelSignService(ChannelSignProperties props) {
    return new ChannelSignService(props.getSecret(), props.getToken());
}
```

### 3.2 与 `@RefreshScope` 的关系

```
┌──────────────────────────────────────────────────────┐
│              Spring 容器中的 Bean                      │
│                                                        │
│  ┌─────────────────┐    ┌─────────────────────────┐  │
│  │ @RefreshScope    │    │ @RefreshOnKeys           │  │
│  │ 任意配置变更 → 刷 │    │ 只有指定 key 变更 → 刷   │  │
│  │ 作用域: refresh  │    │ 作用域: conditionalRefresh│  │
│  └─────────────────┘    └─────────────────────────┘  │
│                                                        │
│  ┌─────────────────────────────────────────────────┐  │
│  │ 普通 Bean（无注解）— 永远不随配置变更重建          │  │
│  └─────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

两种注解**不能同时标记**同一个 Bean（启动时会报错）。

### 3.3 行为矩阵

| 触发场景 | 普通 Bean | `@RefreshScope` Bean | `@RefreshOnKeys` Bean |
|---------|-----------|---------------------|----------------------|
| Nacos 配置 A 变更 | 不动 | ✅ 重建 | 仅当监听 A 时重建 |
| Nacos 配置 B 变更 | 不动 | ✅ 重建 | 仅当监听 B 时重建 |
| 应用启动 | 创建一次 | 创建一次 | 创建一次（惰性） |

---

## 四、整体架构一览

```
阶段 1：编译期（开发者做的事）
═══════════════════════════════════════════════════════════════

  用 @RefreshOnKeys 标记 Bean，声明它依赖哪些配置 Key


阶段 2：Spring 容器启动（自动配置 + 扫描改写）
═══════════════════════════════════════════════════════════════

  ConditionalRefreshAutoConfiguration
    │  检测到 classpath 有 NacosConfigManager → 激活
    │
    ├─ 注册 ConditionalRefreshScope（作用域 Bean）
    ├─ 注册 ConditionalScopeRegistrar（把 scope 注册到容器）
    ├─ 注册 RefreshOnKeysPostProcessor（BDRPP，扫描改写 Bean 定义）
    └─ 注册 ConditionalRefreshListener（事件监听器）

  RefreshOnKeysPostProcessor.postProcessBeanDefinitionRegistry()
    │  遍历所有 Bean 定义，找到标记了 @RefreshOnKeys 的
    │
    ├─ 改写：scope = "conditionalRefresh"
    ├─ 改写：创建 CGLIB scoped-proxy（代理模式）
    ├─ 校验：不得同时标记 @RefreshScope
    ├─ 校验：value 和 prefix 互斥
    └─ 收集元数据 → MetadataCollector
         dataId → group → (beanName → {keys, prefix})


阶段 3：Bean 实例化
═══════════════════════════════════════════════════════════════

  @RefreshOnKeys Bean → 创建 scoped-proxy 代理对象
  真实目标对象：惰性 —— 首次通过 proxy 访问时才创建
  （GenericScope.get() 内部缓存创建好的实例）


阶段 4：ApplicationReadyEvent（环境就绪）
═══════════════════════════════════════════════════════════════

  ConditionalRefreshListener.onApplicationReady()
    │
    └─ MetadataCollector.buildCommittedIndex(environment)
         ├─ 解析占位符 ${...}
         ├─ 空 dataId → spring.application.name
         ├─ 空 group  → DEFAULT_GROUP
         └─ 构建双索引：
              dataId → group → IndexEntry {
                  keyToBeanNames:    key → Set<beanName>      (精确)
                  prefixToBeanNames: prefix → Set<beanName>   (前缀)
             }


阶段 5：运行时（配置变更触发刷新）
═══════════════════════════════════════════════════════════════

  Nacos 推送
    │
    ▼
  NacosConfigDataLoader 更新 PropertySource
    │
    ▼
  ContextRefresher.refresh() → publishEvent(EnvironmentChangeEvent)
    │
    ├─ ConfigurationPropertiesRebinder.rebind() — 先 rebind @ConfigurationProperties
    │
    ▼
  ConditionalRefreshListener.onEnvironmentChanged()     ← 之后才轮到我们
    │  (Ordered.LOWEST_PRECEDENCE 保证顺序)
    │
    ├─ event.getKeys() → Set<changedKeys>
    ├─ IndexEntry.findAffectedBeans(changedKeys) → Set<affectedBeans>
    │    ├─ 精确匹配：changedKey 在 keyToBeanNames 中
    │    └─ 前缀匹配：changedKey.startsWith(prefix + ".")
    │
    └─ 按 Bean：debouncer.debounce(beanName, refreshTask)
         │
         ▼  (500ms 去抖窗口后)
    refreshBean(beanName)
         ├─ ReentrantLock.lock()（Bean 级锁）
         ├─ scope.refresh(beanName)
         │    └─ super.remove("scopedTarget." + beanName) → destroyMethod
         └─ 返回
              │
              ▼
         下次通过 proxy 访问该 Bean
              │
              ▼
         GenericScope.get() → ObjectFactory.getObject() → 创建新实例
              │
              (此时读到的是 PropertySource 中的新值 ✓)
```

---

## 五、代码阅读顺序（由浅入深）

建议按以下顺序阅读源码，每一步都有明确的"阅读目标"和"你需要理解的问题"。

### 第 1 步：`RefreshOnKeys.java` — 注解定义

**路径**: `annotation/RefreshOnKeys.java`

**阅读目标**: 理解框架对外暴露的 API。

**你需要理解的问题**：
- 注解可以标在哪里？（类 or 方法）
- 两种模式如何区分？互斥规则是什么？
- 为什么注解本身不含 `@Scope`？

**关键代码**：
```java
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RefreshOnKeys {
    String[] value() default {};     // 精确模式
    String prefix() default "";      // 前缀模式（与 value 互斥）
    String dataId() default "";
    String group() default "";
}
```

**设计决策**：注解不含 `@Scope`，作用域由后处理器自动设置。这样做的原因：
1. 避免用户忘记手动加 `@Scope("conditionalRefresh")`
2. 避免与 `@RefreshScope` 语义混淆
3. 后处理器可以在扫描阶段灵活控制处理顺序

---

### 第 2 步：`ConditionalRefreshProperties.java` — 配置绑定

**路径**: `config/ConditionalRefreshProperties.java`

**阅读目标**: 了解所有可配置项及其默认值。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `conditional.refresh.enabled` | `true` | 全局开关 |
| `conditional.refresh.debounce.delay` | `500` ms | 去抖静默窗口 |
| `conditional.refresh.metrics-enabled` | `true` | 是否暴露 Micrometer 指标 |
| `conditional.refresh.initial-snapshot-enabled` | `true` | 旧版兼容属性 |

---

### 第 3 步：`ConditionalRefreshAutoConfiguration.java` — 自动配置

**路径**: `config/ConditionalRefreshAutoConfiguration.java`

**阅读目标**: 理解框架的 Bean 装配条件和顺序。

**你需要理解的问题**：
- 什么条件下框架才会激活？
- 各个 Bean 注册的顺序有什么讲究？
- 用户如何替换框架的默认实现？

**装配条件（同时满足）**：
1. classpath 存在 `NacosConfigManager`
2. Spring 容器中已有 `NacosConfigManager` Bean
3. `conditional.refresh.enabled` 不为 `false`

**关键理解**：
- 所有 Bean 都标注了 `@ConditionalOnMissingBean`——意味着用户只要自己声明了同名 Bean，就能替换框架默认实现
- `@AutoConfigureAfter(NacosConfigAutoConfiguration)` 保证 Nacos 配置管理器先注册

---

### 第 4 步：`ConditionalRefreshScope.java` + `ConditionalScopeRegistrar.java`

**路径**:
- `scope/ConditionalRefreshScope.java`
- `scope/ConditionalScopeRegistrar.java`

**阅读目标**: 理解自定义作用域的生命周期。

#### 4.1 `ConditionalScopeRegistrar` — 作用域注册器

它的任务只有一个：在 Spring 容器初始化阶段把 `ConditionalRefreshScope` 实例注册为名为 `"conditionalRefresh"` 的作用域。

```java
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    beanFactory.registerScope("conditionalRefresh", scope);
}
```

**为什么要单独一个注册器**？因为作用域必须在任何 Bean **实例化之前**注册。通过 `BeanFactoryPostProcessor` 可以精确控制这个时机。

#### 4.2 `ConditionalRefreshScope` — 作用域实现

继承 Spring Cloud 的 `GenericScope`，复用了 Bean 创建、缓存、`destroyMethod` 等基础设施。

**核心方法 `refresh(String beanName)`**：

```java
public boolean refresh(String beanName) {
    // 1. 移除旧实例（执行 destroyMethod）
    Object oldInstance = removeBeanSafely(beanName);
    
    if (oldInstance == null) {
        // 检查 survivor cache（跨 context restart 场景）
        Object survivor = SURVIVOR_CACHE.remove(beanName);
        if (survivor != null) { return true; }
        
        // 缓存中无该 Bean → 不是失败，配置变更下次 proxy 访问时生效
        return true;
    }
    
    // 2. 旧实例已销毁，新实例尚未创建
    //    真正的重建将在下次通过 scoped-proxy 访问时触发
    return true;
}
```

**你需要理解的关键点**：
- `refresh()` **只销毁、不创建**——新实例是惰性创建的
- `GenericScope` 内部用 `"scopedTarget." + beanName` 作为缓存 key，所以 `removeBeanSafely()` 需要手动拼这个前缀
- `SURVIVOR_CACHE` 是个静态 Map，用于跨 context restart 场景（SC 2021.0.x 全量 restart 会销毁旧 scope 实例，但是监听器里记住的 beanName 还在）

**惰性重建的好处**：
1. 不阻塞事件发布线程
2. 对齐原生 `RefreshScope` 的行为
3. 自然配合去抖（快速多次变更只触发一次最终访问）

---

### 第 5 步：`RefreshOnKeysPostProcessor.java` — Bean 定义扫描改写

**路径**: `processor/RefreshOnKeysPostProcessor.java`

**阅读目标**: 理解 `@RefreshOnKeys` 如何被识别、改写和收集。

**你需要理解的问题**：
- 这个后处理器在 Spring 生命周期的什么阶段运行？
- 为什么改写 Bean 定义而不是改写已创建的实例？
- scoped-proxy 是如何创建的？

**运行时机**：`BeanDefinitionRegistryPostProcessor` 阶段——在所有 Bean 定义加载后、任何 Bean **实例化之前**。

**处理流程**：

```
postProcessBeanDefinitionRegistry()
  │
  ├─ 遍历所有 Bean 定义
  │   └─ processBeanDefinition(beanName, bd)
  │       ├─ ① findAnnotation(bd)         → 找到 @RefreshOnKeys
  │       │   ├─ @Bean 方法 → 读工厂方法元数据
  │       │   └─ @Component 类 → 读类级元数据
  │       │
  │       ├─ ② checkRefreshScopeConflict()  → 互斥校验
  │       │
  │       ├─ ③ rewriteBeanDefinition()      → 改写 scope + 创建 proxy
  │       │   ├─ abd.setScope("conditionalRefresh")
  │       │   └─ 创建 CGLIB scoped-proxy（通过反射调用 ScopedProxyCreator）
  │       │
  │       ├─ ④ 互斥校验（value vs prefix）
  │       │
  │       └─ ⑤ collector.add(...)           → 收集元数据
```

**核心反射逻辑**：

```java
// ScopedProxyCreator 是 package-private 的，需要反射调用
Class<?> creatorClass = Class.forName(
    "org.springframework.context.annotation.ScopedProxyCreator");
Method method = creatorClass.getMethod(
    "createScopedProxy", BeanDefinitionHolder.class,
    BeanDefinitionRegistry.class, boolean.class);
BeanDefinitionHolder proxyHolder = 
    (BeanDefinitionHolder) method.invoke(null, holder, registry, true);
```

**为什么要用 `ScopedProxyCreator` 而不是直接 `setProxyMode()`**：
- 直接 `setProxyMode()` 在部分 Spring 版本中因为 Bean 覆盖限制无法正确创建代理
- `ScopedProxyCreator.createScopedProxy()` 是 Spring 创建 scoped-proxy 的标准方法，内部处理了所有细节

**改写后的 Bean 定义结构**：

```
注册表中有两个定义：
  "channelSignService"          → ScopedProxyFactoryBean（代理）
  "scopedTarget.channelSignService" → 原始定义（目标）

注入点拿到的是代理对象，每次方法调用时：
  代理.intercept() → GenericScope.get("scopedTarget.channelSignService")
                   → 从缓存取真实实例（或惰性创建）
```

---

### 第 6 步：`MetadataCollector.java` — 元数据收集器

**路径**: `processor/MetadataCollector.java`

**阅读目标**: 理解反向索引的数据结构和构建时机。

**你需要理解的问题**：
- 为什么需要"两阶段"设计（先 raw 后 committedIndex）？
- 反向索引具体长什么样？
- 前缀匹配是怎么实现的？

#### 数据结构

```
原始数据（raw）—— BDRPP 阶段收集
  dataId → group → (beanName → RawEntry{keys, prefix})

反向索引（committedIndex）—— ApplicationReadyEvent 后构建
  dataId → group → IndexEntry {
      keyToBeanNames:    key → Set<beanName>      ← 精确匹配
      prefixToBeanNames: prefix → Set<beanName>   ← 前缀匹配
  }
```

#### 两阶段设计的原因

BDRPP 阶段 Environment 还没完全就绪（Nacos 配置还没加载），此时无法解析 `${...}` 占位符。所以：
- 第一阶段：轻量收集原始字符串，不解析
- 第二阶段（ApplicationReadyEvent）：Environment 就绪后再解析占位符、构建索引

#### 查找受影响 Bean 的逻辑

```java
public Set<String> findAffectedBeans(Set<String> changedKeys) {
    Set<String> affected = new HashSet<>();
    
    // 1. 精确匹配：changedKey 在 keyToBeanNames 中
    for (String key : changedKeys) {
        Set<String> beans = keyToBeanNames.get(key);
        if (beans != null) affected.addAll(beans);
    }
    
    // 2. 前缀匹配：changedKey 以某个 prefix + "." 开头
    if (!prefixToBeanNames.isEmpty()) {
        for (String changedKey : changedKeys) {
            for (prefixEntry : prefixToBeanNames.entrySet()) {
                if (changedKey.startsWith(prefixEntry.getKey() + ".")) {
                    affected.addAll(prefixEntry.getValue());
                }
            }
        }
    }
    return affected;
}
```

**举例**：
- Bean A 标记 `@RefreshOnKeys({"channel.sign.secret"})` → keyToBeanNames: `{"channel.sign.secret" → {A}}`
- Bean B 标记 `@RefreshOnKeys(prefix = "channel.sign")` → prefixToBeanNames: `{"channel.sign" → {B}}`
- 变更 key = `channel.sign.secret` → A（精确匹配）+ B（前缀匹配）→ 两个都刷

---

### 第 7 步：`ConditionalRefreshListener.java` — 核心调度器

**路径**: `listener/ConditionalRefreshListener.java`

**阅读目标**: 理解配置变更如何驱动 Bean 刷新。

**你需要理解的问题**：
- 框架现在如何感知配置变更？（对比旧版直接监听 Nacos SDK 有什么不同）
- 为什么监听器必须在 `ConfigurationPropertiesRebinder` 之后执行？
- 两个事件（EnvironmentChangeEvent / RefreshScopeRefreshedEvent）分别在什么场景触发？

#### 事件监听机制

```java
public class ConditionalRefreshListener
        implements SmartApplicationListener, Ordered, AutoCloseable {
    
    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return ApplicationReadyEvent.class.isAssignableFrom(eventType)
            || EnvironmentChangeEvent.class.isAssignableFrom(eventType)
            || RefreshScopeRefreshedEvent.class.isAssignableFrom(eventType);
    }
}
```

**三个事件各有用途**：

| 事件 | 何时触发 | 处理逻辑 |
|------|----------|----------|
| `ApplicationReadyEvent` | 应用启动完成 | 构建反向索引 |
| `EnvironmentChangeEvent` | PropertySource 更新后（SC 2022.0.x+） | 精准刷新受影响 Bean |
| `RefreshScopeRefreshedEvent` | RefreshScope.refreshAll() 完成后（SC 2021.0.x fallback） | 全量刷新所有 Bean |

#### 为什么是 EnvironmentChangeEvent 而不是直接监听 Nacos SDK？

这是框架最近一次重大重构的核心原因——**时序倒置 bug**：

```
旧实现的问题：
  Nacos 推送 → configService.addListener 回调 → 立即销毁 Bean
       ↑ 此时 PropertySource 还没更新（要等 190ms）
       ↑ 新实例惰性创建时读到的还是旧值 → 条件刷新白做

新实现（PropertySource-First）：
  Nacos 推送 → NacosConfigDataLoader 更新 PropertySource
            → ContextRefresher.refresh()
            → publishEvent(EnvironmentChangeEvent)
            → ConditionalRefreshListener.onEnvironmentChanged()
            → 销毁 Bean → 下次访问读新值 ✓
```

#### 监听器顺序的重要性

```java
@Override
public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;  // 整数最大值 → 最后执行
}
```

Spring 的事件派发是**同步顺序**的。`EnvironmentChangeEvent` 到达时：

```
publishEvent(EnvironmentChangeEvent)
  │
  ├─ ConfigurationPropertiesRebinder.rebind()  ← 先执行（rebind @ConfigurationProperties）
  │
  └─ ConditionalRefreshListener.onEnvironmentChanged()  ← 后执行（我们的顺序值最大）
```

**为什么要在 rebinder 之后？** 因为 `@RefreshOnKeys` 前缀模式的典型用法是注入 `@ConfigurationProperties` Bean 作为工厂参数。如果我们的 listener 先执行，rebinder 还没发生，新实例拿到的 Properties Bean 还是旧值。

#### SC 2021.0.x fallback 路径

```java
public void onRefreshScopeRefreshed(RefreshScopeRefreshedEvent event) {
    // 主路径已处理 → 跳过，避免重复刷新
    if (environmentChangeEventHandled) {
        environmentChangeEventHandled = false;
        return;
    }
    // SC 2021.0.x 无法从 EnvironmentChangeEvent 获取有效 keys
    // 降级方案：全量刷新所有 @RefreshOnKeys Bean
    Set<String> allBeans = collector.getAllBeanNames();
    for (String beanName : allBeans) {
        debouncer.debounce(beanName, () -> refreshBean(beanName));
    }
}
```

#### 去抖 + 锁的并发控制

```java
private void refreshBean(String beanName) {
    ReentrantLock lock = beanLocks.computeIfAbsent(beanName, k -> new ReentrantLock());
    lock.lock();
    try {
        boolean effective = scope.refresh(beanName);  // 销毁旧实例
        if (effective) {
            recordSuccess(beanName);  // Micrometer 指标
        } else {
            recordFailure(beanName);
        }
    } finally {
        lock.unlock();
    }
}
```

- **Debouncer**（外层）：500ms 窗口内多次触发只执行最后一次
- **ReentrantLock**（内层）：保证同一 Bean 不并发刷新，不同 Bean 可并行

---

### 第 8 步：`Debouncer.java` — 去抖器

**路径**: `listener/Debouncer.java`

**阅读目标**: 理解去抖的实现和线程模型。

**你需要理解的问题**：
- 去抖和"防抖"是同一个东西吗？
- 为什么用多线程池而不是单线程？
- 任务抛异常会怎样？

#### 核心逻辑

```java
public void debounce(String key, Runnable task) {
    // 1. 取消同一 key 之前未执行的任务
    ScheduledFuture<?> prev = pending.get(key);
    if (prev != null) prev.cancel(false);
    
    // 2. 调度新的任务（delay 后执行）
    ScheduledFuture<?> future = scheduler.schedule(() -> {
        pending.remove(key);
        try {
            task.run();
        } catch (Exception e) {
            log.error("...", e);
            recordFailure(key);  // 异常不阻断后续任务
        }
    }, delay, unit);
    
    pending.put(key, future);
}
```

**为什么异常要捕获而不是抛出？** 因为任务在 `ScheduledExecutorService` 中运行，未捕获的异常会导致该 slot 永远"消失"，后续调度全部丢失。

#### 线程模型

- 线程池大小 = `max(2, CPU 核心数)`
- 不同 Bean 的刷新任务可**并行**
- 同一 Bean 的去重由 `pending` ConcurrentHashMap 保证
- 同一 Bean 的并发控制由外层 `ReentrantLock` 保证

---

### 第 9 步：`ConfigDiffUtils.java` — 配置解析与 diff

**路径**: `listener/ConfigDiffUtils.java`

**阅读目标**: 了解配置格式解析和 diff 计算逻辑。

**注意**：这个工具类是早期版本的产物。在新架构下（监听 `EnvironmentChangeEvent`），框架不再需要自己解析配置和计算 diff——Spring 的 `ContextRefresher` 已经帮你做完了，通过 `EnvironmentChangeEvent.getKeys()` 直接拿到变更的 keys。

保留这个类是为了兼容性，未来可能移除。

---

## 六、运行时完整调用链路

下面是配置变更从 Nacos 推送到 Bean 成功刷新的**完整时序**，包含每一步的日志输出：

```
时间轴 →

Nacos 服务端
  │  推送配置变更
  ▼
NacosConfigDataLoader（Spring Cloud Alibaba）
  │  更新 PropertySource
  ▼
ContextRefresher.refresh()
  │  refreshEnvironment(): 更新 PropertySource + publishEvent(EnvironmentChangeEvent)
  ▼
SimpleApplicationEventMulticaster 顺序派发
  │
  ├─ [1] ConfigurationPropertiesRebinder.onApplicationEvent()
  │      rebind 所有 @ConfigurationProperties Bean
  │      日志: o.s.c.c.properties.ConfigurationPropertiesRebinders : Rebinding
  │
  └─ [2] ConditionalRefreshListener.onEnvironmentChanged() ← 我们
         │
         ├─ event.getKeys() → [channel.sign.secret]
         │   DEBUG: Environment changed, keys: [channel.sign.secret]
         │
         ├─ findAffectedBeans([channel.sign.secret])
         │   ├─ 精确匹配：channelSignService（value 模式）
         │   └─ 前缀匹配：channelSignService（prefix="channel.sign"）
         │   INFO: Affected beans for changed keys [channel.sign.secret]: [channelSignService]
         │
         ├─ debouncer.debounce("channelSignService", refreshTask)
         │   └─ 调度 500ms 后执行
         │
         └── (500ms 后)
              │
              ▼
         refreshBean("channelSignService")
              ├─ lock.lock()
              ├─ scope.refresh("channelSignService")
              │   └─ super.remove("scopedTarget.channelSignService")
              │       └─ destroyMethod 执行（如 close/shutdown）
              │   INFO: Bean 'channelSignService' destroyed in conditional refresh scope.
              ├─ recordSuccess("channelSignService")
              │   └─ conditional.refresh.success +1 (Micrometer)
              └─ lock.unlock()
              
  ════════════════════════════════════════════════════════════════
  此时旧实例已销毁，但新实例尚未创建
  ════════════════════════════════════════════════════════════════
  
  下次有代码通过 proxy 访问 channelSignService：
  │  代理.intercept() → GenericScope.get("scopedTarget.channelSignService")
  │
  ▼
  ObjectFactory.getObject()
      │  执行工厂方法：channelSignService(channelSignProperties)
      │  此时 channelSignProperties 已 rebind → 拿到新值 ✓
      ▼
  新实例创建并缓存到 scope
```

---

## 七、关键设计决策解读

### 决策 1：为什么监听 Spring 事件而不是 Nacos SDK 回调？

**问题**：Nacos SDK 的 `configService.addListener()` 在 Spring 更新 PropertySource **之前**就触发回调，导致 Bean 销毁重建时读到旧值。

**本质**：Nacos 推送和 Spring 配置更新是两个独立步骤。SDK 回调只告诉你"Nacos 存储变了"，但不等于"Spring 环境变了"。

**正确做法**：监听 Spring Cloud 发布的 `EnvironmentChangeEvent`——这是 PropertySource 更新**之后**才发布的操作。

### 决策 2：为什么监听器必须在 `ConfigurationPropertiesRebinder` 之后？

**问题**：前缀模式的典型用法是整个 `@ConfigurationProperties` Bean 注入工厂方法。如果我们的 listener 先执行，`@ConfigurationProperties` Bean 还没 rebind，新实例拿到旧值。

**解法**：`getOrder()` 返回 `Ordered.LOWEST_PRECEDENCE`（整数最大值）→ 在所有"正常"监听器之后执行。

### 决策 3：为什么用 scoped-proxy 而不是直接持有引用？

**问题**：Spring 注入的 Bean 引用如果是普通引用，永远是同一个对象。需要一种机制让"下次访问时自动拿到新实例"。

**解法**：scoped-proxy 是 Spring 的标准解法。注入点拿到的是代理对象，每次方法调用时从作用域缓存中取真实实例。这样 Bean 销毁（`refresh()`）后，下次调用 proxy 就会触发重新创建。

### 决策 4：为什么只销毁不立即重建？

1. **不阻塞事件线程**——事件发布是同步的，如果在这里创建新实例（可能涉及建连、IO），会阻塞所有后续监听器
2. **对齐原生 `RefreshScope` 语义**——原生也是惰性重建
3. **自然配合去抖**——快速多次变更只触发一次最终重建

### 决策 5：为什么需要"两阶段"构建索引？

BDRPP 阶段（`add()`）Environment 还没完全就绪（Nacos 远程配置尚未加载），此时 `${...}` 占位符无法解析。所以：
- 第一阶段：轻量收集原始字符串
- 第二阶段（`ApplicationReadyEvent`）：Environment 就绪后统一解析、构建索引

### 决策 6：为什么 SC 2021.0.x 要降级为全量刷新？

SC 2021.0.x 使用 `LegacyContextRefresher`，在配置变更时执行**全量 context restart**（创建新 `SpringApplication` 并启动新 context）。这个过程中：
- 旧 context 被整个废弃
- `EnvironmentChangeEvent` 在新 context 中不会再次发布
- 条件监听器无法获取 changedKeys

由于全量 restart 本身就会重建所有 Bean，条件刷新的"精确"优势在该版本本就打折。降级为"全量刷新所有 `@RefreshOnKeys` Bean"是合理的兼容方案。

---

## 八、测试覆盖速查

### 单元测试（starter 模块）

| 测试类 | 覆盖什么 | 关键场景 |
|--------|----------|----------|
| `ConfigDiffUtilsTest` | 配置解析与 diff | Properties/YAML 解析、diff 语义、空配置边界 |
| `DebouncerTest` | 去抖器基础 | 正常执行、去抖去重、异常不阻断、shutdown 行为 |
| `DebouncerConcurrencyTest` | 去抖器并发 | 不同 key 并行、同 key 去重、单线程池退化 |
| `MetadataCollectorTest` | 元数据收集 | 空检查、占位符解析、反向索引正确性、双索引 prefix 模式 |
| `ConditionalRefreshScopeTest` | 作用域生命周期 | 常量验证、不存在 Bean、空/null 名称、惰性重建、scopedTarget 前缀 |
| `ConditionalRefreshListenerTest` | 监听器基础 | 监听器注册、配置开关、close 释放 |
| `ConditionalRefreshListenerRefreshEventTest` | 事件驱动逻辑 | 精确匹配、前缀匹配、混合模式、空变更跳过、fallback 全量刷新、开关关闭 |

### 集成测试（test-sample 模块）

| 测试类 | 覆盖什么 | 关键场景 |
|--------|----------|----------|
| `ConditionalRefreshSampleTest` | 真实 Nacos 端到端 | Context 加载、索引构建、精准刷新、多组独立、destroyMethod |

---

## 附录 A：常见调试日志速查

| 日志级别 | 消息模式 | 含义 |
|----------|----------|------|
| INFO | `Custom scope 'conditionalRefresh' registered...` | 作用域注册成功 |
| INFO | `Conditional refresh auto-configuration activated...` | 自动配置已启用 |
| INFO | `Conditional refresh listeners initialized...` | 监听器初始化完成 |
| INFO | `Environment changed, keys: [...]` | 检测到配置变更 |
| INFO | `Affected beans for changed keys ...: [...]` | 定位到受影响 Bean |
| INFO | `Bean '...' destroyed in conditional refresh scope.` | 旧实例已销毁 |
| WARN | `Key '...' not found in current Environment` | 注解引用的 key 不存在 |
| DEBUG | `Bean '...' registered for conditional refresh` | 单个 Bean 被扫描并改写 |
| DEBUG | `Affected beans for changed keys [...]` | 受影响 Beans 详情 |
| ERROR | `Failed to refresh bean '...'` | 刷新过程异常 |

## 附录 B：扩展点速查

| 需求 | 扩展方式 |
|------|----------|
| 替换默认作用域实现 | `@Bean @ConditionalOnMissingBean ConditionalRefreshScope` |
| 替换后处理器逻辑 | `@Bean @ConditionalOnMissingBean RefreshOnKeysPostProcessor` |
| 替换监听器实现 | `@Bean @ConditionalOnMissingBean ConditionalRefreshListener` |
| 禁用整个框架 | `conditional.refresh.enabled: false` |
| 调整去抖窗口 | `conditional.refresh.debounce.delay: 1000` |
| 自定义监控指标 | 注入 `MeterRegistry`，Micrometer 会自动集成 |

## 附录 C：文件结构总览

```
conditional-refresh-spring-boot-starter/
├── src/main/java/com/liu/conditionalrefresh/
│   ├── annotation/
│   │   └── RefreshOnKeys.java              ← 注解定义（第 1 步）
│   ├── config/
│   │   ├── ConditionalRefreshAutoConfiguration.java  ← 自动配置（第 3 步）
│   │   └── ConditionalRefreshProperties.java         ← 配置绑定（第 2 步）
│   ├── processor/
│   │   ├── MetadataCollector.java           ← 元数据收集器（第 6 步）
│   │   └── RefreshOnKeysPostProcessor.java  ← 后处理器（第 5 步）
│   ├── scope/
│   │   ├── ConditionalRefreshScope.java     ← 作用域实现（第 4 步）
│   │   └── ConditionalScopeRegistrar.java   ← 作用域注册器（第 4 步）
│   ├── listener/
│   │   ├── ConditionalRefreshListener.java  ← 核心监听器（第 7 步）
│   │   ├── Debouncer.java                   ← 去抖器（第 8 步）
│   │   └── ConfigDiffUtils.java             ← 配置解析（第 9 步，已非主链路）
│   └── exception/
│       └── RefreshFailedException.java      ← @Deprecated 预留
└── src/test/java/.../
    ├── ConfigDiffUtilsTest.java
    ├── DebouncerTest.java
    ├── DebouncerConcurrencyTest.java
    ├── MetadataCollectorTest.java
    ├── ConditionalRefreshScopeTest.java
    ├── ConditionalRefreshListenerTest.java
    └── ConditionalRefreshListenerRefreshEventTest.java
```

---

> **阅读建议**：
> 1. 先通读本文档，建立整体认知
> 2. 按第 5 节的顺序阅读源码，每步先理解"这个组件解决什么问题"，再关注实现细节
> 3. 结合 Javadoc 和代码注释理解关键方法
> 4. 最后跑一遍单元测试，用断点跟踪完整链路
