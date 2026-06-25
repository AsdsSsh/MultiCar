package inspection.taskconfigurator;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.BlackboardConfig;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageConfig;
import inspection.common.model.CarStatus;
import inspection.common.model.Point;
import inspection.common.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TaskConfiguratorAgent {
    private static final Logger logger = LoggerFactory.getLogger(TaskConfiguratorAgent.class);

    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private final Random random = new Random();

    private boolean running = false;

    public TaskConfiguratorAgent(BlackboardConfig bbConfig, MessageConfig mqConfig) {
        this.blackboard = new BlackboardClient(bbConfig);
        this.messageBus = new MessageBus(mqConfig);
    }

    public void start() {
        try {
            messageBus.connect();
            running = true;
            logger.info("TaskConfigurator 已启动，等待命令...");

            messageBus.subscribe(Constants.QUEUE_TASK_CONFIG_CMD, this::handleMessage);

            while (running) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            logger.error("TaskConfigurator 运行异常", e);
        }
    }

    private void handleMessage(String cmd, JSONObject data, long timestamp) {
        logger.info("收到命令: cmd={}", cmd);
        switch (cmd) {
            case "FORWARD_CONFIG" -> handleSetConfig(data);
            case "FORWARD_RESET" -> handleReset(data);
            default -> logger.warn("未知命令: {}", cmd);
        }
    }

    private void handleSetConfig(JSONObject data) {
        try {
            String sessionId = data != null ? data.getString("sessionId") : null;
            if (sessionId == null || sessionId.isEmpty()) {
                logger.error("缺少 sessionId");
                return;
            }

            int mapWidth = data.getIntValue("mapWidth", Constants.DEFAULT_MAP_WIDTH);
            int mapHeight = data.getIntValue("mapHeight", Constants.DEFAULT_MAP_HEIGHT);
            int carCount = data.getIntValue("carCount", Constants.DEFAULT_CAR_COUNT);
            double obstacleDensity = data.getDoubleValue("obstacleDensity");
            if (obstacleDensity == 0.0) obstacleDensity = Constants.DEFAULT_OBSTACLE_DENSITY;
            String algorithm = data.getString("algorithm");
            if (algorithm == null) algorithm = Constants.DEFAULT_ALGORITHM;

            logger.info("Session {} 初始化: map={}x{}, cars={}, density={}, algorithm={}",
                    sessionId, mapWidth, mapHeight, carCount, obstacleDensity, algorithm);

            // 1. 写入 TaskConfig
            Map<String, String> config = new HashMap<>();
            config.put("mapWidth", String.valueOf(mapWidth));
            config.put("mapHeight", String.valueOf(mapHeight));
            config.put("carCount", String.valueOf(carCount));
            config.put("obstacleDensity", String.valueOf(obstacleDensity));
            config.put("algorithm", algorithm);
            config.put("taskActive", "true");
            config.put("tickInterval", String.valueOf(Constants.DEFAULT_TICK_INTERVAL_MS));
            blackboard.setTaskConfig(sessionId, config);

            // 2. 初始化障碍物
            boolean hasCustomObstacles = data.containsKey("customObstacles") || data.containsKey("obstacles");
            if (hasCustomObstacles) {
                List<JSONObject> obstacles = data.getList(
                        data.containsKey("customObstacles") ? "customObstacles" : "obstacles",
                        JSONObject.class);
                if (obstacles != null && !obstacles.isEmpty()) {
                    List<Point> pts = new ArrayList<>();
                    for (JSONObject obj : obstacles) {
                        int x = obj.getIntValue("x", -1);
                        int y = obj.getIntValue("y", -1);
                        if (x >= 0 && x < mapWidth && y >= 0 && y < mapHeight) {
                            pts.add(new Point(x, y));
                        }
                    }
                    blackboard.setMapBlockBitsBatch(sessionId, pts, mapWidth);
                    logger.info("Session {} 自定义障碍物 {} 个", sessionId, pts.size());
                } else {
                    initializeObstacles(sessionId, mapWidth, mapHeight, obstacleDensity, carCount);
                }
            } else {
                initializeObstacles(sessionId, mapWidth, mapHeight, obstacleDensity, carCount);
            }

            // 3. 初始化小车
            boolean hasCarPositions = data.containsKey("carPositions");
            if (hasCarPositions) {
                List<JSONObject> carPositions = data.getList("carPositions", JSONObject.class);
                if (carPositions != null && !carPositions.isEmpty()) {
                    initializeCarsWithPositions(sessionId, carPositions, mapWidth, mapHeight);
                } else {
                    initializeCars(sessionId, carCount, mapWidth, mapHeight);
                }
            } else {
                initializeCars(sessionId, carCount, mapWidth, mapHeight);
            }

            // 4. 通知 Controller
            JSONObject notify = new JSONObject();
            notify.put("sessionId", sessionId);
            notify.put("carCount", carCount);
            notify.put("mapWidth", mapWidth);
            notify.put("mapHeight", mapHeight);
            messageBus.publish(inspection.common.util.Constants.getControllerCmdQueue(sessionId), "TASK_READY", notify);
            logger.info("Session {} 初始化完成，已通知 Controller", sessionId);

        } catch (Exception e) {
            logger.error("Session 初始化失败", e);
        }
    }

    private void handleReset(JSONObject data) {
        if (data != null && data.containsKey("sessionId")) {
            String sessionId = data.getString("sessionId");
            blackboard.clearSession(sessionId);
            logger.info("Session {} 已重置", sessionId);
            // 重新初始化
            handleSetConfig(data);
        }
    }

    // ==================== 障碍物 ====================

    private void initializeObstacles(String sessionId, int mapWidth, int mapHeight, double density, int carCount) {
        Set<Point> protectedPositions = new HashSet<>();
        for (int i = 0; i < carCount; i++) {
            Point center = Constants.getCarInitialPosition(i, mapWidth, mapHeight);
            protectedPositions.add(center);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = center.getX() + dx;
                    int ny = center.getY() + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        protectedPositions.add(new Point(nx, ny));
                    }
                }
            }
        }

        List<Point> obstacles = new ArrayList<>();
        int obstacleCount = (int) (mapWidth * mapHeight * density);
        while (obstacles.size() < obstacleCount) {
            Point p = new Point(random.nextInt(mapWidth), random.nextInt(mapHeight));
            if (!protectedPositions.contains(p)) {
                obstacles.add(p);
                protectedPositions.add(p);
            }
        }
        blackboard.setMapBlockBitsBatch(sessionId, obstacles, mapWidth);
        logger.info("Session {} 生成障碍物 {} 个", sessionId, obstacles.size());
    }

    // ==================== 小车初始化 ====================

    private List<String> initializeCars(String sessionId, int carCount, int mapWidth, int mapHeight) {
        List<String> carIds = Constants.generateCarIds(carCount);
        List<Point> illuminated = new ArrayList<>();

        for (int i = 0; i < carCount; i++) {
            String carId = carIds.get(i);
            Point pos = Constants.getCarInitialPosition(i, mapWidth, mapHeight);
            blackboard.setCarPosition(sessionId, carId, pos);
            blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
            blackboard.setCarSteps(sessionId, carId, 0);

            for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                    int nx = pos.getX() + dx;
                    int ny = pos.getY() + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        illuminated.add(new Point(nx, ny));
                    }
                }
            }
        }

        blackboard.setMapViewBitsBatch(sessionId, illuminated, mapWidth);
        return carIds;
    }

    private void initializeCarsWithPositions(String sessionId, List<JSONObject> carPositions, int mapWidth, int mapHeight) {
        List<Point> illuminated = new ArrayList<>();

        for (int i = 0; i < carPositions.size(); i++) {
            JSONObject obj = carPositions.get(i);
            String carId = obj.getString("carId");
            if (carId == null) carId = String.format("Car%03d", i + 1);
            int x = Math.max(0, Math.min(obj.getIntValue("x", 0), mapWidth - 1));
            int y = Math.max(0, Math.min(obj.getIntValue("y", 0), mapHeight - 1));
            Point pos = new Point(x, y);

            blackboard.setCarPosition(sessionId, carId, pos);
            blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
            blackboard.setCarSteps(sessionId, carId, 0);

            for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        illuminated.add(new Point(nx, ny));
                    }
                }
            }
        }
        blackboard.setMapViewBitsBatch(sessionId, illuminated, mapWidth);
    }

    public void stop() {
        running = false;
        messageBus.close();
        blackboard.close();
    }
}
