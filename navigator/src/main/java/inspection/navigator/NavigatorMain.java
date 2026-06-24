package inspection.navigator;

import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.BlackboardConfig;
import inspection.common.config.ConnectionConfig;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Navigator 独立进程入口。
 *
 * 启动方式：
 *   java -cp blackbox-ai-common.jar;navigator.jar inspection.navigator.NavigatorMain
 */
public class NavigatorMain {

    private static final Logger log = LoggerFactory.getLogger(NavigatorMain.class);

    public static void main(String[] args) {
        try {
            log.info("═════════════════════════════════════");
            log.info("  Navigator — 路径规划知识源");
            log.info("  算法层 | 成员C");
            log.info("═════════════════════════════════════");

            // 1. 连接 Redis 黑板（统一配置：环境变量 > 系统属性 > config.properties）
            BlackboardConfig bbConfig = ConnectionConfig.loadBlackboardConfig();
            final BlackboardClient blackboard = new BlackboardClient(bbConfig);
            log.info("Connected to Redis Blackboard");

            // 2. 连接 RabbitMQ 消息总线
            MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();
            final MessageBus messageBus = new MessageBus(mqConfig);
            messageBus.connect();
            log.info("Connected to RabbitMQ");

            // 3. 启动 NavigatorAgent
            final NavigatorAgent agent = new NavigatorAgent(blackboard, messageBus);
            agent.start();

            // 4. 保持进程运行
            log.info("Navigator running... Press Ctrl+C to stop");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Navigator...");
                agent.close();
                messageBus.close();
                blackboard.close();
            }));

            while (agent.isRunning()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            log.error("Navigator terminated with error: {}", e.getMessage(), e);
        }
    }
}
