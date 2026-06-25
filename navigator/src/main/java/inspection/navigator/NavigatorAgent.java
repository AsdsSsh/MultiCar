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

    public void start() throws IOException {
        log.info("=== Navigator starting ===");
        messageBus.declareKnowledgeSourceInput(Constants.QUEUE_NAVIGATOR_CMD);

        messageBus.subscribeConcurrent(Constants.QUEUE_NAVIGATOR_CMD, (cmd, data, timestamp) -> {
            if (MessageType.PLAN_ROUTE.equals(cmd)) {
                handlePlanRoute(data);
            }
        }, 4);

        running = true;
        log.info("Navigator ready — listening on '{}'", Constants.QUEUE_NAVIGATOR_CMD);
    }

    private void handlePlanRoute(JSONObject data) {
        String sessionId = data.getString("sessionId");
        String carId = data.getString("carId");
        String algoStr = data.getString("algorithm");
        if (algoStr == null) algoStr = "BFS";
        RouteAlgorithm algorithm = RouteAlgorithm.valueOf(algoStr);

        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("[Navigator] Missing sessionId, skip");
            notifyController(null, carId, false, 0);
            return;
        }

        log.info("[Navigator] PLAN_ROUTE session={} car={} algo={}", sessionId, carId, algorithm);

        String lockKey = Constants.getLockKey(sessionId, carId);
        boolean locked = distLock.executeWithLock(lockKey, Constants.LOCK_EXPIRE_MS, () -> {
            planRouteForCar(sessionId, carId, algorithm);
        });

        if (!locked) {
            log.warn("[Navigator] Failed to acquire lock for {}:{}", sessionId, carId);
            notifyController(sessionId, carId, false, 0);
        }
    }

    private void planRouteForCar(String sessionId, String carId, RouteAlgorithm algorithm) {
        Point start = blackboard.getCarPosition(sessionId, carId);
        Point target = blackboard.getCarTarget(sessionId, carId);
        int mapWidth = blackboard.getMapWidth(sessionId);
        int mapHeight = blackboard.getMapHeight(sessionId);
        boolean[] mapBlock = blackboard.getFullMapBlock(sessionId, mapWidth, mapHeight);
        boolean[] mapView = blackboard.getFullMapView(sessionId, mapWidth, mapHeight);

        if (start == null) {
            notifyController(sessionId, carId, false, 0);
            return;
        }
        if (target == null) {
            notifyController(sessionId, carId, false, 0);
            return;
        }

        addOtherCarsAsObstacles(sessionId, mapBlock, carId, mapWidth, mapHeight);

        PathFinder finder = pathFinders.getOrDefault(algorithm, pathFinders.get(RouteAlgorithm.BFS));
        List<Point> route = finder.findPath(start, target, mapWidth, mapHeight, mapBlock, mapView);

        if (!route.isEmpty()) {
            blackboard.setCarRouteList(sessionId, carId, route);
            log.info("[Navigator] {}:{} route planned: {} steps", sessionId, carId, route.size());
            notifyController(sessionId, carId, true, route.size());
        } else {
            log.warn("[Navigator] {}:{} no route found", sessionId, carId);
            notifyController(sessionId, carId, false, 0);
        }
    }

    private void addOtherCarsAsObstacles(String sessionId, boolean[] mapBlock, String currentCarId, int mapWidth, int mapHeight) {
        int carCount = blackboard.getCarCount(sessionId);
        for (int i = 1; i <= carCount; i++) {
            String otherId = String.format("Car%03d", i);
            if (otherId.equals(currentCarId)) continue;
            Point pos = blackboard.getCarPosition(sessionId, otherId);
            if (pos != null) {
                int offset = pos.toBitmapOffset(mapWidth);
                if (offset >= 0 && offset < mapBlock.length) {
                    mapBlock[offset] = true;
                }
            }
        }
    }

    private void notifyController(String sessionId, String carId, boolean routeFound, int routeLength) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("carId", carId);
        data.put("routeFound", routeFound);
        data.put("routeLength", routeLength);
        messageBus.replyToController(sessionId, MessageType.ROUTE_PLANNED, data);
    }

    public boolean isRunning() { return running; }

    @Override
    public void close() {
        running = false;
        distLock.close();
        log.info("NavigatorAgent stopped");
    }
}
