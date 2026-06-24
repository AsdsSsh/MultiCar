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
    if (this._subscribedSessions.has(sessionId)) return;
    this._subscribedSessions.add(sessionId);
    if (this._connected) await this._setupSessionConsumer(sessionId);
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
          const tick = (body.data && body.data.tick) ? body.data.tick : 0;
          if (this.pushService) {
            this.pushService.pushStateUpdate(tick, sessionId);
          }
        }
      } catch (e) {
        console.error(`[MQ] 处理消息失败 (session ${sessionId}):`, e.message);
      }
      this.channel.ack(msg);
    });

    console.log(`[MQ] 订阅 session: ${sessionId}`);
  }

  /** 发送命令到 ControllerCmd（携带 sessionId） */
  publishCommand(cmd, data) {
    if (!this.channel) return;
    const message = { cmd, data: data || {}, timestamp: Date.now() };
    try {
      this.channel.sendToQueue(
        config.mq.controllerCmdQueue,
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
