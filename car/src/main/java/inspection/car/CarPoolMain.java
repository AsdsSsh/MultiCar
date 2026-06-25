package inspection.car;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.BlackboardConfig;
import inspection.common.blackboard.DistributedLock;
import inspection.common.config.ConnectionConfig;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageConfig;
import inspection.common.service_discovery.HeartbeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static inspection.common.util.Constants.*;

/**
 * CarPool — 无状态模式，每次 TICK_MOVE 创建临时 CarAgent 处理。
 * 支持多实例部署：多个 CarPool 订阅同一队列，MQ 轮询分发。
 * 用法：java inspection.car.CarPoolMain
 */
public class CarPoolMain {

    private static final Logger log = LoggerFactory.getLogger(CarPoolMain.class);

    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private final DistributedLock distributedLock;
    private final BlackboardConfig bbConfig;

    public CarPoolMain() {
        this.bbConfig = ConnectionConfig.loadBlackboardConfig();
        this.blackboard = new BlackboardClient(bbConfig);
        this.distributedLock = new DistributedLock(blackboard.getJedisPool());

        MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();
        this.messageBus = new MessageBus(mqConfig);
    }

    public void start() throws IOException, TimeoutException {
        messageBus.connect();
        log.info("CarPool connected to MQ");

        HeartbeatService heartbeat = new HeartbeatService(bbConfig, "carpool");
        heartbeat.start();

        messageBus.subscribe(QUEUE_CAR_POOL, this::handleMessage);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("CarPool shutting down...");
            heartbeat.close();
            messageBus.close();
            blackboard.close();
            log.info("CarPool stopped.");
        }));

        log.info("CarPool ready (stateless), listening on '{}'", QUEUE_CAR_POOL);

        while (true) {
            try { Thread.sleep(60000); } catch (InterruptedException e) { break; }
        }
    }

    private void handleMessage(String cmd, JSONObject data, long timestamp) {
        if (!CMD_TICK_MOVE.equals(cmd)) return;

        String carId = data.getString("carId");
        if (carId == null) return;

        new CarAgent(carId, blackboard, messageBus, distributedLock)
                .handleMessage(cmd, data, timestamp);
    }

    public static void main(String[] args) {
        try {
            new CarPoolMain().start();
        } catch (Exception e) {
            log.error("CarPool failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
