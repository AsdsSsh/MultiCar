# BlackBoxAI — 变电站巡检仿真系统 架构文档

## 1. 系统概览

基于**黑板架构 (Blackboard Architecture)** 的多智能体协作变电站巡检仿真系统。多个独立知识源（Agent）通过共享黑板（Redis）和消息总线（RabbitMQ）异步协作，完成地图探索任务。

```
                            ┌─────────────────────────────┐
                            │      Web Browser (Vue 3)     │
                            │   Canvas 渲染 · Pinia 状态   │
                            └──────┬───────────┬──────────┘
                                   │ REST      │ WebSocket
                                   ▼           ▼
┌──────────────────────────────────────────────────────────────┐
│              display-node (Express :8887)                     │
│  ┌──────────┐  ┌──────────┐  ┌─────────┐  ┌──────────────┐ │
│  │  Routes   │  │ WsHandler│  │PushSvc  │  │  Recorder    │ │
│  │ auth/     │  │ JOIN/    │  │ 组装    │  │  JSONL 录制  │ │
│  │ admin/    │  │ LEAVE/   │  │ STATE_  │  │              │ │
│  │ config/   │  │ SET_CONF │  │ UPDATE  │  │              │ │
│  │ simulation│  │ IG/STOP  │  │         │  │              │ │
│  │ replay/   │  │          │  │         │  │              │ │
│  └──────────┘  └────┬─────┘  └────┬────┘  └──────────────┘ │
│                     │              │                          │
│              ┌──────┴──────┐  ┌───┴──────┐                  │
│              │ RabbitClient│  │  Redis   │                  │
│              │  publishCmd │  │  Reader  │                  │
│              └──────┬──────┘  └───┬──────┘                  │
└─────────────────────┼─────────────┼─────────────────────────┘
                      │             │
              ┌───────┴──────┐  ┌───┴──────────┐
              │   RabbitMQ    │  │    Redis      │
              │  (消息总线)   │  │  (黑板存储)   │
              └───────┬──────┘  └───┬──────────┘
                      │              │
┌─────────────────────┼──────────────┼─────────────────────────┐
│              Java 后端 (多模块 Maven)                          │
│                      │              │                         │
│  ┌───────────────────┴──────────────┴──────────────────────┐ │
│  │            ControllerAgent (调度核心)                     │ │
│  │    tick 循环 · 17 种命令处理 · 多 session 管理            │ │
│  └──┬────────┬──────────┬──────────┬──────────────────────┘ │
│     │        │          │          │                          │
│     ▼        ▼          ▼          ▼                          │
│  ┌──────┐ ┌──────┐ ┌────────┐ ┌──────────────┐              │
│  │Car   │ │Navig │ │Target  │ │TaskConfig    │              │
│  │Agent │ │ator  │ │Planner │ │urator        │              │
│  │移动  │ │路径  │ │目标    │ │初始化/重置    │              │
│  │执行  │ │规划  │ │分配    │ │              │              │
│  └──────┘ └──────┘ └────────┘ └──────────────┘              │
└──────────────────────────────────────────────────────────────┘
```

## 2. 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| 前端 | Vue 3 + Vite + Pinia + Vue Router | SPA，Canvas 2D 渲染 |
| 展示层 | Node.js + Express + ws + ioredis + amqplib | HTTP API + WebSocket 桥接 |
| 消息总线 | RabbitMQ (端口 5672) | 5 个共享队列 + 每 session 1 个 Fanout 交换机 |
| 黑板存储 | Redis (端口 6379) | Bitmap 地图 + Hash 配置 + String 小车状态 |
| 后端 | Java 17 + Maven 多模块 | 6 个 Agent 独立进程 |

## 3. 项目结构

