# 多实例部署指南

## 1. 概述

系统基于**黑板架构**设计，各组件通过 RabbitMQ + Redis 解耦协作。部分组件支持横向扩展（多实例部署），通过 RabbitMQ 轮询分发 + Redis 分布式锁保证正确性。

## 2. 组件可扩展性

| 组件 | 多实例 | 关键机制 |
|------|--------|----------|
| **Controller** | 单实例 | 集中式节拍调度，`SessionState` 在 JVM 内存中 |
| **Navigator** | 多实例 | 无状态路径规划，MQ 轮询分发，分布式锁保护 |
| **TargetPlanner** | 单实例 | `assignedThisBatch` 在内存中，多实例会重复分配目标 |
| **CarPool** | 多实例 | 无状态模式，每次 `TICK_MOVE` 创建临时 CarAgent |
| **TaskConfigurator** | 多实例 | 无状态，处理一次性初始化命令 |

### 多实例工作原理

```
Controller ──TICK_MOVE──▶  CarPool 队列
                              │
                    RabbitMQ round-robin
                    (basicQos=1)
                         ╱          ╲
                  CarPool#1       CarPool#2
                  (机器A)         (机器B)
                    │                │
              new CarAgent     new CarAgent
                    │                │
              ┌─────┴────────────────┴─────┐
              │     Redis 分布式锁          │
              │  lock:{sid}:Car001          │
              │  同一时刻只有一个能拿到锁     │
              └────────────────────────────┘
```

## 3. 关键设计

### 3.1 RabbitMQ 公平轮询

`MessageBus.subscribe()` 设置了 `basicQos(1)`，每个消费者一次只预取一条消息，处理完后才接收下一条。多个实例订阅同一队列时，消息在实例间公平轮询分发。

```java
// MessageBus.java
consumerChannel.basicQos(1);  // 每次只取一条
consumerChannel.basicConsume(queueName, true, deliverCallback, tag -> {});
```

### 3.2 CarPool 无状态模式

CarPool 不再缓存 CarAgent，每次收到 `TICK_MOVE` 消息直接创建临时 CarAgent 处理：

```java
// CarPoolMain.java
private void handleMessage(String cmd, JSONObject data, long timestamp) {
    if (!CMD_TICK_MOVE.equals(cmd)) return;
    String carId = data.getString("carId");
    if (carId == null) return;

    new CarAgent(carId, blackboard, messageBus, distributedLock)
            .handleMessage(cmd, data, timestamp);
}
```

所有小车状态（位置、路线、状态机）存储在 Redis 中，CarAgent 构造函数默认 `running = true`，不依赖 `start()` 调用。

### 3.3 Redis 分布式锁

以小车粒度加锁，保证同一辆车的状态不会被多个实例同时修改：

```java
// DistributedLock.java - 基于 SET NX PX + Lua 原子解锁
String lockKey = "lock:" + sessionId + ":" + carId;  // 如 lock:abc123:Car001
distributedLock.executeWithLock(lockKey, LOCK_EXPIRE_MS, () -> {
    // 临界区：读写 Redis 中该小车的状态
});
```

### 3.4 Navigator 无状态计算

Navigator 处理流程不依赖本地状态：
1. 从消息中提取 `sessionId`、`carId`、`algorithm`
2. 从 Redis 读取地图、小车位置、目标
3. 执行 BFS/A* 路径计算
4. 加锁后写入路径到 Redis
5. 回复 Controller

每个请求完全独立，多个 Navigator 实例可并行处理不同小车的路径规划。

## 4. 部署示例

### 4.1 单机部署（开发/调试）

```
机器 A:
  Redis + RabbitMQ
  TaskConfigurator ×1
  Controller ×1
  Navigator ×1
  TargetPlanner ×1
  CarPool ×1
  display-node ×1
```

### 4.2 多机部署（生产）

```
机器 A (10.0.0.1):          机器 B (10.0.0.2):          机器 C (10.0.0.3):
  Redis                        RabbitMQ                     display-node
  Controller ×1                                             Navigator ×2
  TargetPlanner ×1                                          CarPool ×2
  TaskConfigurator ×1                                       Navigator ×2
```

### 4.3 环境变量配置

每台机器通过环境变量指向共享的 Redis 和 RabbitMQ：

```bash
# 机器 B 和 C 上设置
export REDIS_HOST=10.0.0.1
export REDIS_PORT=6379
export MQ_HOST=10.0.0.2
export MQ_PORT=5672
export MQ_USERNAME=admin
export MQ_PASSWORD=secret
```

### 4.4 启动命令

```bash
# 在机器 C 上启动多个 Navigator 实例
java -cp "..." inspection.navigator.NavigatorMain &
java -cp "..." inspection.navigator.NavigatorMain &  # 第二个实例

# 在机器 C 上启动多个 CarPool 实例
java -cp "..." inspection.car.CarPoolMain &
java -cp "..." inspection.car.CarPoolMain &  # 第二个实例
```

多个实例订阅同一个队列，RabbitMQ 自动轮询分发消息。

## 5. 当前限制

### 5.1 Controller（不可多实例）

Controller 在 `ConcurrentHashMap<String, SessionState>` 中维护 session 状态（tick 计数、暂停标志、pending 请求等），多个实例会重复调度同一 session。

如需支持，需要将 `SessionState` 迁移到 Redis 并用分布式锁保护每个 tick 的执行。

### 5.2 TargetPlanner（不可多实例）

`assignedThisBatch` 用于在当前 tick 内避免重复分配同一目标点给不同小车。此状态在 JVM 内存中，多实例互不可见。

如需支持，改用 Redis `SET NX` 原子标记已分配目标：

```java
// 伪代码
String key = "batch:" + sessionId + ":" + tick + ":" + targetXY;
if (jedis.set(key, carId, SetParams.setParams().nx().ex(30)) == OK) {
    // 分配成功
}
```

### 5.3 display-node（有限多实例）

WebSocket 连接绑定在单个 Node.js 进程上，不能直接多实例。可部署多个 display-node 实例但需要用 Redis Pub/Sub 或 MQ 共享状态，或在前端做 sticky session。

## 6. 扩容建议

| 场景 | 扩容策略 |
|------|---------|
| 路径规划成为瓶颈 | 增加 Navigator 实例（无状态，直接扩） |
| 小车移动执行成为瓶颈 | 增加 CarPool 实例（无状态，直接扩） |
| 初始化/重置变慢 | 增加 TaskConfigurator 实例 |
| Controller 成为瓶颈 | 按 session 哈希分片，每个 Controller 管理不同 session |
| Redis 成为瓶颈 | 升级 Redis Cluster / Sentinel |
| RabbitMQ 成为瓶颈 | RabbitMQ 集群 + 镜像队列 |
