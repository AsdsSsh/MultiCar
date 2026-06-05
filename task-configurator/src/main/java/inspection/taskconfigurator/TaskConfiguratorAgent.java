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

/**
 * 任务配置器（TaskConfigurator）
 * 职责：
 * 1. 接收 Controller 转发的 FORWARD_CONFIG / FORWARD_RESET 命令
 * 2. 初始化/重置黑板数据（地图、障碍物、小车位置、任务配置）
 * 3. 初始化完成后通知 Controller "TASK_READY"
 *
 * 仅受 Controller 调度，不与其他知识源直接通信
 */
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

    public TaskConfiguratorAgent() {
        this(new BlackboardConfig(), new MessageConfig());
    }

    /**
     * 启动 TaskConfigurator
     * 1. 连接 RabbitMQ
     * 2. 订阅 TaskConfigCmd 队列
     * 3. 等待 Controller 命令
     */
    public void start() {
        try {
            messageBus.connect();
            running = true;
            logger.info("TaskConfigurator 已启动");

            // 自动初始化默认仿真数据
            logger.info("自动初始化默认仿真...");
            autoInit();

            // 订阅 TaskConfigCmd 队列
            messageBus.subscribe(Constants.QUEUE_TASK_CONFIG_CMD, this::handleMessage);

            // 主线程保持运行
            while (running) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            logger.error("TaskConfigurator 运行异常", e);
        }
    }

    /** 自动初始化默认仿真 */
    private void autoInit() {
        try {
            int mapWidth = Constants.DEFAULT_MAP_WIDTH;
            int mapHeight = Constants.DEFAULT_MAP_HEIGHT;
            int carCount = Constants.DEFAULT_CAR_COUNT;
            double obstacleDensity = Constants.DEFAULT_OBSTACLE_DENSITY;
            String algorithm = Constants.DEFAULT_ALGORITHM;

            logger.info("自动初始化: map={}x{}, cars={}, density={}, algorithm={}",
                    mapWidth, mapHeight, carCount, obstacleDensity, algorithm);

            blackboard.clearAll();

            Map<String, String> config = new HashMap<>();
            config.put("mapWidth", String.valueOf(mapWidth));
            config.put("mapHeight", String.valueOf(mapHeight));
            config.put("carCount", String.valueOf(carCount));
            config.put("obstacleDensity", String.valueOf(obstacleDensity));
            config.put("algorithm", algorithm);
            config.put("taskActive", "true");
            config.put("tickInterval", String.valueOf(Constants.DEFAULT_TICK_INTERVAL_MS));
            blackboard.setTaskConfig(config);

            initializeObstacles(mapWidth, mapHeight, obstacleDensity, carCount);
            initializeCars(carCount, mapWidth, mapHeight);

            messageBus.declareAllSystemQueues(carCount);

            JSONObject notifyData = new JSONObject();
            notifyData.put("carCount", carCount);
            notifyData.put("mapWidth", mapWidth);
            notifyData.put("mapHeight", mapHeight);
            messageBus.publish(Constants.QUEUE_CONTROLLER_CMD, "TASK_READY", notifyData);
            logger.info("自动初始化完成，已通知 Controller");
        } catch (Exception e) {
            logger.error("自动初始化失败", e);
        }
    }

    /**
     * 处理收到的消息
     */
    private void handleMessage(String cmd, JSONObject data, long timestamp) {
        logger.info("收到命令: cmd={}", cmd);
        switch (cmd) {
            case "FORWARD_CONFIG" -> handleSetConfig(data);
            case "FORWARD_RESET" -> handleReset();
            default -> logger.warn("未知命令: {}", cmd);
        }
    }

    /**
     * 处理配置命令（初始化仿真）
     */
    private void handleSetConfig(JSONObject data) {
        try {
            int mapWidth = data.getIntValue("mapWidth", Constants.DEFAULT_MAP_WIDTH);
            int mapHeight = data.getIntValue("mapHeight", Constants.DEFAULT_MAP_HEIGHT);
            int carCount = data.getIntValue("carCount", Constants.DEFAULT_CAR_COUNT);
            double obstacleDensity = data.getDoubleValue("obstacleDensity");
            if (obstacleDensity == 0.0) obstacleDensity = Constants.DEFAULT_OBSTACLE_DENSITY;
            String algorithm = data.getString("algorithm");
            if (algorithm == null) algorithm = Constants.DEFAULT_ALGORITHM;

            logger.info("开始初始化仿真: map={}x{}, cars={}, density={}, algorithm={}",
                    mapWidth, mapHeight, carCount, obstacleDensity, algorithm);

            // 1. 清空黑板所有数据
            blackboard.clearAll();
            logger.info("黑板数据已清空");

            // 2. 写入 TaskConfig 配置
            Map<String, String> config = new HashMap<>();
            config.put("mapWidth", String.valueOf(mapWidth));
            config.put("mapHeight", String.valueOf(mapHeight));
            config.put("carCount", String.valueOf(carCount));
            config.put("obstacleDensity", String.valueOf(obstacleDensity));
            config.put("algorithm", algorithm);
            config.put("taskActive", "true");
            config.put("tickInterval", String.valueOf(Constants.DEFAULT_TICK_INTERVAL_MS));
            blackboard.setTaskConfig(config);
            logger.info("TaskConfig 已写入");

            // 3. 初始化障碍物
            initializeObstacles(mapWidth, mapHeight, obstacleDensity, carCount);
            logger.info("障碍物初始化完成");

            // 4. 初始化小车
            List<String> carIds = initializeCars(carCount, mapWidth, mapHeight);
            logger.info("小车初始化完成: {}", carIds);

            // 5. 声明所有系统队列
            messageBus.declareAllSystemQueues(carCount);
            logger.info("系统队列声明完成");

            // 6. 通知 Controller "TASK_READY"
            JSONObject notifyData = new JSONObject();
            notifyData.put("carCount", carCount);
            notifyData.put("mapWidth", mapWidth);
            notifyData.put("mapHeight", mapHeight);
            messageBus.publish(Constants.QUEUE_CONTROLLER_CMD, "TASK_READY", notifyData);
            logger.info("已通知 Controller: TASK_READY");

        } catch (Exception e) {
            logger.error("初始化仿真失败", e);
        }
    }

    /**
     * 处理重置命令
     */
    private void handleReset() {
        logger.info("收到重置命令");
        // 重置就是重新初始化，读取当前配置
        Map<String, String> config = blackboard.getTaskConfig();
        JSONObject data = new JSONObject();
        data.put("mapWidth", Integer.parseInt(config.getOrDefault("mapWidth", "30")));
        data.put("mapHeight", Integer.parseInt(config.getOrDefault("mapHeight", "30")));
        data.put("carCount", Integer.parseInt(config.getOrDefault("carCount", "5")));
        data.put("obstacleDensity", Double.parseDouble(config.getOrDefault("obstacleDensity", "0.1")));
        data.put("algorithm", config.getOrDefault("algorithm", "BFS"));
        handleSetConfig(data);
    }

    /**
     * 初始化障碍物
     * 随机生成障碍物，避开小车初始位置
     */
    private void initializeObstacles(int mapWidth, int mapHeight, double density, int carCount) {
        // 获取小车初始位置（这些位置不能放障碍物）
        Set<Point> protectedPositions = new HashSet<>();
        for (int i = 0; i < carCount; i++) {
            protectedPositions.add(Constants.getCarInitialPosition(i, mapWidth, mapHeight));
            // 保护小车周围3x3区域
            Point center = Constants.getCarInitialPosition(i, mapWidth, mapHeight);
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

        // 随机生成障碍物
        List<Point> obstacles = new ArrayList<>();
        int totalCells = mapWidth * mapHeight;
        int obstacleCount = (int) (totalCells * density);

        while (obstacles.size() < obstacleCount) {
            int x = random.nextInt(mapWidth);
            int y = random.nextInt(mapHeight);
            Point p = new Point(x, y);
            if (!protectedPositions.contains(p)) {
                obstacles.add(p);
                protectedPositions.add(p); // 避免重复
            }
        }

        // 批量写入障碍物
        blackboard.setMapBlockBitsBatch(obstacles, mapWidth);
        logger.info("生成障碍物 {} 个", obstacles.size());
    }

    /**
     * 初始化小车
     * 设置位置、状态、步数，标记动态障碍，点亮3x3视野
     */
    private List<String> initializeCars(int carCount, int mapWidth, int mapHeight) {
        List<String> carIds = Constants.generateCarIds(carCount);
        List<Point> illuminatedCells = new ArrayList<>();

        for (int i = 0; i < carCount; i++) {
            String carId = carIds.get(i);
            Point initialPos = Constants.getCarInitialPosition(i, mapWidth, mapHeight);

            // 设置位置
            blackboard.setCarPosition(carId, initialPos);

            // 设置状态为 IDLE
            blackboard.setCarStatus(carId, CarStatus.IDLE);

            // 设置步数为 0
            blackboard.setCarSteps(carId, 0);

            // 收集需要点亮的3x3区域格子
            for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                    int nx = initialPos.getX() + dx;
                    int ny = initialPos.getY() + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        illuminatedCells.add(new Point(nx, ny));
                    }
                }
            }
        }

        // 批量点亮所有小车的初始视野
        blackboard.setMapViewBitsBatch(illuminatedCells, mapWidth);
        logger.info("点亮初始视野 {} 个格子", illuminatedCells.size());

        return carIds;
    }

    public void stop() {
        running = false;
        messageBus.close();
        blackboard.close();
        logger.info("TaskConfigurator 已停止");
    }

    public static void main(String[] args) {
        TaskConfiguratorAgent agent = new TaskConfiguratorAgent();
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
        agent.start();
    }
}
