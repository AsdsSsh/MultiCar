# BlackBoxAI 文档索引

## 文档列表

| 文档 | 内容 |
|------|------|
| [architecture.md](architecture.md) | 系统架构全景：技术栈、项目结构、多智能体消息流、Redis 键空间、RabbitMQ 拓扑、完整仿真生命周期、前端状态管理、WebSocket 协议、回放系统 |
| [api.md](api.md) | API 完整参考：HTTP REST（认证/管理/地图/仿真/回放）+ WebSocket 协议（命令表 + STATE_UPDATE 结构） |

## 快速导航

- **架构图** → [architecture.md#1-系统概览](architecture.md#1-系统概览)
- **完整仿真流程** → [architecture.md#7-完整仿真生命周期](architecture.md#7-完整仿真生命周期)
- **多智能体消息流** → [architecture.md#4-多智能体架构消息流](architecture.md#4-多智能体架构消息流)
- **Redis 键空间** → [architecture.md#5-redis-键空间设计](architecture.md#5-redis-键空间设计)
- **WebSocket 协议** → [api.md#websocket-协议](api.md#websocket-协议)
- **HTTP API** → [api.md#http-rest-api](api.md#http-rest-api)
- **回放机制** → [architecture.md#10-回放系统](architecture.md#10-回放系统)

## 系统启动顺序

1. **Redis** (端口 6379)
2. **RabbitMQ** (端口 5672)
3. **Java 后端** — 启动顺序任意：TaskConfigurator → Controller → Navigator → TargetPlanner → CarPool
4. **display-node** — `cd display-node && npm start` (端口 8887)
5. **web 前端** — `cd web && npm run dev` (端口 5173)

## 角色权限

| 角色 | 首页 | 权限 |
|------|------|------|
| ADMIN | /admin | 用户管理、注册审批、所有功能 |
| CONFIGURATOR | /configurator | 地图创建和编辑 |
| USER | /user | 创建/加入仿真、控制运行、查看回放 |
