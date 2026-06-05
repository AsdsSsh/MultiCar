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
