# conditional-refresh-spring-boot-starter

条件配置刷新框架，支持通过 `@RefreshOnKeys` 注解声明 Bean 仅对指定配置 Key 敏感，仅当这些 Key 的 **值** 发生变更时才重建该 Bean，与全局 `@RefreshScope` 互不干扰。

## 特性

- 🎯 **精确到 Key 级别**：结构化 diff，只刷新真正受影响的 Bean。
- 🔌 **无侵入注解**：`@RefreshOnKeys` 不包含 `@Scope` 语义，作用域自动设置。
- 🔄 **与全局 @RefreshScope 共存**：两种刷新策略互不干扰。
- 🎛️ **双模式监听**：精确模式（显式列 key）+ 前缀模式（`prefix.*` 粗粒监听）。
- ⏱️ **PropertySource-First 时序**：监听 Spring `EnvironmentChangeEvent`，确保新实例读到正确的新值。
- 🛡️ **并发安全**：Bean 级锁 + 去抖，防止重复刷新。
- 📊 **Micrometer 监控指标**：`conditional.refresh.success` / `failure`。
- 🔌 **可选 Nacos 依赖**：仅当 Nacos Config 在 classpath 时启用。
- 🌐 **多版本兼容**：Spring Boot 2.7 / 3.0 / 3.5 / 4.0 全矩阵验证。

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.liu</groupId>
    <artifactId>conditional-refresh-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 启用全局 Nacos 刷新

```yaml
spring:
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        prefix: myapp                # 默认 Data ID（即 application name）
        group: DEFAULT_GROUP
        refresh-enabled: true        # 开启全局刷新
        file-extension: yaml
```

### 3. 在 Bean 上使用 @RefreshOnKeys

```java
@Configuration
public class CosConfig {

    @Bean(destroyMethod = "shutdown")
    @RefreshOnKeys({"cos.tencent.secretId", "cos.tencent.secretKey"})
    public COSClient cosClient(Environment env) {
        String id  = env.getProperty("cos.tencent.secretId");
        String key = env.getProperty("cos.tencent.secretKey");
        return new COSClient(id, key);
    }
}

@Service
@RefreshScope     // 完全不受影响，持续随全局刷新重建
public class SmsService { }
```

### 4. 配置属性（可选）

```yaml
conditional:
  refresh:
    enabled: true                 # 默认 true
    debounce.delay: 500           # 去抖窗口 ms，默认 500
    metrics-enabled: true         # 暴露 Micrometer 指标
    initial-snapshot-enabled: true  # 首次注册拉取快照
```

## 行为说明

| 触发场景 | SmsService | cosClient |
|---------|-----------|-----------|
| `sms.sign.name` 变更 | ✅ 重建 | ❌ 不动 |
| `cos.tencent.secretId` 变更 | ❌ 不动 | ✅ 重建 |
| 两者同时变更 | ✅ 重建 | ✅ 重建（各自独立） |
| `cos.endpoint` 内容不变（无 diff） | ❌ 不动 | ❌ 不动 |

## 测试验证模块

项目内置多个测试验证模块，覆盖 Spring Boot 2.7 / 3.0 / 3.5 / 4.0：

| 模块 | Spring Boot | Spring Cloud | 说明 |
|------|-------------|--------------|------|
| `conditional-refresh-test-sample` | 2.7.18 | 2021.0.8 | 与父 POM 一起构建 |
| `conditional-refresh-test-sample-v2` | 2.7.18 | 2022.0.5 | 独立项目 |
| `conditional-refresh-test-sample-v3` | 3.5.x | 2023.0.x | 独立项目，需 Java 17 |
| `conditional-refresh-test-sample-v4` | 4.0.x | 2023.0.x | 独立项目，需 Java 17 |

```bash
# 构建 v2 测试模块（独立项目）
mvn clean package -pl conditional-refresh-test-sample-v2

# 运行测试验证应用
java -jar conditional-refresh-test-sample-v2/target/conditional-refresh-test-sample-v2-1.0.0.jar
```

启动后通过 REST 端点验证：

```bash
# 查看当前 Bean 值
curl http://localhost:65380/v2/status

# 健康检查
curl http://localhost:65380/v2/health
```

**切换 Nacos 环境**：修改对应模块 `pom.xml` 中的 `<nacos.server-addr>`、`<nacos.namespace>`、`<nacos.group>` 三个属性即可。