```
BlackBoxAI/
├── pom.xml                          # Maven 父 POM
├── common/                          # 公共模块（数据模型、Redis、MQ、工具）
│   └── src/main/java/inspection/common/
│       ├── blackboard/              # BlackboardClient, DistributedLock, Config
│       ├── messaging/               # MessageBus, MessageConfig, MessageType
│       ├── model/                   # Point, CarStatus, CarInfo, RouteAlgorithm, SimulationState
│       ├── config/                  # ConnectionConfig
│       └── util/                    # Constants, MapCompression
├── controller/                      # 调度控制器
│   └── ControllerAgent.java        # tick 循环、命令处理、session 管理
├── car/                             # 小车执行模块
│   └── CarAgent.java               # 移动执行、受阻检测
├── navigator/                       # 路径规划模块
│   ├── AStarPathFinder.java        # A* 启发式搜索
│   └── BfsPathFinder.java          # BFS 最短路径
├── target-planner/                  # 目标分配模块
│   └── TargetPlannerAgent.java     # 未探索格子分配
├── task-configurator/              # 任务初始化模块
│   └── TaskConfiguratorAgent.java  # 障碍物生成、小车初始化
├── display-node/                    # Node.js 展示层
│   ├── server.js                   # 入口 :8887
│   ├── config.js                   # Redis/MQ/JWT 配置
│   ├── routes/                     # auth, admin, mapConfig, simulation, replay
│   ├── ws/wsHandler.js             # WebSocket 命令处理
│   ├── mq/rabbitClient.js          # RabbitMQ 客户端
│   ├── redis/blackboardReader.js   # Redis 只读客户端
│   ├── services/                   # pushService, recorder
│   ├── auth/                       # jwt, middleware, password
│   ├── store/jsonFileStore.js      # JSON 文件持久化
│   └── data/                       # users/maps/simulations.json
├── web/                             # Vue 3 前端
│   └── src/
│       ├── views/                  # Login, Register, Admin, Configurator, User, Replay
│       ├── components/             # SimulationCanvas, CarStatusList/Item, 等 10 个
│       ├── composables/            # useWebSocket, useCanvasRenderer
│       ├── store/                  # authStore, simulationStore
│       ├── router/index.js         # 路由 + 角色守卫
│       └── utils/                  # api, constants, canvasDrawer, mapCompression
└── data/                            # 持久化数据文件
```

## 4. 多智能体架构 — 消息流

```
     前端 WebSocket                    前端 REST API
     (ws://host:8887/ws/simulation)    (http://host:8887/api/*)
              │                              │
              ▼                              ▼
     ┌────────────────┐            ┌──────────────────┐
     │   WsHandler    │            │  Express Routes  │
     │  权限检查+转发  │            │  CRUD + Auth     │
     └───────┬────────┘            └──────────────────┘
             │ publishCommand(cmd, data)
             ▼
     ┌───────────────┐
     │ ControllerCmd  │  RabbitMQ 队列
     └───────┬───────┘
             ▼
     ┌───────────────────────────────────────────────┐
     │           ControllerAgent                      │
     │                                                │
     │  switch(cmd):                                  │
     │    SET_CONFIG → forwardWebCommand              │
     │    RESET      → forwardWebCommand              │
     │    PAUSE      → handlePause                    │
     │    RESUME     → handleResume                   │
     │    STEP_ONCE  → handleStepOnce                 │
     │    STOP       → handleStop                     │
     │    ADD_CAR    → handleAddCar                   │
     │    DELETE_CAR → handleDeleteCar                │
     │    MOVE_CAR   → handleMoveCar                  │
     └───┬──────────┬──────────┬─────────────────────┘
         │          │          │
         ▼          ▼          ▼
    ┌────────┐ ┌────────┐ ┌──────────────┐
    │TaskConf│ │Target  │ │NavigatorCmd  │
    │igCmd   │ │Planner │ │              │
    │        │ │Cmd     │ │              │
    └───┬────┘ └───┬────┘ └──────┬───────┘
        │          │              │
        ▼          ▼              ▼
   TaskConfig  TargetPlanner  Navigator
   urator      Agent          Agent
   (初始化)    (目标分配)     (路径规划)
        │          │              │
        └──────────┴──────┬───────┘
                          │ 回复到 ControllerCmd
                          ▼
                   ┌──────────────┐
                   │ Controller   │
                   │ 处理回复     │
                   │ 发送 TICK_   │
                   │ MOVE 到      │
                   │ CarPool      │
                   └──────┬───────┘
                          ▼
                   ┌──────────────┐
                   │  CarAgent    │
                   │  执行移动    │
                   └──────┬───────┘
                          │ 回复 MOVED/BLOCKED/ROUTE_DONE
                          ▼
                   ┌──────────────┐
                   │  Controller  │ 每 tick 广播 REFRESH_ALL
                   │  Fanout      │
                   └──────┬───────┘
                          ▼
                   ┌──────────────────┐
                   │ UpdateView_{sid} │ Fanout Exchange
                   └──────┬───────────┘
                          ▼
                   ┌──────────────────┐
                   │  RabbitClient    │ (display-node)
                   │  消费 REFRESH_ALL│
                   └──────┬───────────┘
                          ▼
                   ┌──────────────────┐
                   │  PushService     │
                   │  读 Redis → 组装 │
                   │  STATE_UPDATE →  │
                   │  推送到 WS 客户端│
                   └──────────────────┘
```

