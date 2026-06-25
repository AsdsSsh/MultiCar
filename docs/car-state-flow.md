# 小车状态流转 — 架构全景

## 状态机

```
                    ┌──────────────────────────┐
                    │                          │
                    ▼                          │
  ┌──────┐  分配目标  ┌──────────────┐  规划路径  ┌───────┐  执行移动  ┌────────┐
  │ IDLE │ ───────→ │ WAITING_ROUTE │ ───────→ │ READY │ ───────→ │ MOVING │
  └──────┘           └──────────────┘           └───────┘           └────────┘
     ▲                                            │                    │
     │              没有路径                       │   还有下一步        │
     │◄───────────────────────────────────────────┘◄───────────────────┘
     │
     │  路线走完
     │◄── ROUTE_DONE
     │
     │  受阻超时（≥2 tick）
     │◄── BLOCKED ──┐
     │              │  碰到障碍
     │              └── BLOCKED（阻塞）
```

| 状态 | 含义 | 触发条件 |
|------|------|---------|
| IDLE | 空闲，等待任务 | 初始化、路线走完、受阻超时、无可用路径 |
| WAITING_ROUTE | 已有目标，等待路径 | TargetPlanner 分配了目标 |
| READY | 就绪，下一步可走 | Navigator 规划了路径 |
| MOVING | 正在移动一步 | Controller 发出 TICK_MOVE |
| BLOCKED | 受阻 | 下一步是障碍 |


## 一个 tick 的完整数据流

```
Controller tick (每个 session 独立线程，每 500ms)

    遍历所有小车，按状态分别处理：

    IDLE ──────────────────────────────────────────────
      │  requestTargetAssignment(sessionId, carId, tick)
      ▼
    TargetPlannerCmd 队列 ──→ TargetPlanner (多实例, 4线程并发)
      │                           │
      │                    扫描未探索区域（本地计算）
      │                    按距离排序候选点
      │                    对最近的点做 SET NX（1次 Redis）
      │                    失败则试下一个
      │                    setCarTarget (Redis)
      │                           │
      │◄── reply TARGET_ASSIGNED ─┘
      ▼
    handleTargetAssigned → CarStatus = WAITING_ROUTE


    WAITING_ROUTE ─────────────────────────────────────
      │  requestRoutePlan(sessionId, carId)
      ▼
    NavigatorCmd 队列 ──→ Navigator (多实例, 4线程并发)
      │                       │
      │                读地图、位置、目标 (Redis)
      │                读其他小车位置作为动态障碍
      │                BFS / A* 计算路径
      │                加分布式锁写 RouteList (Redis)
      │                       │
      │◄── reply ROUTE_PLANNED ─┘
      ▼
    handleRoutePlanned → CarStatus = READY (有路径) 或 IDLE (无路径)


    READY ────────────────────────────────────────────
      │  broadcastTickMove(sessionId, carId)
      ▼
    CarPool 队列 ──→ CarPool (多实例, 4线程并发, 无状态)
      │                   │
      │            new CarAgent(carId)
      │            加分布式锁 peek 下一步
      │            pop + setCarPosition (Redis)
      │            illuminateArea (视野 3×3)
      │            检查障碍 → 正常/受阻
      │                   │
      │◄── MOVED / BLOCKED / ROUTE_DONE ─┘
      ▼
    handleMoved    → CarStatus = READY（还有下一步）
    handleBlocked  → CarStatus = BLOCKED
    handleRouteDone → CarStatus = IDLE


    BLOCKED ──────────────────────────────────────────
      │  checkBlockedTimeout
      │  超时 ≥ 2 tick → 加锁清理路径和目标 → IDLE


    tick 结束:
      broadcastViewUpdate → Fanout → display-node → WebSocket → 前端
```


## 组件角色

