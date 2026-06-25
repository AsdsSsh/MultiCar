package inspection.common.messaging;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.*;
import inspection.common.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 消息总线封装
 * 提供 publish / subscribe / fanout 三种通信方式
 * 给 B、C、D 提供统一的消息收发接口
 */
public class MessageBus {
    private static final Logger logger = LoggerFactory.getLogger(MessageBus.class);

    private final MessageConfig config;
    private Connection connection;
    private Channel channel;

    public MessageBus(MessageConfig config) {
        this.config = config;
    }

    public MessageBus() {
        this(new MessageConfig());
    }

    /**
     * 连接到 RabbitMQ
     */
    public void connect() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());

        // 跨机器部署优化：连接超时、心跳保活、自动恢复
        factory.setConnectionTimeout(5000);
        factory.setRequestedHeartbeat(30);
        factory.setNetworkRecoveryInterval(5000);
        factory.setAutomaticRecoveryEnabled(true);

        this.connection = factory.newConnection();
        this.channel = connection.createChannel();
        logger.info("已连接到 RabbitMQ: {}:{}", config.getHost(), config.getPort());
    }

    /**
     * 声明队列（Classic Queue）
     */
    public void declareQueue(String queueName) throws IOException {
        channel.queueDeclare(queueName, true, false, false, null);
        logger.debug("声明队列: {}", queueName);
    }

    /**
     * 声明 Fanout 交换器
     */
    public void declareFanoutExchange(String exchangeName) throws IOException {
        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, true);
        logger.debug("声明Fanout交换器: {}", exchangeName);
    }

    /**
     * 绑定队列到 Fanout 交换器
     */
    public void bindQueueToFanout(String queueName, String exchangeName) throws IOException {
        channel.queueBind(queueName, exchangeName, "");
        logger.debug("绑定队列 {} 到交换器 {}", queueName, exchangeName);
    }

    /** 声明共享队列（所有 session 共用，只需声明一次） */
    public void declareSharedQueues() throws IOException {
        declareQueue(Constants.QUEUE_CONTROLLER_CMD);
        declareQueue(Constants.QUEUE_NAVIGATOR_CMD);
        declareQueue(Constants.QUEUE_TARGET_PLANNER_CMD);
        declareQueue(Constants.QUEUE_TASK_CONFIG_CMD);
        logger.info("共享队列声明完成");
    }

    /** 声明指定 session 的队列（Fanout + 刷新队列 + CarPool 队列） */
    public void declareSessionQueues(String sessionId, int carCount) throws IOException {
        String fanoutExchange = Constants.getSessionFanoutExchange(sessionId);
        String refreshQueue = Constants.getSessionRefreshQueue(sessionId);

        declareFanoutExchange(fanoutExchange);
        declareQueue(refreshQueue);
        bindQueueToFanout(refreshQueue, fanoutExchange);

        declareQueue(Constants.QUEUE_CAR_POOL);

        logger.info("Session {} 队列声明完成", sessionId);
    }

    /**
     * 发送消息到指定队列（点对点）
     * @param queueName 目标队列
     * @param cmd 命令类型
     * @param data 数据对象（会被序列化为JSON）
     */
    public void publish(String queueName, String cmd, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("cmd", cmd);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String json = message.toJSONString();
            channel.basicPublish("", queueName, null, json.getBytes(StandardCharsets.UTF_8));
            logger.debug("发送消息到队列 {}: cmd={}", queueName, cmd);
        } catch (Exception e) {
            logger.error("发送消息失败: queue={}, cmd={}, error={}", queueName, cmd, e.getMessage());
        }
    }

    /**
     * 广播消息到 Fanout 交换器
     * @param exchangeName 交换器名
     * @param cmd 命令类型
     * @param data 数据对象
     */
    public void fanoutPublish(String exchangeName, String cmd, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("cmd", cmd);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String json = message.toJSONString();
            channel.basicPublish(exchangeName, "", null, json.getBytes(StandardCharsets.UTF_8));
            logger.debug("广播消息: exchange={}, cmd={}", exchangeName, cmd);
        } catch (Exception e) {
            logger.error("广播消息失败: exchange={}, cmd={}, error={}", exchangeName, cmd, e.getMessage());
        }
    }

    /**
     * 订阅队列消息（阻塞式，在新线程中执行）
     * @param queueName 队列名
     * @param handler 消息处理器
     */
    public void subscribe(String queueName, MessageHandler handler) {
        new Thread(() -> {
            try {
                // 每个订阅者使用独立channel
                Channel consumerChannel = connection.createChannel();
                consumerChannel.queueDeclare(queueName, true, false, false, null);

                DeliverCallback deliverCallback = (tag, delivery) -> {
                    String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    try {
                        JSONObject message = JSON.parseObject(body);
                        String cmd = message.getString("cmd");
                        JSONObject data = message.getJSONObject("data");
                        long timestamp = message.getLongValue("timestamp");

                        logger.debug("收到消息: queue={}, cmd={}", queueName, cmd);
                        handler.handle(cmd, data, timestamp);
                    } catch (Exception e) {
                        logger.error("处理消息失败: body={}, error={}", body, e.getMessage());
                    }
                };

                consumerChannel.basicQos(1); // 每次只取一条，多消费者时公平轮询
                consumerChannel.basicConsume(queueName, true, deliverCallback, tag -> {});
                logger.info("开始订阅队列: {}", queueName);

            } catch (Exception e) {
                logger.error("订阅队列失败: queue={}, error={}", queueName, e.getMessage());
            }
        }, "MQ-Consumer-" + queueName).start();
    }

    /**
     * 订阅 Fanout 交换器
     * @param exchangeName 交换器名
     * @param queueName 临时队列名
     * @param handler 消息处理器
     */
    public void subscribeFanout(String exchangeName, String queueName, MessageHandler handler) {
        new Thread(() -> {
            try {
                Channel consumerChannel = connection.createChannel();
                consumerChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, true);
                consumerChannel.queueDeclare(queueName, true, false, false, null);
                consumerChannel.queueBind(queueName, exchangeName, "");

                DeliverCallback deliverCallback = (tag, delivery) -> {
                    String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    try {
                        JSONObject message = JSON.parseObject(body);
                        String cmd = message.getString("cmd");
                        JSONObject data = message.getJSONObject("data");
                        long timestamp = message.getLongValue("timestamp");

                        logger.debug("收到广播: exchange={}, cmd={}", exchangeName, cmd);
                        handler.handle(cmd, data, timestamp);
                    } catch (Exception e) {
                        logger.error("处理广播失败: body={}, error={}", body, e.getMessage());
                    }
                };

                consumerChannel.basicQos(1);
                consumerChannel.basicConsume(queueName, true, deliverCallback, tag -> {});
                logger.info("开始订阅交换器: {}, 队列: {}", exchangeName, queueName);

            } catch (Exception e) {
                logger.error("订阅交换器失败: exchange={}, error={}", exchangeName, e.getMessage());
            }
        }, "MQ-Fanout-" + queueName).start();
    }

    /**
     * 声明知识源输入队列（由各知识源调用，确保队列存在）
     */
    public void declareKnowledgeSourceInput(String queueName) throws IOException {
        declareQueue(queueName);
        logger.debug("知识源输入队列已声明: {}", queueName);
    }

    /**
     * 回复消息给 Controller（发送到 ControllerCmd 队列）
     * Navigator / TargetPlanner 等知识源用此方法回复 Controller
     */
    public void replyToController(String cmd, Object data) {
        publish(Constants.QUEUE_CONTROLLER_CMD, cmd, data);
    }

    /**
     * 关闭连接
     */
    public void close() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
            logger.info("RabbitMQ 连接已关闭");
        } catch (Exception e) {
            logger.error("关闭 RabbitMQ 连接失败: {}", e.getMessage());
        }
    }

    /**
     * 消息处理器接口
     */
    @FunctionalInterface
    public interface MessageHandler {
        void handle(String cmd, JSONObject data, long timestamp);
    }
}