## 5. Redis 键空间设计

所有 key 格式: `session:{sessionId}:{suffix}`

| Key | 类型 | 内容 |
|-----|------|------|
| `session:{sid}:mapView` | Bitmap | 探索视野，bit=1 已探索 |
| `session:{sid}:mapBlock` | Bitmap | 障碍物，bit=1 有障碍 |
| `session:{sid}:TaskConfig` | Hash | mapWidth, mapHeight, carCount, obstacleDensity, algorithm, taskActive |
| `session:{sid}:simulation:paused` | String | "1"=暂停, "0"=运行 |
| `session:{sid}:Car{XXX}:Position` | String | `{"x":N,"y":N}` |
| `session:{sid}:Car{XXX}:Target` | String | `{"x":N,"y":N}` |
| `session:{sid}:Car{XXX}:RouteList` | List | 路径点 JSON 列表 (LIFO: lpush/rpop) |
| `session:{sid}:Car{XXX}:Status` | String | IDLE/WAITING_ROUTE/READY/MOVING/BLOCKED |
| `session:{sid}:Car{XXX}:Steps` | String | 行走步数 (整数) |
| `session:{sid}:Car{XXX}:BlockedTick` | String | 受阻时的节拍号 |
| `lock:{sid}:Car{XXX}` | String | 分布式锁 (SET NX PX 5000ms) |

## 6. RabbitMQ 拓扑

### 共享队列 (所有 session 共用)

| 队列名 | 消费者 | 处理模式 | 消息类型 |
|--------|--------|----------|----------|
| `ControllerCmd` | ControllerAgent | 同步 `subscribe` | Web 初始化命令 |
| `NavigatorCmd` | NavigatorAgent | 4线程 `subscribeConcurrent` | PLAN_ROUTE |
| `TargetPlannerCmd` | TargetPlannerAgent | 4线程 `subscribeConcurrent` | ASSIGN_TARGET, RESET_BATCH |
| `TaskConfigCmd` | TaskConfiguratorAgent | 同步 `subscribe` | FORWARD_CONFIG, FORWARD_RESET |
| `CarPool` | CarPoolMain (无状态，支持多实例) | 4线程 `subscribeConcurrent` | TICK_MOVE |

### 每 Session 动态队列

| 名称 | 类型 | 用途 |
|------|------|------|
| `UpdateView_{sessionId}` | Fanout Exchange | 广播 REFRESH_ALL |
| `WSB_Refresh_{sessionId}` | Queue (绑定到 Fanout) | display-node 消费，触发 STATE_UPDATE 推送 |

### 消息分发

