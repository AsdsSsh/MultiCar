package inspection.car;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.BlackboardConfig;
import inspection.common.blackboard.DistributedLock;
import inspection.common.config.ConnectionConfig;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static inspection.common.util.Constants.*;

/**
 * CarPool — 单队列 + 懒加载，按需创建 CarAgent。
 * 用法：java inspection.car.CarPoolMain
 */
public class CarPoolMain {

    private static final Logger log = LoggerFactory.getLogger(CarPoolMain.class);

    private final Map<String, CarAgent> agents = new ConcurrentHashMap<>();
    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private final DistributedLock distributedLock;

    public CarPoolMain() {
        BlackboardConfig bbConfig = ConnectionConfig.loadBlackboardConfig();
        this.blackboard = new BlackboardClient(bbConfig);
        this.distributedLock = new DistributedLock(blackboard.getJedisPool());

        MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();
        this.messageBus = new MessageBus(mqConfig);
    }

    public void start() throws IOException, TimeoutException {
        messageBus.connect();
        log.info("CarPool connected to MQ");

        messageBus.subscribe(QUEUE_CAR_POOL, this::handleMessage);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("CarPool shutting down ({} agents)...", agents.size());
            agents.values().forEach(CarAgent::stop);
            messageBus.close();
            blackboard.close();
            log.info("CarPool stopped.");
        }));

        log.info("CarPool ready, listening on '{}' (lazy mode)", QUEUE_CAR_POOL);

        while (true) {
            try { Thread.sleep(60000); } catch (InterruptedException e) { break; }
        }
    }

    private void handleMessage(String cmd, JSONObject data, long timestamp) {
        if (!CMD_TICK_MOVE.equals(cmd)) return;

        String carId = data.getString("carId");
        if (carId == null) return;

        CarAgent agent = agents.computeIfAbsent(carId, id -> {
            CarAgent a = new CarAgent(id, blackboard, messageBus, distributedLock);
            a.start();
            log.info("CarPool: lazily created {}", id);
            return a;
        });

        // 直接调 handleMessage，carId 已在 data 中
        agent.handleMessage(cmd, data, timestamp);
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
