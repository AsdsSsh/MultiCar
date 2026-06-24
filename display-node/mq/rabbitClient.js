// RabbitMQ 客户端 — 监听 REFRESH_ALL + 发布命令
// 与 Java RefreshAllListener + ControllerCmdPublisher 一致
const amqp = require('amqplib');
const config = require('../config');

class RabbitClient {
  constructor() {
    this.connection = null;
    this.channel = null;
    this.pushService = null;
  }

  setPushService(ps) {
    this.pushService = ps;
  }

  async connect() {
    const url = `amqp://${config.mq.username}:${config.mq.password}@${config.mq.host}:${config.mq.port}${config.mq.vhost}`;
    this.connection = await amqp.connect(url);
    this.channel = await this.connection.createChannel();

    // 声明 exchange 和队列
    await this.channel.assertExchange(config.mq.fanoutExchange, 'fanout', { durable: true });
    await this.channel.assertQueue(config.mq.refreshQueue, { durable: true });
    await this.channel.assertQueue(config.mq.controllerCmdQueue, { durable: true });
    await this.channel.bindQueue(config.mq.refreshQueue, config.mq.fanoutExchange, '');

    // 监听 REFRESH_ALL
    await this.channel.consume(config.mq.refreshQueue, (msg) => {
      if (!msg) return;
      try {
        const body = JSON.parse(msg.content.toString());
        if (body.cmd === 'REFRESH_ALL') {
          const tick = (body.data && body.data.tick) ? body.data.tick : 0;
          console.log(`[MQ] 收到 REFRESH_ALL tick=${tick}`);
          if (this.pushService) {
            this.pushService.pushStateUpdate(tick);
          }
        }
      } catch (e) {
        console.error('[MQ] 处理消息失败:', e.message);
      }
      this.channel.ack(msg);
    });

    console.log(`RabbitMQ 已连接: ${config.mq.host}:${config.mq.port}`);
  }

  /** 发布控制命令到 ControllerCmd 队列 */
  publishCommand(cmd, data) {
    if (!this.channel) return;
    const message = {
      cmd,
      data: data || {},
      timestamp: Date.now(),
    };
    this.channel.sendToQueue(
      config.mq.controllerCmdQueue,
      Buffer.from(JSON.stringify(message)),
      { persistent: true }
    );
    console.log(`[MQ] 发送命令: cmd=${cmd}`);
  }

  async close() {
    if (this.channel) await this.channel.close();
    if (this.connection) await this.connection.close();
  }
}

module.exports = RabbitClient;
