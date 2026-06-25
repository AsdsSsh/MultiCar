# 变电站巡检仿真系统

基于黑板架构的多车协作巡检仿真系统。

## 技术栈

- Java 17
- Maven
- Redis（黑板存储）
- RabbitMQ（消息队列）
- fastjson2（JSON序列化）

## 项目结构

```
BlackBoxAI/
├── pom.xml                    # 父POM
├── common/                    # 公共模块（成员A负责）
│   ├── model/                 # 数据模型（Point, CarStatus, CarInfo, SimulationState）
│   ├── blackboard/            # Redis封装（BlackboardClient, DistributedLock）
│   ├── messaging/             # RabbitMQ封装（MessageBus）
│   └── util/                  # 工具常量（Constants）
├── task-configurator/         # 任务配置器（成员A负责）
│   ├── TaskConfiguratorAgent.java
│   └── TaskConfiguratorMain.java
├── controller/                # 调度控制器（成员B负责）
├── car/                       # 小车知识源（成员B负责）
├── navigator/                 # 导航器（成员C负责）
├── target-planner/            # 目标规划器（成员C负责）
├── display/                   # 显示桥接器+Web（成员D负责）
└── launcher/                  # 一键启动
```

## 成员A负责内容

### 1. Common 公共模块

给 B、C、D 提供统一的底层接口：

#### BlackboardClient（Redis操作）
- 小车位置：`getCarPosition`, `setCarPosition`
- 小车状态：`getCarStatus`, `setCarStatus`
- 小车目标：`getCarTarget`, `setCarTarget`
- 小车路径：`getCarRouteList`, `peekNextRouteStep`, `popNextRouteStep`, `setCarRouteList`, `clearCarRouteList`
- 小车步数：`getCarSteps`, `setCarSteps`, `incrementCarSteps`
- 受阻节拍：`getCarBlockedTick`, `setCarBlockedTick`
- 地图视野：`getMapViewBit`, `setMapViewBit`, `getFullMapView`
- 障碍物：`getMapBlockBit`, `setMapBlockBit`, `getFullMapBlock`
- 任务配置：`getTaskConfig`, `setTaskConfig`, `isTaskActive`
- 探索率：`getExploredPercent`
- 批量操作：`setMapViewBitsBatch`, `setMapBlockBitsBatch`
- 快照数据：`getAllCarPositions`, `getAllCarStatuses`, `getAllCarSteps`
- 清空：`clearAll`

#### DistributedLock（分布式锁）
- `tryLock(lockKey, requestId, expireMs)`
- `unlock(lockKey, requestId)`
- `executeWithLock(lockKey, operation)`

#### MessageBus（RabbitMQ消息）
- `publish(queueName, cmd, data)` — 点对点发送
- `fanoutPublish(exchangeName, cmd, data)` — 广播
- `subscribe(queueName, handler)` — 订阅队列
- `subscribeFanout(exchangeName, queueName, handler)` — 订阅广播
- `declareQueue`, `declareFanoutExchange`, `declareAllSystemQueues`

### 2. TaskConfigurator（任务配置器）

独立进程，负责初始化和重置：

- 监听 `TaskConfigCmd` 队列
- 收到 `FORWARD_CONFIG` → 初始化黑板数据
  1. 清空黑板
  2. 写入 TaskConfig
  3. 随机生成障碍物（避开小车位置）
  4. 初始化5台小车（位置、状态=IDLE、步数=0）
  5. 标记小车为动态障碍
  6. 点亮初始3x3视野
  7. 声明所有MQ队列
  8. 通知 Controller `TASK_READY`
- 收到 `FORWARD_RESET` → 重新初始化

## 运行方式

### 前置条件
1. 启动 Redis：`redis-server`
2. 启动 RabbitMQ：`rabbitmq-server`

### 构建
```bash
mvn clean package -DskipTests
```

### 独立进程启动（成员A的部分）
```bash
# TaskConfigurator
java -cp common/target/common-1.0-SNAPSHOT.jar;task-configurator/target/task-configurator-1.0-SNAPSHOT.jar inspection.taskconfigurator.TaskConfiguratorMain
```

### 一键启动（开发调试）
```bash
java -jar launcher/target/launcher-1.0-SNAPSHOT.jar
```

## 消息流程

```
Web浏览器 → WSB → ControllerCmd → Controller → TaskConfigCmd → TaskConfigurator
                                                        ↓
                                              初始化黑板 → TASK_READY → Controller
                                                        ↓
                                              开始节拍循环调度
```

## 分布式架构

系统基于**黑板架构**，各组件通过 RabbitMQ + Redis 解耦协作，支持跨机器部署。

### 组件部署形态

| 组件 | 多实例 | 说明 |
|------|--------|------|
| Controller | 单实例 | 集中式节拍调度器，维护 Session 状态 |
| Navigator | 多实例 | 无状态路径规划，MQ 轮询分发 |
| TargetPlanner | 单实例 | 内存中维护批分配缓存 |
| CarPool | 多实例 | 无状态模式，每次 TICK_MOVE 创建临时 CarAgent |
| Car (独立模式) | 多实例 | 每辆小车独立进程，独立 MQ 队列 |
| TaskConfigurator | 多实例 | 无状态，处理一次性初始化命令 |

### 通信机制

| 机制 | 中间件 | 用途 |
|------|--------|------|
| 消息队列 | RabbitMQ | 进程间异步命令 |
| 共享状态 | Redis | 黑板存储，所有进程读写 |
| 分布式锁 | Redis (SET NX PX + Lua) | 小车粒度的互斥访问 |
| WebSocket | ws (Node.js) | 实时推送状态到浏览器 |
| HTTP REST | Express | 前端 API 调用 |

### 配置方式

通过环境变量覆盖默认配置（优先级：环境变量 > 系统属性 > config.properties）：

- `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`
- `MQ_HOST` / `MQ_PORT` / `MQ_USERNAME` / `MQ_PASSWORD` / `MQ_VHOST`

### 多实例部署

详见 [docs/multi-instance-deployment.md](docs/multi-instance-deployment.md)