- **Worker (Navigator/TargetPlanner/CarPool)**：使用 `subscribeConcurrent`，消息到达后提交到 4 线程池并行处理，`basicQos(4)` + 手动 ack。
- **Controller**：使用 `subscribe`（同步），维护 in-memory 状态。
- `MessageBus.publish` / `fanoutPublish` 加 `synchronized`，多线程 emit 安全。

### CarPool 无状态模式

CarPool 不再缓存 CarAgent。每次收到 `TICK_MOVE` 创建临时 `CarAgent` 处理，处理完后丢弃。所有小车状态存储在 Redis，由分布式锁保证互斥。详见 [多实例部署指南](multi-instance-deployment.md)。

### TargetPlanner 目标分配

本地按距离排序候选格点，只对最近的候选做 1 次 Redis `SET NX` 抢占，失败则试下一个。从每车 N 次 Redis 往返优化为 1-2 次。

## 7. 完整仿真生命周期

```
┌─ 1. 初始化 ─────────────────────────────────────────────────┐
│                                                              │
│  前端: 创建仿真 → enterSimulation → SET_CONFIG (WebSocket)   │
│    │                                                         │
│    ▼                                                         │
│  WsHandler: 订阅 session → publishCommand(SET_CONFIG)        │
│    │                                                         │
│    ▼                                                         │
│  ControllerAgent: 生成 sessionId → forwardWebCommand         │
│    │                                                         │
│    ▼                                                         │
│  TaskConfiguratorAgent:                                      │
│    1. 写入 TaskConfig (Redis Hash)                           │
│    2. 生成障碍物 bitmap (自定义 or 随机，避开 Car 3x3 保护区)│
│    3. 初始化 Car 位置/状态=IDLE/步数=0                       │
│    4. 点亮初始视野 (Car 周围 3x3)                            │
│    5. 发送 TASK_READY → ControllerCmd                       │
│    │                                                         │
│    ▼                                                         │
│  ControllerAgent: 创建 SessionState (paused=true)            │
│    广播 REFRESH_ALL → 前端显示初始状态                        │
└──────────────────────────────────────────────────────────────┘

┌─ 2. 运行 ────────────────────────────────────────────────────┐
│                                                              │
│  用户点击 "开始" → RESUME                                    │
│    │                                                         │
│    ▼                                                         │
│  Controller: paused=false → 进入 tick 循环 (500ms/次)        │
│                                                              │
│  每 tick 对每辆 Car:                                         │
│                                                              │
│    IDLE ──→ TargetPlanner 分配目标 ──→ WAITING_ROUTE        │
│    WAITING_ROUTE ──→ Navigator 规划路径 ──→ READY           │
│    READY ──→ CarAgent 执行一步移动                           │
│    MOVING ──→ 异常复位为 READY                               │
│    BLOCKED ──→ 超时(≥2 tick)重试，回到 IDLE                 │
│                                                              │
│  每 tick 结束: broadcastViewUpdate → REFRESH_ALL (Fanout)    │
│    → PushService 读 Redis → STATE_UPDATE → WebSocket 推送   │
│    → Recorder 录制 tick (JSONL)                              │
└──────────────────────────────────────────────────────────────┘

┌─ 3. 探索完成 ────────────────────────────────────────────────┐
│                                                              │
│  exploredPercent ≥ 99.9% → Controller 移除 session           │
│  停止 tick 循环                                              │
│  录制文件保留在 data/replays/{sessionId}.jsonl              │
└──────────────────────────────────────────────────────────────┘

┌─ 4. 控制操作 ────────────────────────────────────────────────┐
│                                                              │
│  暂停/恢复: 不停止录制                                       │
│  增加小车: Controller.handleAddCar → Redis + carCount++     │
│  删除小车: Controller.handleDeleteCar → Redis + carCount--  │
│  移动小车: Controller.handleMoveCar → Redis + 清理路径       │
│  重置: RESET → TaskConfigurator 重新初始化 → 新录制开始     │
│  退出: LEAVE_SESSION → 仅退订，仿真继续运行                  │
│  停止: STOP → 清理 Redis + 结束录制 (保留文件)              │
└──────────────────────────────────────────────────────────────┘
```

