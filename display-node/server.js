// 展示层 Node.js 后端 — 取代 Spring Boot display 模块
// 纯 API + WebSocket 服务，不嵌入前端静态文件
const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const cors = require('cors');
const config = require('./config');
const { authMiddleware } = require('./auth/middleware');
const BlackboardReader = require('./redis/blackboardReader');
const PushService = require('./services/pushService');
const RabbitClient = require('./mq/rabbitClient');
const WsHandler = require('./ws/wsHandler');

const authRoutes = require('./routes/auth');
const adminRoutes = require('./routes/admin');
const mapConfigRoutes = require('./routes/mapConfig');

async function main() {
  const app = express();
  const server = http.createServer(app);

  // CORS + JSON body parser
  app.use(cors());
  app.use(express.json());
  app.use(authMiddleware);

  // 初始化 Redis
  const blackboardReader = new BlackboardReader();
  await blackboardReader.connect();

  // 初始化服务
  const pushService = new PushService(blackboardReader);
  app.set('pushService', pushService); // 供 mapConfig 路由广播 MAP_LIST_UPDATED

  // 初始化 RabbitMQ
  const rabbitClient = new RabbitClient();
  rabbitClient.setPushService(pushService);
  await rabbitClient.connect();

  // WebSocket /ws/simulation
  const wsHandler = new WsHandler(pushService, rabbitClient);
  const wss = new WebSocketServer({ server, path: '/ws/simulation' });
  wss.on('connection', (ws, req) => wsHandler.onConnection(ws, req));

  // REST API 路由
  app.use('/api/auth', authRoutes);
  app.use('/api/admin', adminRoutes);
  app.use('/api/config', mapConfigRoutes);

  // 启动
  server.listen(config.server.port, () => {
    console.log('═════════════════════════════════════');
    console.log(`  Display Node.js 后端已启动`);
    console.log(`  HTTP:  http://localhost:${config.server.port}`);
    console.log(`  WS:    ws://localhost:${config.server.port}/ws/simulation`);
    console.log('═════════════════════════════════════');
  });
}

main().catch(err => {
  console.error('启动失败:', err);
  process.exit(1);
});
