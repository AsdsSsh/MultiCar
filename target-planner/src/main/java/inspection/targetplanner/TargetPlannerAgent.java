package inspection.targetplanner;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.messaging.MessageBus;
import inspection.common.messaging.MessageType;
import inspection.common.model.Point;
import inspection.common.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.util.*;

public class TargetPlannerAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TargetPlannerAgent.class);
    private static final int CLAIM_TTL_SEC = 30;

    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private volatile boolean running = false;

    /** 当前批次的 tick，用于构造 claim key 前缀 */
    private volatile long currentTick = 0;

    public TargetPlannerAgent(BlackboardClient blackboard, MessageBus messageBus) {
        this.blackboard = blackboard;
        this.messageBus = messageBus;
    }

    public void start() throws IOException {
        log.info("=== TargetPlanner starting (multi-instance, Redis claims) ===");
        messageBus.declareKnowledgeSourceInput(Constants.QUEUE_TARGET_PLANNER_CMD);

        messageBus.subscribe(Constants.QUEUE_TARGET_PLANNER_CMD, (cmd, data, timestamp) -> {
            if (MessageType.ASSIGN_TARGET.equals(cmd)) {
                handleAssignTarget(data);
            } else if (MessageType.RESET_BATCH.equals(cmd)) {
                if (data != null) {
                    currentTick = data.getLongValue("tick", 0);
                }
            }
        });

        running = true;
        log.info("TargetPlanner ready");
    }

    private void handleAssignTarget(JSONObject data) {
        String sessionId = data.getString("sessionId");
        String carId = data.getString("carId");

        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("[TargetPlanner] Missing sessionId, skip");
            notifyController(sessionId, Collections.emptyList());
            return;
        }

        log.info("[TargetPlanner] ASSIGN_TARGET session={} car={}", sessionId, carId);

        int mapWidth = blackboard.getMapWidth(sessionId);
        int mapHeight = blackboard.getMapHeight(sessionId);

        boolean[] mapView = blackboard.getFullMapView(sessionId, mapWidth, mapHeight);
        boolean[] mapBlock = blackboard.getFullMapBlock(sessionId, mapWidth, mapHeight);
        Point carPos = blackboard.getCarPosition(sessionId, carId);

        if (carPos == null) {
            notifyController(sessionId, Collections.emptyList());
            return;
        }

        List<Point> candidates = findUnexploredCells(mapView, mapBlock, mapWidth, mapHeight);
        if (candidates.isEmpty()) {
            log.info("[TargetPlanner] Session {} no unexplored cells", sessionId);
            notifyController(sessionId, Collections.emptyList());
            return;
        }

        int remaining = candidates.size();
        Point bestTarget = null;
        int bestDist = Integer.MAX_VALUE;

        // claim key 前缀：targetClaim:{sessionId}:{tick}:
        String claimPrefix = "targetClaim:" + sessionId + ":" + currentTick + ":";

        for (Point candidate : candidates) {
            String claimKey = claimPrefix + candidate.getX() + "," + candidate.getY();

            // 用 Redis SET NX 原子抢占，成功则获得分配权
            if (!tryClaim(claimKey, carId)) continue;

            int dist = carPos.manhattanDistance(candidate);
            if (remaining > 1 && dist < Constants.MIN_TARGET_DISTANCE) continue;

            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = candidate;
            }
        }

        if (bestTarget != null) {
            blackboard.setCarTarget(sessionId, carId, bestTarget);
            log.info("[TargetPlanner] {}:{} → target ({},{}) distance={}",
                    sessionId, carId, bestTarget.getX(), bestTarget.getY(), bestDist);

            List<Assignment> assignments = new ArrayList<>();
            assignments.add(new Assignment(carId, bestTarget.getX(), bestTarget.getY()));
            notifyController(sessionId, assignments);
        } else {
            notifyController(sessionId, Collections.emptyList());
        }
    }

    private boolean tryClaim(String key, String carId) {
        try (Jedis jedis = blackboard.getJedisPool().getResource()) {
            return "OK".equals(jedis.set(key, carId, SetParams.setParams().nx().ex(CLAIM_TTL_SEC)));
        } catch (Exception e) {
            log.warn("[TargetPlanner] claim failed: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    private List<Point> findUnexploredCells(boolean[] mapView, boolean[] mapBlock, int mapWidth, int mapHeight) {
        List<Point> cells = new ArrayList<>();
        int total = mapWidth * mapHeight;
        for (int i = 0; i < total; i++) {
            if (!mapView[i] && !mapBlock[i]) {
                cells.add(new Point(i % mapWidth, i / mapWidth));
            }
        }
        return cells;
    }

    private void notifyController(String sessionId, List<Assignment> assignments) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("assignedCars", assignments);
        messageBus.replyToController(sessionId, MessageType.TARGET_ASSIGNED, data);
    }

    public boolean isRunning() { return running; }

    @Override
    public void close() {
        running = false;
        log.info("TargetPlannerAgent stopped");
    }

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
