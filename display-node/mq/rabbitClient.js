const amqp = require('amqplib');
const config = require('../config');

class RabbitClient {
  constructor() {
    this.connection = null;
    this.channel = null;
    this.pushService = null;
    this._connected = false;
    this._subscribedSessions = new Set();
  }

  setPushService(ps) { this.pushService = ps; }
  get connected() { return this._connected; }

  async connect() {
    const vhost = config.mq.vhost.startsWith('/') ? config.mq.vhost : '/' + config.mq.vhost;
    const url = `amqp://${config.mq.username}:${config.mq.password}@${config.mq.host}:${config.mq.port}${vhost}`;
    try {
      this.connection = await amqp.connect(url);
      this.channel = await this.connection.createChannel();

      await this.channel.assertQueue(config.mq.controllerCmdQueue, { durable: true });
      this._connected = true;
      console.log(`[MQ] 已连接 ${config.mq.host}:${config.mq.port}`);

      this.connection.on('close', () => {
        this._connected = false;
        console.warn('[MQ] 连接断开，5s 后重连…');
        setTimeout(() => this.connect(), 5000);
      });

      // 重连后重新订阅已注册的 session
      for (const sid of this._subscribedSessions) {
        this._setupSessionConsumer(sid);
      }
    } catch (e) {
      this._connected = false;
      console.warn(`[MQ] 连接失败 (${config.mq.host}:${config.mq.port}): ${e.message}`);
    }
  }

  /** 订阅某个 session 的 REFRESH_ALL */
  async subscribeSession(sessionId) {
    if (this._subscribedSessions.has(sessionId)) {
      console.log(`[MQ] subscribeSession ${sessionId} (already subscribed)`);
      return;
    }
    console.log(`[MQ] subscribeSession ${sessionId} starting...`);
    this._subscribedSessions.add(sessionId);
    if (this._connected) {
      try {
        await this._setupSessionConsumer(sessionId);
        console.log(`[MQ] subscribeSession ${sessionId} DONE`);
      } catch (e) {
        console.error(`[MQ] subscribeSession ${sessionId} FAILED:`, e.message);
      }
    } else {
      console.warn(`[MQ] subscribeSession ${sessionId} skipped (not connected)`);
    }
  }

  async _setupSessionConsumer(sessionId) {
    const exchange = `UpdateView_${sessionId}`;
    const queue = `WSB_Refresh_${sessionId}`;

    await this.channel.assertExchange(exchange, 'fanout', { durable: true });
    await this.channel.assertQueue(queue, { durable: true });
    await this.channel.bindQueue(queue, exchange, '');

    await this.channel.consume(queue, (msg) => {
      if (!msg) return;
      try {
        const body = JSON.parse(msg.content.toString());
        if (body.cmd === 'REFRESH_ALL') {
          const d = body.data || {};
          const tick = d.tick || 0;
          const finished = d.finished || false;
          if (this.pushService) {
            this.pushService.pushStateUpdate(tick, sessionId, finished);
          }
        }
      } catch (e) {
        console.error(`[MQ] 处理消息失败 (session ${sessionId}):`, e.message);
      }
      this.channel.ack(msg);
    });

    console.log(`[MQ] 订阅 session: ${sessionId}`);
  }

  /** 发送命令到 Controller（初始化命令走共享队列，其他有 sessionId 走专属队列） */
  publishCommand(cmd, data) {
    if (!this.channel) return;
    const initCmds = ['SET_CONFIG', 'SET_MAP_EDIT', 'RESET'];
    const sid = (data && data.sessionId) ? data.sessionId : null;
    const queue = (sid && !initCmds.includes(cmd)) ? `ControllerCmd_${sid}` : config.mq.controllerCmdQueue;
    const message = { cmd, data: data || {}, timestamp: Date.now() };
    try {
      this.channel.sendToQueue(
        queue,
        Buffer.from(JSON.stringify(message)),
        { persistent: true }
      );
    } catch (e) {
      console.error('[MQ] 发送命令失败:', e.message);
    }
  }

  async close() {
    try {
      if (this.channel) await this.channel.close();
      if (this.connection) await this.connection.close();
    } catch (e) { }
  }
}

module.exports = RabbitClient;
