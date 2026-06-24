# 变电站巡检仿真系统 — 启动说明

## 前置依赖

| 依赖 | 版本 | 端口 |
|------|------|------|
| JDK | 17+ | — |
| Maven | 3.8+ | — |
| Redis | 7.x | 6379 |
| RabbitMQ | 4.x | 5672 / 15672 (管理) |
| Node.js | 20+ | — |

---

## 一、启动基础服务

```bash
# Redis
redis-server

# RabbitMQ
rabbitmq-server
```

---

## 二、启动 Display（Node.js 后端）

```bash
cd display-node
npm install        # 首次运行
node server.js     # 端口 8887
```

---

## 三、编译并启动各知识源

每个模块在独立终端中启动，启动顺序建议：

### 1. TaskConfigurator（任务配置器）
```bash
mvn compile -pl common,task-configurator -q

java -cp "common/target/common-1.0-SNAPSHOT.jar;task-configurator/target/task-configurator-1.0-SNAPSHOT.jar;%USERPROFILE%\.m2\repository\redis\clients\jedis\5.0.2\jedis-5.0.2.jar;%USERPROFILE%\.m2\repository\com\rabbitmq\amqp-client\5.18.0\amqp-client-5.18.0.jar;%USERPROFILE%\.m2\repository\com\alibaba\fastjson2\fastjson2\2.0.47\fastjson2-2.0.47.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-classic\1.5.6\logback-classic-1.5.6.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-core\1.5.6\logback-core-1.5.6.jar;%USERPROFILE%\.m2\repository\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar" inspection.taskconfigurator.TaskConfiguratorMain
```

### 2. Controller（调度控制器）
```bash
java -cp "common/target/common-1.0-SNAPSHOT.jar;controller/target/controller-1.0-SNAPSHOT.jar;%USERPROFILE%\.m2\repository\redis\clients\jedis\5.0.2\jedis-5.0.2.jar;%USERPROFILE%\.m2\repository\com\rabbitmq\amqp-client\5.18.0\amqp-client-5.18.0.jar;%USERPROFILE%\.m2\repository\com\alibaba\fastjson2\fastjson2\2.0.47\fastjson2-2.0.47.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-classic\1.5.6\logback-classic-1.5.6.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-core\1.5.6\logback-core-1.5.6.jar;%USERPROFILE%\.m2\repository\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar" inspection.controller.ControllerMain
```

### 3. Navigator（路径规划器）
```bash
java -cp "common/target/common-1.0-SNAPSHOT.jar;navigator/target/navigator-1.0-SNAPSHOT.jar;%USERPROFILE%\.m2\repository\redis\clients\jedis\5.0.2\jedis-5.0.2.jar;%USERPROFILE%\.m2\repository\com\rabbitmq\amqp-client\5.18.0\amqp-client-5.18.0.jar;%USERPROFILE%\.m2\repository\com\alibaba\fastjson2\fastjson2\2.0.47\fastjson2-2.0.47.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-classic\1.5.6\logback-classic-1.5.6.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-core\1.5.6\logback-core-1.5.6.jar;%USERPROFILE%\.m2\repository\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar" inspection.navigator.NavigatorMain
```

### 4. TargetPlanner（目标规划器）
```bash
java -cp "common/target/common-1.0-SNAPSHOT.jar;target-planner/target/target-planner-1.0-SNAPSHOT.jar;%USERPROFILE%\.m2\repository\redis\clients\jedis\5.0.2\jedis-5.0.2.jar;%USERPROFILE%\.m2\repository\com\rabbitmq\amqp-client\5.18.0\amqp-client-5.18.0.jar;%USERPROFILE%\.m2\repository\com\alibaba\fastjson2\fastjson2\2.0.47\fastjson2-2.0.47.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-classic\1.5.6\logback-classic-1.5.6.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-core\1.5.6\logback-core-1.5.6.jar;%USERPROFILE%\.m2\repository\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar" inspection.targetplanner.TargetPlannerMain
```

### 5. Car（小车知识源，每车一个进程）
```bash
java -cp "common/target/common-1.0-SNAPSHOT.jar;car/target/car-1.0-SNAPSHOT.jar;%USERPROFILE%\.m2\repository\redis\clients\jedis\5.0.2\jedis-5.0.2.jar;%USERPROFILE%\.m2\repository\com\rabbitmq\amqp-client\5.18.0\amqp-client-5.18.0.jar;%USERPROFILE%\.m2\repository\com\alibaba\fastjson2\fastjson2\2.0.47\fastjson2-2.0.47.jar;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-classic\1.5.6\logback-classic-1.5.6.jar;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-core\1.5.6\logback-core-1.5.6.jar;%USERPROFILE%\.m2\repository\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar" inspection.car.CarMain
```

---

## 四、构建前端（可选）

```bash
cd web
npm install       # 首次运行
npm run dev       # 开发模式（端口 5173，代理到 8887）
npm run build     # 生产构建 → web/dist/
```

---

## 五、访问地址

| 服务 | 地址 |
|------|------|
| 前端（开发） | http://localhost:5173 |
| Display 后端 | http://localhost:8887 |
| RabbitMQ 管理 | http://localhost:15672 |
| Redis | localhost:6379 |

**默认管理员**：admin / admin123

---

## 六、连接配置

所有服务统一读取以下环境变量：

```bash
# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# RabbitMQ
MQ_HOST=localhost
MQ_PORT=5672
MQ_USERNAME=guest
MQ_PASSWORD=guest
```

非 localhost 部署时在终端中 `export` 或在启动命令前加上即可。
