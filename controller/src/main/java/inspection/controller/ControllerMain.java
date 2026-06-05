package inspection.controller;

import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.BlackboardConfig;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Controller 独立进程入口
 *
 * 用法：
 *   java -cp common.jar;controller.jar inspection.controller.ControllerMain
 */
public class ControllerMain {

    private static final Logger log = LoggerFactory.getLogger(ControllerMain.class);

    public static void main(String[] args) {
        log.info("=== Controller Starting ===");

        // 1. 初始化黑板连接
        BlackboardConfig bbConfig = new BlackboardConfig();
        // 可通过环境变量/系统属性覆盖默认配置
        bbConfig.setHost(System.getProperty("redis.host", "localhost"));
        bbConfig.setPort(Integer.getInteger("redis.port", 6379));

        BlackboardClient blackboard = new BlackboardClient(bbConfig);

        // 2. 初始化消息总线
        MessageConfig mqConfig = new MessageConfig();
        mqConfig.setHost(System.getProperty("rabbitmq.host", "localhost"));
        mqConfig.setPort(Integer.getInteger("rabbitmq.port", 5672));

        MessageBus messageBus = new MessageBus(mqConfig);

        try {
            messageBus.connect();

            // 3. 声明队列（如果尚未声明）
            int carCount = blackboard.getCarCount();
            if (carCount == 0) carCount = 5; // 默认
            messageBus.declareAllSystemQueues(carCount);

            // 4. 创建并启动Controller
            ControllerAgent controller = new ControllerAgent(blackboard, messageBus);
            controller.start();

            // 5. 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Controller...");
                controller.stop();
                messageBus.close();
                blackboard.close();
                log.info("Controller stopped.");
            }));

            log.info("Controller running. Press Ctrl+C to stop.");

            // 保持主线程存活
            Thread.currentThread().join();

        } catch (IOException | TimeoutException e) {
            log.error("Failed to start Controller: {}", e.getMessage(), e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Controller interrupted.");
        }
    }
}