> **注意**：集成测试需要连接真实 Nacos 服务器。v3/v4 模块构建需 Maven 镜像含 Spring Cloud Alibaba 2023.x BOM。

## 设计架构

```
@RefreshOnKeys
     │
     ▼
RefreshOnKeysPostProcessor (BDRPP)
     │  ① 扫描注解元数据（value 或 prefix 模式）
     │  ② 设置 scope="conditionalRefresh" + CGLIB scoped-proxy
     │  ③ 互斥校验（不得同时标注 @RefreshScope）
     │  ④ 收集元数据到 MetadataCollector
     ▼
Bean 实例化 → scoped-proxy 代理（惰性，首次访问时创建目标实例）
     │
     ▼
ApplicationReadyEvent
     │  ConditionalRefreshListener.onApplicationReady()
     │  ① 构建双索引 (key → beans / prefix → beans)
     ▼
Nacos 配置推送 → PropertySource 更新 → EnvironmentChangeEvent
     │
     ▼
ConditionalRefreshListener.onEnvironmentChanged()  ← 主路径
     │  ① event.getKeys() 获取变更 keys
     │  ② 双索引查找受影响 Bean（精确 + 前缀）
     │  ③ Debouncer 去抖 → ReentrantLock → scope.refresh()
     ▼
旧实例销毁（destroyMethod）→ 下次 proxy 访问时惰性创建新实例（读到的新值）

┌─────────────────────────────────────────────────────────────────────┐
│ SC 2021.0.x fallback:                                               │
│ RefreshScopeRefreshedEvent → 全量刷新所有 @RefreshOnKeys Bean       │
└─────────────────────────────────────────────────────────────────────┘
```

## 关键约束

- ❌ `@RefreshOnKeys` 与 `@RefreshScope` **不能同时标记**同一 Bean（启动时报错）。
- ❌ `@RefreshOnKeys` 的 `value` 与 `prefix` **不能同时非空，也不能同时为空**。
- ✅ 支持 `${...}` 占位符（在环境就绪后统一解析）。
- ✅ 空 `dataId` 自动回退为 `spring.cloud.nacos.config.prefix` → `spring.application.name`。
- ✅ 空 `group` 自动回退为 `spring.cloud.nacos.config.group` → `DEFAULT_GROUP`。
- ✅ 支持 properties 和 YAML 格式配置（Nacos 存储格式无关）。
- ✅ 监听器顺序 `Ordered.LOWEST_PRECEDENCE`，确保在 `ConfigurationPropertiesRebinder` 之后执行。

## 监控指标

| 指标名 | 类型 | 说明 |
|-------|------|-----|
| `conditional.refresh.success` | Counter | 刷新成功次数（标签：`bean` = Bean 名） |
| `conditional.refresh.failure` | Counter | 刷新失败次数（标签：`bean` = Bean 名） |

## 两种监听模式对比

| 维度 | 精确模式 (`value`) | 前缀模式 (`prefix`) |
|-----|-------------------|-------------------|
| 用法 | `@RefreshOnKeys({"a.b.c"})` | `@RefreshOnKeys(prefix = "a.b")` |
| 触发条件 | 仅列出的 key 值变更 | 任何 `a.b.*` key 变更 |
| 适用场景 | 少量 key、精确控制 | 整个 `@ConfigurationProperties` 绑定 |
| 推荐注入方式 | `@Value` | 构造函数注入 Properties Bean |

## 条件刷新 vs 全量刷新对比

| 维度 | @RefreshScope | @RefreshOnKeys |
|-----|---------------|----------------|
| 刷新范围 | 标记的所有 Bean | 仅受影响的 Bean |
| 触发条件 | 任意环境变更 | 指定 Key 值变更 |
| 作用域 | `refresh` | `conditionalRefresh` |
| Key 匹配 | N/A | 结构化 diff |
| 性能 | O(n) 全量重建 | O(k) 精准重建（k << n）|

## 文档

| 文档 | 说明 |
|------|------|
| [`docs/code-reading-guide.md`](docs/code-reading-guide.md) | **框架原理与代码解读**（面向初学者，由浅入深讲解设计动机、核心原理和代码实现） |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更日志 |
| [`CLAUDE.md`](CLAUDE.md) | 项目架构与构建指引（面向开发者） |

## 许可证

Copyright © 2026