## 8. 前端状态管理

```
┌─────────────────────────────────────────────┐
│            SimulationStore (Pinia)           │
├─────────────────────────────────────────────┤
│  sessionId, connected, tick, isRunning       │
│  config: { mapWidth, mapHeight, ... }       │
│  mapView: number[][]  (0=未探索, 1=已探索)   │
│  mapBlock: number[][] (0=空, 1=障碍)        │
│  cars: CarStateDTO[]                        │
│  hoveredCell, activeCarId                   │
├─────────────────────────────────────────────┤
│  handleStateUpdate(payload) ← WebSocket     │
│    · 更新 tick/config/cars                  │
│    · 解压 RLE 地图数据                      │
│    · 按 carId 数字排序 cars                 │
├─────────────────────────────────────────────┤
│  enterEditMode / exitEditMode               │
│  toggleObstacle / setCarPosition            │
│  getEditConfig → 发送到后端                 │
└──────────────┬──────────────────────────────┘
               │ watch (12 个属性)
               ▼
┌─────────────────────────────────────────────┐
│        useCanvasRenderer (RAF Loop)          │
├─────────────────────────────────────────────┤
│  8 层渲染:                                   │
│    1. 探索背景 (灰/白)                       │
│    2. 网格线                                  │
│    3. 障碍物 (红 X，仅已探索区域)              │
│    4. 路线 (半透明折线)                       │
│    5. 目标 (金色星形)                         │
│    6. 小车 (彩色圆 + 方向箭头 + 编号)         │
│    7. 悬停高亮                                │
│    8. HUD 信息面板                            │
└─────────────────────────────────────────────┘
```

## 9. WebSocket 命令全集

| 命令 | 方向 | 权限 | 功能 |
|------|------|------|------|
| PING | 客户端→服务器 | 已认证 | 心跳 |
| SET_CONFIG | 客户端→服务器 | USER/ADMIN | 初始化仿真 |
| RESUME | 客户端→服务器 | USER/ADMIN | 开始/恢复 |
| PAUSE | 客户端→服务器 | USER/ADMIN | 暂停 |
| STEP_ONCE | 客户端→服务器 | USER/ADMIN | 单步 |
| RESET | 客户端→服务器 | USER/ADMIN | 重置 |
| STOP | 客户端→服务器 | USER/ADMIN | 停止并清理 |
| ADD_CAR | 客户端→服务器 | USER/ADMIN | 增加小车 |
| DELETE_CAR | 客户端→服务器 | USER/ADMIN | 删除小车 |
| MOVE_CAR | 客户端→服务器 | USER/ADMIN | 移动小车 |
| JOIN_SESSION | 客户端→服务器 | 已认证 | 加入已有仿真 |
| LEAVE_SESSION | 客户端→服务器 | 已认证 | 离开仿真 |
| STATE_UPDATE | 服务器→客户端 | — | 状态推送 |
| MAP_LIST_UPDATED | 服务器→客户端 | — | 地图列表变更通知 |
| PONG | 服务器→客户端 | — | 心跳响应 |

## 10. 回放系统

```
录制: PushService.pushStateUpdate()
  → Recorder.startRecording (首 tick)
  → Recorder.recordTick (每 tick 追加一行 JSON)
  → 文件: data/replays/{sessionId}.jsonl
  → 最大 5000 tick，达到上限自动停止

回放: ReplayPage.vue
  → GET /api/replay/sessions (列表)
  → GET /api/replay/sessions/{id} (全量 tick)
  → store.handleStateUpdate(tick) 逐帧恢复
  → SimulationCanvas 渲染
  → 控制: 播放/暂停/步进/步退/进度条/0.5x-8x 倍速
```
