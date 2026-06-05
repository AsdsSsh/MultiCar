package inspection.navigator;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.DistributedLock;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageType;
import inspection.common.model.Point;
import inspection.common.model.RouteAlgorithm;
import inspection.common.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Navigator 知识源 —— 路径规划器。
 *
 * 方案B接口规范：
 *   输入：订阅 NavigatorCmd 队列，接收 Controller 的 PLAN_ROUTE 命令
 *   处理：根据 algorithm 选择 BFS 或 A* 搜索路径
 *   输出：写入 Redis CarID:RouteList（lpush，⚠ 不写 Status）
 *        回复 ControllerCmd: ROUTE_PLANNED(carId, routeFound, routeLength)
 *   锁：  分布式锁 lock:CarID 保护 RouteList 清空+写入复合操作
 *
 *   关键约束：仅受 Controller 调度，不接受其他知识源的消息
 */
public class NavigatorAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NavigatorAgent.class);

    private final BlackboardClient blackboard;
    private final DistributedLock distLock;
    private final MessageBus messageBus;
    private final Map<RouteAlgorithm, PathFinder> pathFinders;
    private volatile boolean running = false;

    public NavigatorAgent(BlackboardClient blackboard, MessageBus messageBus) {
        this.blackboard = blackboard;
        this.distLock = new DistributedLock(blackboard);
        this.messageBus = messageBus;
        this.pathFinders = Map.of(
                RouteAlgorithm.BFS, new BfsPathFinder(),
                RouteAlgorithm.A_STAR, new AStarPathFinder()
        );
    }

    /** 启动 Navigator：订阅 NavigatorCmd 队列，等待 Controller 命令 */
    public void start() throws IOException {
        log.info("=== Navigator starting ===");
        messageBus.declareKnowledgeSourceInput(Constants.QUEUE_NAVIGATOR_CMD);

        messageBus.subscribe(Constants.QUEUE_NAVIGATOR_CMD, (cmd, data, timestamp) -> {
            if (MessageType.PLAN_ROUTE.equals(cmd)) {
                handlePlanRoute(data);
            }
        });

        running = true;
        log.info("Navigator ready — listening on '{}'", Constants.QUEUE_NAVIGATOR_CMD);
    }

    /**
     * 处理 PLAN_ROUTE 命令。
     *
     * 输入格式：
     *   {"cmd":"PLAN_ROUTE","data":{"carId":"Car001","algorithm":"BFS"},"timestamp":...}
     *
     * 处理流程：
     *   1. 加锁 lock:CarID
     *   2. 从黑板读取 CarID:Position, CarID:Target, mapBlock, TaskConfig.algorithm
     *   3. 根据 algorithm 选择 PathFinder
     *   4. 执行路径搜索
     *   5. 路径找到 → 写入黑板 RouteList（⚠ 不写 Status）
     *   6. 路径未找到 → 不写任何状态
     *   7. 释放锁
     *   8. 通知 Controller ROUTE_PLANNED
     */
    private void handlePlanRoute(JSONObject data) {
        String carId = data.getString("carId");
        String algoStr = data.getString("algorithm");
        if (algoStr == null) algoStr = "BFS";
        RouteAlgorithm algorithm = RouteAlgorithm.valueOf(algoStr);

        log.info("[Navigator] PLAN_ROUTE for {}, algorithm={}", carId, algorithm);

        // 加锁 → 读取黑板 → 搜索 → 写入 RouteList → 通知 Controller
        boolean locked = distLock.executeWithCarLock(carId, () -> {
            planRouteForCar(carId, algorithm);
        });

        if (!locked) {
            log.warn("[Navigator] Failed to acquire lock for {}, skip", carId);
            notifyController(carId, false, 0);
        }
    }

    /**
     * 为指定小车执行路径规划（在分布式锁保护下调用）。
     */
    private void planRouteForCar(String carId, RouteAlgorithm algorithm) {
        // 1. 从黑板读取数据
        Point start = blackboard.getCarPosition(carId);
        Point target = blackboard.getCarTarget(carId);
        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();
        boolean[] mapBlock = blackboard.getFullMapBlock(mapWidth, mapHeight);

        // 2. 参数校验
        if (start == null) {
            log.warn("[Navigator] CarPosition is null for {}, skip", carId);
            notifyController(carId, false, 0);
            return;
        }
        if (target == null) {
            log.warn("[Navigator] CarTarget is null for {}, skip", carId);
            notifyController(carId, false, 0);
            return;
        }

        // 3. 将其他小车位置作为临时障碍加入（避免多车碰撞）
        addOtherCarsAsObstacles(mapBlock, carId, mapWidth, mapHeight);

        // 4. 选择算法并搜索
        PathFinder finder = pathFinders.getOrDefault(algorithm, pathFinders.get(RouteAlgorithm.BFS));
        List<Point> route = finder.findPath(start, target, mapWidth, mapHeight, mapBlock);

        // 5. 写入黑板（⚠ 只写 RouteList，不写 Status）
        if (!route.isEmpty()) {
            blackboard.setCarRouteList(carId, route);
            log.info("[Navigator] {} route planned: {} steps", carId, route.size());
            notifyController(carId, true, route.size());
        } else {
            // 路径未找到 —— 不写任何状态
            log.warn("[Navigator] {} no route found from {} to {}", carId, start, target);
            notifyController(carId, false, 0);
        }
    }

    /**
     * 将其他小车的当前位置加入障碍数组（作为临时障碍）
     */
    private void addOtherCarsAsObstacles(boolean[] mapBlock, String currentCarId, int mapWidth, int mapHeight) {
        int carCount = blackboard.getCarCount();
        for (int i = 1; i <= carCount; i++) {
            String otherCarId = String.format("Car%03d", i);
            if (otherCarId.equals(currentCarId)) continue;
            Point pos = blackboard.getCarPosition(otherCarId);
            if (pos != null) {
                int offset = pos.toBitmapOffset(mapWidth);
                if (offset >= 0 && offset < mapBlock.length) {
                    mapBlock[offset] = true;
                }
            }
        }
    }

    /**
     * 通知 Controller 路径规划完成。
     *
     * 输出格式：
     *   {"cmd":"ROUTE_PLANNED","data":{"carId":"Car001","routeFound":true,"routeLength":15},"timestamp":...}
     */
    private void notifyController(String carId, boolean routeFound, int routeLength) {
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        data.put("routeFound", routeFound);
        data.put("routeLength", routeLength);
        messageBus.replyToController(MessageType.ROUTE_PLANNED, data);
    }

    public boolean isRunning() { return running; }

    @Override
    public void close() {
        running = false;
        distLock.close();
        log.info("NavigatorAgent stopped");
    }
}
