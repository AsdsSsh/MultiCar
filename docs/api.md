# API 参考

## HTTP REST API

Base URL: `http://localhost:8887`

所有需要认证的接口需带 `Authorization: Bearer {token}` 请求头。

---

### 认证 `/api/auth`

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/auth/login` | 否 | 登录，返回 JWT token |
| POST | `/api/auth/register` | 否 | 注册（需管理员审批） |
| GET | `/api/auth/whoami` | 是 | 验证 token，返回用户信息 |

**登录请求**: `{ username, password }`
**登录响应**: `{ success, token, user: { id, username, role } }`

---

### 管理员 `/api/admin`（需 ADMIN）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users` | 列出所有用户 |
| POST | `/api/admin/users` | 创建用户 `{ username, password, role }` |
| POST | `/api/admin/users/:id/reset-password` | 重置密码 `{ newPassword }` |
| DELETE | `/api/admin/users/:id` | 删除用户 |
| GET | `/api/admin/registrations` | 列出注册请求 |
| POST | `/api/admin/registrations/:id/approve` | 审批通过 |
| POST | `/api/admin/registrations/:id/reject` | 拒绝注册 |

---

### 地图配置 `/api/config`

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/config/list` | 否 | 列出所有地图 |
| GET | `/api/config/get` | 否 | 获取默认配置 |
| GET | `/api/config/get/:mapId` | 否 | 获取指定地图 |
| POST | `/api/config/save` | CONFIGURATOR/ADMIN | 保存地图 |
| PUT | `/api/config/:mapId` | CONFIGURATOR/ADMIN | 更新地图 |
| DELETE | `/api/config/:mapId` | CONFIGURATOR/ADMIN | 删除地图 |

**地图对象**:
```json
{
  "id": "uuid8",
  "name": "地图名",
  "mapWidth": 40, "mapHeight": 30,
  "carCount": 5, "obstacleDensity": 0.2,
  "algorithm": "A_STAR",
  "obstacles": [{"x": 20, "y": 12}],
  "carPositions": [{"carId": "Car001", "x": 7, "y": 12}],
  "createdAt": 1700000000000, "createdBy": "username"
}
```

---

### 仿真管理 `/api/simulation`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/simulation/list` | 列出所有仿真（附带地图名） |
| POST | `/api/simulation/create` | 创建仿真 `{ name, mapId }` |
| GET | `/api/simulation/:id` | 获取仿真详情 |
| POST | `/api/simulation/:id/start` | 标记仿真运行中 |
| POST | `/api/simulation/:id/stop` | 标记仿真停止 |
| DELETE | `/api/simulation/:id` | 删除仿真（仅创建者/ADMIN） |

**仿真对象**:
```json
{
  "id": "uuid8", "name": "仿真名",
  "mapId": "map-uuid", "sessionId": "同id",
  "status": "inactive|running|paused",
  "createdAt": 1700000000000, "createdBy": "username",
  "mapName": "关联地图名"
}
```

---

### 回放 `/api/replay`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/replay/sessions` | 列出录制（过滤 tickCount < 2） |
| GET | `/api/replay/sessions/:sessionId` | 获取全部 tick 数组 |
| GET | `/api/replay/sessions/:sessionId/range?from=0&to=100` | 获取指定范围 tick |

**录制列表项**:
```json
{
  "sessionId": "abc12345",
  "tickCount": 250,
  "startTime": 1700000000000,
  "config": { "mapWidth": 40, "mapHeight": 30, "carCount": 5 }
}
```

---

## WebSocket 协议

**连接**: `ws://localhost:8887/ws/simulation?token={jwt}`

### 客户端→服务器 (cmd)

```json
{ "cmd": "SET_CONFIG", "data": { "sessionId": "...", "mapWidth": 40, ... }, "timestamp": 1700000000000 }
```

| cmd | data 字段 | 说明 |
|-----|-----------|------|
| `PING` | `{}` | 心跳，服务器回复 PONG |
| `SET_CONFIG` | sessionId, mapWidth, mapHeight, carCount, obstacleDensity, algorithm, customObstacles[], carPositions[] | 初始化仿真 |
| `RESUME` | sessionId | 开始/恢复 |
| `PAUSE` | sessionId | 暂停 |
| `STEP_ONCE` | sessionId | 单步执行 |
| `RESET` | sessionId | 重置仿真 |
| `STOP` | sessionId | 停止并清理 |
| `ADD_CAR` | sessionId, [x, y] | 增加小车（无 x/y 则自动选位） |
| `DELETE_CAR` | sessionId, carId | 删除小车 |
| `MOVE_CAR` | sessionId, carId, x, y | 移动小车 |
| `JOIN_SESSION` | sessionId | 加入已有仿真（不重置） |
| `LEAVE_SESSION` | sessionId | 离开仿真（不停止） |

### 服务器→客户端 (type)

| type | 字段 | 说明 |
|------|------|------|
| `STATE_UPDATE` | sessionId, tick, running, config, cars[], mapWidth, mapHeight, mapView/mapBlock (or compressed) | 状态推送 |
| `MAP_LIST_UPDATED` | (无) | 地图列表变更 |
| `PONG` | (无) | 心跳响应 |
| `ERROR` | message | 错误通知 |

### STATE_UPDATE 结构

```json
{
  "type": "STATE_UPDATE",
  "sessionId": "abc12345",
  "tick": 120,
  "running": true,
  "config": {
    "mapWidth": 40, "mapHeight": 30,
    "carCount": 5, "obstacleDensity": 0.2,
    "algorithm": "A_STAR"
  },
  "mapWidth": 40,
  "mapHeight": 30,
  "cars": [
    {
      "carId": "Car001", "displayId": "Car#001",
      "x": 15, "y": 10,
      "targetX": 20, "targetY": 12,
      "route": [{"x":16,"y":10}, ...],
      "status": "MOVING",
      "steps": 45
    }
  ],
  "mapViewCompressed": [[...]],   // RLE 压缩时存在
  "mapBlockCompressed": [[...]],  // RLE 压缩时存在
  "compressed": true
}
```
