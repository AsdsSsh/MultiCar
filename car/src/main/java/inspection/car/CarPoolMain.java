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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CarPool — 单进程承载所有小车。
 * 用法：java inspection.car.CarPoolMain [carCount]
 */
public class CarPoolMain {

    private static final Logger log = LoggerFactory.getLogger(CarPoolMain.class);

    public static void main(String[] args) {
        int carCount = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        log.info("=== CarPool Starting ({} cars) ===", carCount);

        BlackboardConfig bbConfig = ConnectionConfig.loadBlackboardConfig();
        BlackboardClient blackboard = new BlackboardClient(bbConfig);
        DistributedLock distributedLock = new DistributedLock(blackboard.getJedisPool());

        MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();
        MessageBus messageBus = new MessageBus(mqConfig);

        try {
            messageBus.connect();
        } catch (IOException | TimeoutException e) {
            log.error("Failed to connect MQ: {}", e.getMessage());
            System.exit(1);
        }

        ExecutorService executor = Executors.newFixedThreadPool(carCount, r -> {
            Thread t = new Thread(r);
            t.setDaemon(false);
            return t;
        });

        List<CarAgent> cars = new ArrayList<>();

        for (int i = 1; i <= carCount; i++) {
            String carId = String.format("Car%03d", i);
            CarAgent car = new CarAgent(carId, blackboard, messageBus, distributedLock);
            cars.add(car);
            executor.submit(() -> {
                try {
                    log.info("CarPool: starting {}", carId);
                    car.start();
                } catch (Exception e) {
                    log.error("CarPool: {} error: {}", carId, e.getMessage());
                }
            });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("CarPool shutting down...");
            cars.forEach(CarAgent::stop);
            executor.shutdown();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            messageBus.close();
            blackboard.close();
            log.info("CarPool stopped.");
        }));

        log.info("CarPool running with {} cars", carCount);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
