# 多实例部署指南

## 1. 组件可扩展性

所有组件均支持多实例部署：

| 组件 | 多实例 | 关键机制 |
|------|--------|----------|
| **Controller** | 多实例 | SET_CONFIG 抢占订阅 `ControllerCmd_{sid}`，session 绑定到实例 |
| **Navigator** | 多实例 | 无状态 + 4 线程并发 + MQ 轮询 + 分布式锁 |
| **TargetPlanner** | 多实例 | tick 携带在消息中，Redis `SET NX` 按 `{session}:{tick}:{x},{y}` 隔离 |
| **CarPool** | 多实例 | 无状态 + 4 线程并发 + MQ 轮询 + 分布式锁 |
| **TaskConfigurator** | 多实例 | 无状态，操作幂等 |
| **display-node** | 多实例 | 状态全在 Redis，MQ 智能路由 |

## 2. 并发模型

Worker 组件使用 `subscribeConcurrent`（线程池）替代 `subscribe`（同步处理）：

```
MQ 消息 → 投递线程 → 线程池 (4 daemon) → 并行处理 → manual ack
                  basicQos(4)
```

| 组件 | 线程池 | 线程名 |
|------|--------|--------|
| TargetPlanner | `MQ-Worker-TargetPlannerCmd` | 4 |
| Navigator | `MQ-Worker-NavigatorCmd` | 4 |
| CarPool | `MQ-Worker-CarPool` | 4 |

Controller 保持同步 `subscribe`（维护 in-memory 状态）。`MessageBus.publish` / `fanoutPublish` 加 `synchronized` 保证多线程 emit 安全。

## 3. MQ 队列拓扑

```
display-node publishCommand(cmd, data):
  cmd ∈ {SET_CONFIG, SET_MAP_EDIT, RESET} → ControllerCmd（共享）
  其他 + data.sessionId                    → ControllerCmd_{sid}（session 专属）
```

```
                        ┌─────────────────────┐
                        │   共享队列（持久化）   │
                        │  ControllerCmd       │ ← Web 初始化命令
                        │  NavigatorCmd        │ ← 路径规划请求
                        │  TargetPlannerCmd    │ ← 目标分配请求
                        │  TaskConfigCmd       │ ← 配置请求
                        │  CarPool             │ ← 移动执行请求
                        └─────────────────────┘

                        ┌─────────────────────┐
                        │  Session 专属队列     │
                        │  ControllerCmd_{sid} │ ← Worker 回复 + Web 命令
                        │  UpdateView_{sid}    │ ← Fanout 广播
                        └─────────────────────┘
```

## 4. 多 Controller 工作原理

```
1. display-node → SET_CONFIG → ControllerCmd（共享）
                              ↓ round-robin
2. Controller#1 收到 → subscribeSessionReplies(sid)
                     → 订阅 ControllerCmd_{sid}（专属绑定）
                     → 转发 FORWARD_CONFIG → TaskConfigurator
3. TaskConfigurator → TASK_READY → ControllerCmd_{sid}（只有#1监听）
4. Controller#1 → 创建 session → 启动 tick
5. 后续 RESUME/PAUSE/STOP 等 → ControllerCmd_{sid} → #1 专属处理
6. Worker 回复（TARGET_ASSIGNED/ROUTE_PLANNED/MOVED 等）→ ControllerCmd_{sid} → #1

另一个用户:
  SET_CONFIG → ControllerCmd → round-robin 到 Controller#2
  → 订阅 ControllerCmd_{sid2} → #2 独立管理 session2
  
#1 和 #2 互不干扰
```

## 5. 部署示例

### 单机

```
Redis + RabbitMQ
Controller ×1, TaskConfigurator ×1
Navigator ×2, TargetPlanner ×2, CarPool ×2
display-node ×1
```

### 多机

```
机器A: Redis, RabbitMQ, display-node ×1
机器B: Controller ×1, TaskConfigurator ×1
机器C: Navigator ×2, TargetPlanner ×2, CarPool ×3
机器D: Navigator ×2, TargetPlanner ×2, CarPool ×3
```

### 环境变量

```bash
export REDIS_HOST=10.0.0.1
export MQ_HOST=10.0.0.1
export MQ_USERNAME=admin
export MQ_PASSWORD=secret
```

## 6. 注意事项

- **不要 purge 队列**：新 Controller 启动时只声明队列，不清空。Worker 通过 `isTaskActive` 快速丢弃旧 session 的消息。
- **重启顺序**：先启 Redis/RabbitMQ，再启 Controller，最后启 Worker。Controller 先启动以确保共享队列存在。
- **心跳间隔**：2s 心跳，5s TTL，display-node 每秒扫描。新实例 2s 内可见。
- **扩容**：Worker 组件可直接增加实例，MQ 自动轮询。Controller 增加实例后新 session 自动分配。
