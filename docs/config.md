# 外部连接配置说明

## 总览

所有服务的连接配置通过**环境变量**统一控制，默认值指向 `localhost`，本地开发无需任何设置。

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| Redis 地址 | `REDIS_HOST` | localhost | |
| Redis 端口 | `REDIS_PORT` | 6379 | |
| Redis 密码 | `REDIS_PASSWORD` | (空) | |
| RabbitMQ 地址 | `MQ_HOST` | localhost | |
| RabbitMQ 端口 | `MQ_PORT` | 5672 | |
| RabbitMQ 用户名 | `MQ_USERNAME` | guest | |
| RabbitMQ 密码 | `MQ_PASSWORD` | guest | |
| Display 端口 | `SERVER_PORT` | 8887 | 仅 Node.js 后端 |
| JWT 密钥 | `JWT_SECRET` | (内置默认) | 仅 Node.js 后端 |
| 前端 WS 地址 | `VITE_WS_URL` | `ws://localhost:8887/ws/simulation` | 仅前端 |

---

## 一、Java 服务（controller / car / navigator / target-planner / task-configurator）

**配置加载链**：

```
环境变量  >  系统属性(-D)  >  config.properties  >  硬编码默认值
    ↑              ↑                  ↑
REDIS_HOST    redis.host       common/.../resources/config.properties
```

**涉及文件**：

| 文件 | 作用 |
|------|------|
| `common/src/main/resources/config.properties` | 配置文件（兜底默认值） |
| `common/.../config/ConnectionConfig.java` | 配置加载器（按优先级读取） |
| `common/.../blackboard/BlackboardConfig.java` | Redis 配置 POJO |
| `common/.../messaging/MessageConfig.java` | RabbitMQ 配置 POJO |

**config.properties 内容**：
```properties
redis.host=localhost
redis.port=6379
redis.password=
mq.host=localhost
mq.port=5672
mq.username=guest
mq.password=guest
mq.virtualHost=/
```

**使用方式**：每个 Main 类通过 `ConnectionConfig.loadBlackboardConfig()` 和 `ConnectionConfig.loadMessageConfig()` 获取配置，无需各自硬编码。

---

## 二、Display Node.js 后端（display-node）

**配置加载**：环境变量直接读取，无中间配置文件。

**涉及文件**：

| 文件 | 作用 |
|------|------|
| `display-node/config.js` | 环境变量读取 + 默认值 |

**config.js 关键代码**：
```js
const config = {
  server: { port: parseInt(process.env.SERVER_PORT || '8887', 10) },
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6379', 10),
    password: process.env.REDIS_PASSWORD || undefined,
  },
  mq: {
    host: process.env.MQ_HOST || 'localhost',
    port: parseInt(process.env.MQ_PORT || '5672', 10),
    username: process.env.MQ_USERNAME || 'guest',
    password: process.env.MQ_PASSWORD || 'guest',
    vhost: process.env.MQ_VHOST || '/',
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'BlackBoxAI-Secret-Key-2024-Must-Be-Changed',
    expiresMs: 24 * 60 * 60 * 1000,
  },
};
```

---

## 三、Vue 前端（web）

**涉及文件**：

| 文件 | 作用 | 关键配置 |
|------|------|----------|
| `web/src/utils/constants.js` | 全局常量 | `WS_URL`（WebSocket 地址） |
| `web/vite.config.js` | Vite 构建配置 | 开发端口、API 代理、构建输出 |

**constants.js — WS_URL**：
```js
// 通过环境变量 VITE_WS_URL 覆盖，默认 localhost:8887
export const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8887/ws/simulation'
```

**vite.config.js — 开发代理**：
```js
server: {
  port: 5173,                    // 前端开发端口
  proxy: {
    '/api': {
      target: 'http://localhost:8887',  // 代理到 Display 后端
      changeOrigin: true
    },
    '/ws': {
      target: 'ws://localhost:8887',    // WebSocket 代理
      ws: true
    }
  }
}
```

> **注意**：`build.outDir` 目前指向已删除的 `display/` 目录，需要更新为独立输出路径。

---

## 四、部署到非 localhost 环境

假设 Redis 在 `10.0.0.5`，RabbitMQ 在 `10.0.0.6`，后端部署在 `10.0.0.10`：

**Linux/macOS**：
```bash
export REDIS_HOST=10.0.0.5
export MQ_HOST=10.0.0.6
export MQ_USERNAME=admin
export MQ_PASSWORD=secret

# 启动所有 Java 服务 + Node.js 后端
```

**Windows PowerShell**：
```powershell
$env:REDIS_HOST = "10.0.0.5"
$env:MQ_HOST = "10.0.0.6"
$env:MQ_USERNAME = "admin"
$env:MQ_PASSWORD = "secret"
```

**前端构建时指定后端地址**：
```bash
# 方式一：构建时设置
VITE_WS_URL=ws://10.0.0.10:8887/ws/simulation npm run build

# 方式二：创建 web/.env.production
VITE_WS_URL=ws://10.0.0.10:8887/ws/simulation
```
