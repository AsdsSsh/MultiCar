package inspection.taskconfigurator;

import inspection.common.blackboard.BlackboardConfig;
import inspection.common.messaging.MessageConfig;
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

        // 支持命令行参数指定 Redis/MQ 地址
        String redisHost = getArg(args, "--redis-host", "localhost");
        int redisPort = Integer.parseInt(getArg(args, "--redis-port", "6379"));
        String mqHost = getArg(args, "--mq-host", "localhost");
        int mqPort = Integer.parseInt(getArg(args, "--mq-port", "5672"));

        BlackboardConfig bbConfig = new BlackboardConfig(redisHost, redisPort);
        MessageConfig mqConfig = new MessageConfig(mqHost, mqPort);

        TaskConfiguratorAgent agent = new TaskConfiguratorAgent(bbConfig, mqConfig);

        // 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("正在关闭 TaskConfigurator...");
            agent.stop();
        }));

        agent.start();
    }

    private static String getArg(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}
