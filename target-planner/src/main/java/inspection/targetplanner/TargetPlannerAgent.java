package inspection.targetplanner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageType;
import inspection.common.model.Point;
import inspection.common.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * TargetPlanner 知识源 —— 贪心目标分配器。
 *
 * 方案B接口规范：
 *   输入：订阅 TargetPlannerCmd 队列，接收 Controller 的 ASSIGN_TARGET 命令
 *   处理：贪心算法为小车分配未探索目标（距离 ≥ 10 规则）
 *   输出：写入 Redis CarID:Target（JSON，⚠ 不写 Status）
 *        回复 ControllerCmd: TARGET_ASSIGNED(assignedCars)
 *   锁：  无锁/乐观锁（仅写单个 Key，冲突概率极低）
 *
 *   关键约束：仅受 Controller 调度，不接受其他知识源的消息
 */
public class TargetPlannerAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TargetPlannerAgent.class);

    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private volatile boolean running = false;

    /** 已分配的目标缓存（避免同一节拍多车分配到同一目标） */
    private final Set<String> assignedThisBatch = new HashSet<>();

    public TargetPlannerAgent(BlackboardClient blackboard, MessageBus messageBus) {
        this.blackboard = blackboard;
        this.messageBus = messageBus;
    }

    /** 启动 TargetPlanner：订阅 TargetPlannerCmd 队列，等待 Controller 命令 */
    public void start() throws IOException {
        log.info("=== TargetPlanner starting ===");
        messageBus.declareKnowledgeSourceInput(Constants.QUEUE_TARGET_PLANNER_CMD);

        messageBus.subscribe(Constants.QUEUE_TARGET_PLANNER_CMD, (cmd, data, timestamp) -> {
            if (MessageType.ASSIGN_TARGET.equals(cmd)) {
                handleAssignTarget(data);
            } else if (MessageType.RESET_BATCH.equals(cmd)) {
                assignedThisBatch.clear();
                log.debug("[TargetPlanner] Batch reset");
            }
        });

        running = true;
        log.info("TargetPlanner ready — listening on '{}'", Constants.QUEUE_TARGET_PLANNER_CMD);
    }

    /**
     * 处理 ASSIGN_TARGET 命令。
     *
     * 输入格式：
     *   {"cmd":"ASSIGN_TARGET","data":{"carId":"Car001"},"timestamp":...}
     *
     * 贪心分配策略：
     *   1. 扫描全图找出所有未探索且非障碍的候选格子
     *   2. 对每台需要目标的车，找曼哈顿距离最近的目标
     *   3. ⚠ 距离规则：
     *      - 剩余候选 > 1：只分配距离 ≥ 10 的目标
     *      - 剩余候选 = 1：最后一个区域无距离限制
     *      - 无满足条件的 → 暂不分配
     *   4. 标记已分配（避免重复分配）
     *   5. 写入 CarID:Target（⚠ 不写 Status）
     *   6. 通知 Controller TARGET_ASSIGNED
     */
    private void handleAssignTarget(JSONObject data) {
        String carId = data.getString("carId");
        log.info("[TargetPlanner] ASSIGN_TARGET for {}", carId);

        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();

        // 1. 读取黑板数据
        boolean[] mapView = blackboard.getFullMapView(mapWidth, mapHeight);
        boolean[] mapBlock = blackboard.getFullMapBlock(mapWidth, mapHeight);
        Point carPos = blackboard.getCarPosition(carId);

        if (carPos == null) {
            log.warn("[TargetPlanner] CarPosition is null for {}, skip", carId);
            notifyController(Collections.emptyList());
            return;
        }

        // 2. 找出所有候选目标（未探索 && 非障碍 && 未被本批次分配）
        List<Point> candidates = findUnexploredCells(mapView, mapBlock, mapWidth, mapHeight);

        if (candidates.isEmpty()) {
            log.info("[TargetPlanner] No unexplored cells left");
            notifyController(Collections.emptyList());
            return;
        }

        // 3. 贪心分配（含距离规则）
        int remaining = candidates.size();
        Point bestTarget = null;
        int bestDist = Integer.MAX_VALUE;

        for (Point candidate : candidates) {
            String key = candidate.getX() + "," + candidate.getY();
            if (assignedThisBatch.contains(key)) continue;

            int dist = carPos.manhattanDistance(candidate);

            // 距离规则：剩余 > 1 时只分配距离 ≥ 10 的目标
            if (remaining > 1 && dist < Constants.MIN_TARGET_DISTANCE) {
                continue;
            }

            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = candidate;
            }
        }

        if (bestTarget != null) {
            // 4. 写入黑板（⚠ 只写 Target，不写 Status）
            blackboard.setCarTarget(carId, bestTarget);
            String key = bestTarget.getX() + "," + bestTarget.getY();
            assignedThisBatch.add(key);
            log.info("[TargetPlanner] {} assigned target ({},{}) distance={}",
                    carId, bestTarget.getX(), bestTarget.getY(), bestDist);

            // 5. 通知 Controller
            List<Assignment> assignments = new ArrayList<>();
            assignments.add(new Assignment(carId, bestTarget.getX(), bestTarget.getY()));
            notifyController(assignments);
        } else {
            log.info("[TargetPlanner] No suitable target for {} (remaining={})", carId, remaining);
            notifyController(Collections.emptyList());
        }
    }

    /**
     * 扫描全图，找出所有未探索且非障碍的候选格子。
     */
    private List<Point> findUnexploredCells(boolean[] mapView, boolean[] mapBlock,
                                            int mapWidth, int mapHeight) {
        List<Point> cells = new ArrayList<>();
        int total = mapWidth * mapHeight;
        for (int i = 0; i < total; i++) {
            if (!mapView[i] && !mapBlock[i]) {
                int x = i % mapWidth;
                int y = i / mapWidth;
                cells.add(new Point(x, y));
            }
        }
        return cells;
    }

    /**
     * 通知 Controller 目标分配完成。
     *
     * 输出格式：
     *   {"cmd":"TARGET_ASSIGNED","data":{"assignedCars":[{"carId":"Car001","targetX":20,"targetY":15}]},"timestamp":...}
     */
    private void notifyController(List<Assignment> assignments) {
        JSONObject data = new JSONObject();
        data.put("assignedCars", assignments);
        messageBus.replyToController(MessageType.TARGET_ASSIGNED, data);
    }

    /**
     * 重置批次分配缓存（Controller 可在每个 tick 开始前调用 RESET_BATCH）。
     */
    public void resetBatch() {
        assignedThisBatch.clear();
    }

    public boolean isRunning() { return running; }

    @Override
    public void close() {
        running = false;
        log.info("TargetPlannerAgent stopped");
    }

    /** 单次分配结果 */
    public static class Assignment {
        private String carId;
        private int targetX;
        private int targetY;

        public Assignment() {}
        public Assignment(String carId, int targetX, int targetY) {
            this.carId = carId;
            this.targetX = targetX;
            this.targetY = targetY;
        }

        public String getCarId() { return carId; }
        public void setCarId(String carId) { this.carId = carId; }
        public int getTargetX() { return targetX; }
        public void setTargetX(int targetX) { this.targetX = targetX; }
        public int getTargetY() { return targetY; }
        public void setTargetY(int targetY) { this.targetY = targetY; }
    }
}
