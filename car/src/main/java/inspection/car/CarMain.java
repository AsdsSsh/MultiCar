package inspection.car;

import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.BlackboardConfig;
import inspection.common.blackboard.DistributedLock;
import inspection.common.config.ConnectionConfig;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Car 独立进程入口
 *
 * 每台小车作为独立进程运行，通过命令行参数指定carId。
 *
 * 用法：
 *   java -cp common.jar;car.jar inspection.car.CarMain Car001
 *   java -cp common.jar;car.jar inspection.car.CarMain Car002
 *   ...
 */
public class CarMain {

    private static final Logger log = LoggerFactory.getLogger(CarMain.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            log.error("Usage: CarMain <carId>");
            log.error("Example: CarMain Car001");
            System.exit(1);
        }

        String carId = args[0];
        log.info("=== Car {} Starting ===", carId);

        // 1. 初始化黑板连接（统一配置：环境变量 > 系统属性 > config.properties）
        BlackboardConfig bbConfig = ConnectionConfig.loadBlackboardConfig();
        BlackboardClient blackboard = new BlackboardClient(bbConfig);
        DistributedLock distributedLock = new DistributedLock(blackboard.getJedisPool());

        // 2. 初始化消息总线
        MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();
        MessageBus messageBus = new MessageBus(mqConfig);

        try {
            messageBus.connect();

            // 声明Car专属队列
            messageBus.declareQueue(inspection.common.util.Constants.getCarQueueName(carId));

            // 3. 创建并启动Car
            CarAgent car = new CarAgent(carId, blackboard, messageBus, distributedLock);
            car.start();

            // 4. 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Car {}...", carId);
                car.stop();
                messageBus.close();
                blackboard.close();
                log.info("Car {} stopped.", carId);
            }));

            log.info("Car {} running. Press Ctrl+C to stop.", carId);

            // 保持主线程存活
            Thread.currentThread().join();

        } catch (IOException | TimeoutException e) {
            log.error("Failed to start Car {}: {}", carId, e.getMessage(), e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Car {} interrupted.", carId);
        }
    }
}