| 组件 | 角色 | 触发 | 输出 |
|------|------|------|------|
| Controller | 指挥中心 | 定时器 500ms | 调度命令、广播视图 |
| TargetPlanner | 目标分配 | ASSIGN_TARGET | 小车目标坐标 |
| Navigator | 路径规划 | PLAN_ROUTE | 路径点列表 (RouteList) |
| CarAgent | 移动执行 | TICK_MOVE | 新位置、视野、受阻信息 |
| TaskConfigurator | 初始化 | SET_CONFIG | 地图、障碍物、小车初始状态 |
| display-node | 展示桥接 | REFRESH_ALL | WebSocket STATE_UPDATE |

## 通信机制

```
          ┌──────────────────────────────────┐
          │            Redis 黑板             │
          │  位置·状态·路线·目标·视野·障碍    │
          └────┬──────────────────────┬───────┘
               │ 读/写                │ 读/写
     ┌─────────┴─────────┐    ┌──────┴──────┐
     │   Controller      │    │  Navigator  │
     │   CarAgent        │    │  TargetP.   │
     │   TaskConfig.     │    │             │
     └───────┬───────────┘    └─────────────┘
             │ 消息 (MQ)
     ┌───────┴──────────────────────────────┐
     │           RabbitMQ                    │
     │                                      │
     │  共享队列:                            │
     │    NavigatorCmd, TargetPlannerCmd     │
     │    TaskConfigCmd, CarPool             │
     │    ControllerCmd (Web 命令)            │
     │                                      │
     │  Session 专属队列:                     │
     │    ControllerCmd_{sessionId} (回复)    │
     │    UpdateView_{sessionId} (Fanout)    │
     └───────┬──────────────────────────────┘
             │
     ┌───────┴───────┐
     │  display-node │ ── WebSocket ── 前端
     └───────────────┘
```

## 并发处理

Worker 组件使用 `subscribeConcurrent` 替代 `subscribe`：消息到达后立即提交到线程池（4线程），不等上一个完成就收下一条。`basicQos(4)` + 手动 ack。

| 组件 | 并发度 | 线程池 |
|------|--------|--------|
| TargetPlanner | 4 线程 | `MQ-Worker-TargetPlannerCmd` |
| Navigator | 4 线程 | `MQ-Worker-NavigatorCmd` |
| CarPool | 4 线程 | `MQ-Worker-CarPool` |

Controller 保持同步 `subscribe`（维护 in-memory 状态）。`publish`/`fanoutPublish` 加 `synchronized` 保证多线程 emit 安全。

## 分布式保障

| 机制 | 实现 | 用途 |
|------|------|------|
| 分布式锁 | Redis `SET NX PX` + Lua 解锁 | 移动执行、路径写入时互斥 |
| 目标抢占 | Redis `SET NX EX`（仅 1-2 次 / 车） | TargetPlanner 原子分配目标 |
| 服务心跳 | Redis `SET key EX 5` | 服务发现，每 2s 心跳 |
| Session 隔离 | MQ 队列 + Redis key 前缀 | 多仿真并行，互不干扰 |
| 多实例负载 | MQ 轮询 + 线程池并发 | 水平扩展，跨机器分担 |
| 无效消息过滤 | `isTaskActive` 快速检查 | 旧 session 堆积消息秒级丢弃 |

## 多实例支持

所有组件均支持多实例部署：

| 组件 | 机制 |
|------|------|
| Controller | SET_CONFIG 抢占订阅 `ControllerCmd_{sid}`，初始化命令走共享队列 |
| TargetPlanner | tick 携带在消息中，Redis `SET NX` 按 `{session}:{tick}:{x},{y}` 隔离 |
| Navigator | 无状态 + 线程池并发 + 分布式锁 |
| CarPool | 无状态每次 new CarAgent + 分布式锁 |
| TaskConfigurator | 无状态，操作幂等 |
| display-node | 状态全在 Redis，MQ 智能路由（初始化命令走共享，其他走 session 专属） |

### 多 Controller 流量路由

```
display-node publishCommand(cmd, data):
  cmd ∈ {SET_CONFIG, SET_MAP_EDIT, RESET} → 共享 ControllerCmd
  其他 + data.sessionId 存在             → ControllerCmd_{sessionId}
```
