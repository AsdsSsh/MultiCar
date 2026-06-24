// Display Node.js 后端 — 纯 API + WebSocket，不嵌入前端
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
const replayRoutes = require('./routes/replay');
const simulationRoutes = require('./routes/simulation');
const jsonFileStore = require('./store/jsonFileStore');

async function main() {
  const app = express();
  const server = http.createServer(app);

  app.use(cors());
  app.use(express.json());
  app.use(authMiddleware);

  const blackboardReader = new BlackboardReader();
  await blackboardReader.connect();

  const pushService = new PushService(blackboardReader);
  app.set('pushService', pushService);

  const rabbitClient = new RabbitClient();
  rabbitClient.setPushService(pushService);
  await rabbitClient.connect();

  const wsHandler = new WsHandler(pushService, rabbitClient);
  const wss = new WebSocketServer({ server, path: '/ws/simulation' });
  wss.on('connection', (ws, req) => wsHandler.onConnection(ws, req));

  app.use('/api/auth', authRoutes);
  app.use('/api/admin', adminRoutes);
  app.use('/api/config', mapConfigRoutes);
  app.use('/api/replay', replayRoutes);
app.use('/api/simulation', simulationRoutes);

  await jsonFileStore.initRedis();

  server.listen(config.server.port, () => {
    console.log('═════════════════════════════════════');
    console.log(`  Display Node.js 后端已启动 (multi-session)`);
    console.log(`  HTTP:    http://localhost:${config.server.port}`);
    console.log(`  WS:      ws://localhost:${config.server.port}/ws/simulation`);
    console.log(`  Redis:   ${config.redis.host}:${config.redis.port}`);
    console.log(`  RabbitMQ:${config.mq.host}:${config.mq.port}`);
    console.log('═════════════════════════════════════');
  });
}

main().catch(err => {
  console.error('启动失败:', err.message);
  process.exit(1);
});
