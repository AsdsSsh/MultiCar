// 统一配置：环境变量 > 默认值
// 与 common 模块 ConnectionConfig.java 读取相同环境变量

const config = {
  server: {
    port: parseInt(process.env.SERVER_PORT || '8887', 10),
  },

  redis: {
    host: process.env.REDIS_HOST || '{{YOUR_REDIS_HOST}}',
    port: parseInt(process.env.REDIS_PORT || '6379', 10),
    password: process.env.REDIS_PASSWORD || '{{YOUR_REDIS_PASSWORD}}',
  },

  mq: {
    host: process.env.MQ_HOST || '{{YOUR_MQ_HOST}}',
    port: parseInt(process.env.MQ_PORT || '5672', 10),
    username: process.env.MQ_USERNAME || '{{YOUR_MQ_USERNAME}}',
    password: process.env.MQ_PASSWORD || '{{YOUR_MQ_PASSWORD}}',
    vhost: process.env.MQ_VHOST || '{{YOUR_MQ_VHOST}}',
    fanoutExchange: 'UpdateView',
    refreshQueue: 'WSB_Refresh',
    controllerCmdQueue: 'ControllerCmd',
  },

  jwt: {
    secret: process.env.JWT_SECRET || '{{YOUR_JWT_SECRET}}',
    expiresMs: 24 * 60 * 60 * 1000, // 24小时
  },

  carKeyPrefix: 'Car',
};

module.exports = config;
