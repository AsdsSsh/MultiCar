// RabbitMQ 客户端 — 监听 REFRESH_ALL + 发布命令
const amqp = require('amqplib');
const config = require('../config');

class RabbitClient {
  constructor() {
    this.connection = null;
    this.channel = null;
    this.pushService = null;
    this._connected = false;
  }

  setPushService(ps) {
    this.pushService = ps;
  }

  get connected() { return this._connected; }

  async connect() {
    const vhost = config.mq.vhost.startsWith('/') ? config.mq.vhost : '/' + config.mq.vhost;
    const url = `amqp://${config.mq.username}:${config.mq.password}@${config.mq.host}:${config.mq.port}${vhost}`;
    try {
      this.connection = await amqp.connect(url);
      this.channel = await this.connection.createChannel();

      await this.channel.assertExchange(config.mq.fanoutExchange, 'fanout', { durable: true });
      await this.channel.assertQueue(config.mq.refreshQueue, { durable: true });
      await this.channel.assertQueue(config.mq.controllerCmdQueue, { durable: true });
      await this.channel.bindQueue(config.mq.refreshQueue, config.mq.fanoutExchange, '');

      await this.channel.consume(config.mq.refreshQueue, (msg) => {
        if (!msg) return;
        try {
          const body = JSON.parse(msg.content.toString());
          if (body.cmd === 'REFRESH_ALL') {
            const tick = (body.data && body.data.tick) ? body.data.tick : 0;
            if (this.pushService) {
              this.pushService.pushStateUpdate(tick);
            }
          }
        } catch (e) {
          console.error('[MQ] 处理消息失败:', e.message);
        }
        this.channel.ack(msg);
      });

      this._connected = true;
      console.log(`[MQ] 已连接 ${config.mq.host}:${config.mq.port}`);

      // 连接断开时尝试重连
      this.connection.on('close', () => {
        this._connected = false;
        console.warn('[MQ] 连接断开，5s 后重连…');
        setTimeout(() => this.connect(), 5000);
      });
    } catch (e) {
      this._connected = false;
      console.warn(`[MQ] 连接失败 (${config.mq.host}:${config.mq.port}): ${e.message}`);
    }
  }

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
    } catch (e) { /* ignore */ }
  }
}

module.exports = RabbitClient;
