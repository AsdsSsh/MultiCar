# 多实例架构设计

## 核心原理

全部靠三样东西：**Redis 原子操作 + MQ 队列隔离 + 无状态设计**。

---

## 架构全景图

```
                    前端 (Vue3)
                       │
                  WebSocket
                       │
              ┌────────┴────────┐
              │  display-node   │  ← 多实例，状态全在 Redis
              └────────┬────────┘
                       │
    ┌──────────────────┼──────────────────┐
    │        initCmds  │  有 sessionId    │
    ▼                  │                  ▼
ControllerCmd      ControllerCmd_{sid1}   ControllerCmd_{sid2}
(共享队列)         (专属队列 #1拥有)       (专属队列 #2拥有)
    │                  │                  │
    │ MQ round-robin   │ 直达 #1          │ 直达 #2
    ▼              ┌───┴───┐          ┌───┴───┐
 Controller#1     │  OK    │          │  OK    │
   ┌─订阅sid1─┐   └───────┘          └───────┘
   │ tick...  │
   └──────────┘

 Controller#2
   ┌─订阅sid2─┐
   │ tick...  │
   └──────────┘
```

### 消息路由

```
display-node publishCommand(cmd, data):
  cmd ∈ {SET_CONFIG, SET_MAP_EDIT, RESET} → ControllerCmd（共享）
  其他 + data.sessionId                   → ControllerCmd_{sid}（专属）
```

---

## 一个 tick 的完整数据流

```
═══════════════════════════════════════════════════════════════

  Controller tick (每 session 独立线程，每 500ms)

    遍历所有小车，按状态分别处理：


    IDLE ──────────────────────────────────────────────
      │  requestTargetAssignment(sessionId, carId, tick)
      ▼
    TargetPlannerCmd 队列 ──→ TargetPlanner (多实例, 4线程并发)
      │                           │
      │                    扫描未探索区域（本地计算）
      │                    按距离排序候选点
      │                    SET NX 原子抢占目标（1-2次Redis）
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
      │                加分布式锁 write RouteList (Redis)
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
      │            pop + SET NX 抢占目标格点（防碰撞）
      │            setCarPosition + illuminateArea (Redis)
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

═══════════════════════════════════════════════════════════════
```

---

## Controller 多实例选主

```
1. display-node → SET_CONFIG → ControllerCmd（共享队列）
                                    ↓ MQ round-robin
2. Controller#1 收到 → 生成/提取 sessionId
                     → 立即订阅 ControllerCmd_{sid}  ← 抢占
                     → 转发 FORWARD_CONFIG → TaskConfigurator
3. TaskConfigurator → TASK_READY → ControllerCmd_{sid}
                                    ↓
                              只有 #1 在监听
4. #1 → 创建 session → 启动 tick → #1 拥有
5. 后续 RESUME/PAUSE/STOP → ControllerCmd_{sid} → 直达 #1
6. Worker 回复 → ControllerCmd_{sid} → 直达 #1

另一个 session:
  #2 抢到另一个 sessionId → 订阅 ControllerCmd_{sid2} → 独立管理
```

---

## 并发模型

```
Worker (Navigator/TargetPlanner/CarPool):
  MQ消息 → 投递线程 → [线程1|线程2|线程3|线程4] → 并行处理 → manual ack
            basicQos(4)

Controller:
  MQ消息 → 投递线程 → 同步处理 → auto ack
            basicQos(1)

MessageBus.publish / fanoutPublish → synchronized (多线程emit安全)
```

| 组件 | 线程池 | 并发度 |
|------|--------|--------|
| TargetPlanner | `MQ-Worker-TargetPlannerCmd` | 4 |
| Navigator | `MQ-Worker-NavigatorCmd` | 4 |
| CarPool | `MQ-Worker-CarPool` | 4 |
| Controller | 无（同步） | 1 |

---

## 冲突防护全景

```
 两车抢同一目标:
   Car001 ──→ SET NX targetClaim:abc:7:5,10 ──→ OK ✓
   Car002 ──→ SET NX targetClaim:abc:7:5,10 ──→ nil ✗ (试下一个)

 两车抢同一格点:
   Car001 ──→ pop (5,10) → SET NX moveClaim:abc:7:5,10 ──→ OK ✓
   Car002 ──→ pop (5,10) → SET NX moveClaim:abc:7:5,10 ──→ nil ✗ (阻塞)

 不同 tick 互不干扰:
   tick=7: targetClaim:abc:7:5,10
   tick=8: targetClaim:abc:8:5,10  ← 不同 key，不会冲突

 多实例 Worker 互不冲突:
   MQ 轮询分发 + Redis SET NX 原子操作 + 分布式锁
```

---

## MQ 队列拓扑

```
共享队列:
  ControllerCmd      ← Web 初始化命令
  NavigatorCmd       ← 路径规划请求
  TargetPlannerCmd   ← 目标分配请求
  TaskConfigCmd      ← 配置请求
  CarPool            ← 移动执行请求

Session 专属:
  ControllerCmd_{sid}  ← Worker 回复 + Web 命令
  UpdateView_{sid}     ← Fanout 广播
```

---

## 多实例支持一览

| 组件 | 多实例 | 机制 |
|------|--------|------|
| Controller | 多实例 | 抢占订阅 `ControllerCmd_{sid}` |
| TargetPlanner | 多实例 | tick 携带 + `SET NX targetClaim:{sid}:{tick}:{x},{y}` |
| Navigator | 多实例 | 无状态 + MQ 轮询 + 分布式锁 |
| CarPool | 多实例 | 无状态 + 分布式锁 + 格点抢占 `moveClaim` |
| TaskConfigurator | 多实例 | 无状态，操作幂等 |
| display-node | 多实例 | 状态全在 Redis |
