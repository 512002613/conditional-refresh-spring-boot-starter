# conditional-refresh-spring-boot-starter

条件配置刷新框架，支持通过 `@RefreshOnKeys` 注解声明 Bean 仅对指定配置 Key 敏感，仅当这些 Key 的 **值** 发生变更时才重建该 Bean，与全局 `@RefreshScope` 互不干扰。

## 特性

- 🎯 **精确到 Key 级别**：结构化 diff，只刷新真正受影响的 Bean。
- 🔌 **无侵入注解**：`@RefreshOnKeys` 不包含 `@Scope` 语义，作用域自动设置。
- 🔄 **与全局 @RefreshScope 共存**：两种刷新策略互不干扰。
- ⚡ **首次推送不刷新**：注册 Nacos 监听器前主动获取初始快照。
- 🛡️ **并发安全**：Bean 级锁 + 去抖，防止重复刷新。
- 📊 **Micrometer 监控指标**：`conditional.refresh.success` / `failure`。
- 🔌 **可选 Nacos 依赖**：仅当 Nacos Config 在 classpath 时启用。

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

## Nacos file-extension 兼容

当 Nacos 配置 `file-extension: yaml`（或 `properties`）时，Nacos 2.x 服务端实际存储和推送的 dataId 会自动追加扩展名后缀（例如 `myapp` → `myapp.yaml`）。框架已内置兼容处理：

- 注册 Nacos 监听器时，自动同时注册 `dataId` 与 `dataId.fileExtension` 两个监听器
- 接收推送时，无论 Nacos 回调返回哪个 dataId 形式，均能正确找到对应的 `ListenerContext`

**无需额外配置**，框架自动从 `spring.cloud.nacos.config.file-extension` 感知当前后缀。

## 行为说明

| 触发场景 | SmsService | cosClient |
|---------|-----------|-----------|
| `sms.sign.name` 变更 | ✅ 重建 | ❌ 不动 |
| `cos.tencent.secretId` 变更 | ❌ 不动 | ✅ 重建 |
| 两者同时变更 | ✅ 重建 | ✅ 重建（各自独立） |
| `cos.endpoint` 内容不变（无 diff） | ❌ 不动 | ❌ 不动 |

## 端到端集成测试

框架提供基于真实 Nacos 服务器的端到端测试套件（位于下游 `tornado-facade-service` 项目），通过 `ConfigService.publishConfig()` 模拟 Nacos 服务端推送，覆盖以下场景：

- Context 加载与反向索引构建
- Nacos Listener 注册（含 file-extension 兼容）
- 精准刷新（指定 key 变更仅刷新对应 Bean）
- 未监听 Key 不影响刷新
- 多 dataId/group 独立刷新
- destroyMethod 与惰性实例化

## 设计架构

```
@RefreshOnKeys
     │
     ▼
RefreshOnKeysPostProcessor (BDRPP)
     │  ① 扫描注解元数据
     │  ② 设置 scope="conditionalRefresh"
     │  ③ 设置 proxyMode=TARGET_CLASS
     │  ④ 收集元数据到 MetadataCollector
     ▼
ConditionalRefreshListener (on ApplicationReadyEvent)
     │  ① 构建反向索引 (key → Set<beanName>)
     │  ② 为每个 (dataId, group) 获取初始快照
     │  ③ 注册 Nacos Listener
     ▼
Nacos 配置推送
     │
     ▼
ConfigDiffUtils.parse() → 新快照
ConfigDiffUtils.diff(old, new) → 变更 keys
ListenerContext.findAffectedBeans() → 受影响 Beans
     │
     ▼
Debouncer + ReentrantLock
     │
     ▼
ConditionalRefreshScope.refresh(beanName)
     │  ① super.remove() → destroyMethod
     │  ② super.get() → 重建并缓存
     ▼
完成
```

## 关键约束

- ❌ `@RefreshOnKeys` 与 `@RefreshScope` **不能同时标记**同一 Bean（启动时报错）。
- ✅ 支持 `${...}` 占位符（在环境就绪后统一解析）。
- ✅ 空 `dataId` 自动回退为 `spring.cloud.nacos.config.prefix` → `spring.application.name`。
- ✅ 空 `group` 自动回退为 `spring.cloud.nacos.config.group` → `DEFAULT_GROUP`。
- ✅ 支持 properties 和 YAML 格式配置。

## 监控指标

| 指标名 | 类型 | 说明 |
|-------|------|-----|
| `conditional.refresh.success` | Counter | 刷新成功次数（标签：`bean` = Bean 名） |
| `conditional.refresh.failure` | Counter | 刷新失败次数（标签：`bean` = Bean 名） |

## 条件刷新 vs 全量刷新对比

| 维度 | @RefreshScope | @RefreshOnKeys |
|-----|---------------|----------------|
| 刷新范围 | 标记的所有 Bean | 仅受影响的 Bean |
| 触发条件 | 任意环境变更 | 指定 Key 值变更 |
| 作用域 | `refresh` | `conditionalRefresh` |
| Key 匹配 | N/A | 结构化 diff |
| 性能 | O(n) 全量重建 | O(k) 精准重建（k << n）|

## 许可证

Copyright © 2026
