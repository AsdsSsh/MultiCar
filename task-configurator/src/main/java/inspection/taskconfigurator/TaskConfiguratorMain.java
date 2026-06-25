package inspection.taskconfigurator;

import inspection.common.blackboard.BlackboardConfig;
import inspection.common.config.ConnectionConfig;
import inspection.common.messaging.MessageConfig;
import inspection.common.service_discovery.HeartbeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TaskConfigurator 独立启动入口
 * 用法: java inspection.taskconfigurator.TaskConfiguratorMain
 */
public class TaskConfiguratorMain {
    private static final Logger logger = LoggerFactory.getLogger(TaskConfiguratorMain.class);

    public static void main(String[] args) {
        logger.info("启动 TaskConfigurator...");

        // 统一配置：环境变量 > 系统属性 > config.properties
        BlackboardConfig bbConfig = ConnectionConfig.loadBlackboardConfig();
        MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();

        HeartbeatService heartbeat = new HeartbeatService(bbConfig, "taskconfig");
        heartbeat.start();

        TaskConfiguratorAgent agent = new TaskConfiguratorAgent(bbConfig, mqConfig);

        // 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("正在关闭 TaskConfigurator...");
            agent.stop();
            heartbeat.close();
        }));

        agent.start();
    }
}
