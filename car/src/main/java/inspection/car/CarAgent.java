package inspection.car;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.DistributedLock;
import inspection.common.messaging.MessageBus;
import inspection.common.model.CarStatus;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static inspection.common.util.Constants.*;

public class CarAgent {

    private static final Logger log = LoggerFactory.getLogger(CarAgent.class);

    private final String carId;
    private final String displayId;
    private final BlackboardClient blackboard;
    private final MessageBus messageBus;
    private final DistributedLock distributedLock;

    private volatile boolean running = false;

    public CarAgent(String carId, BlackboardClient blackboard, MessageBus messageBus,
                    DistributedLock distributedLock) {
        this.carId = carId;
        this.displayId = BlackboardClient.getDisplayId(carId);
        this.blackboard = blackboard;
        this.messageBus = messageBus;
        this.distributedLock = distributedLock;
    }

    public void start() {
        running = true;
        log.debug("Car {} active (lazy)", carId);
    }

    /** CarMain 兼容：独立进程模式下通过 MQ 订阅接收消息 */
    public void startStandalone() {
        running = true;
        log.info("Car {} standalone, subscribing to MQ", carId);
        messageBus.subscribe(getCarQueueName(carId), this::handleMessage);
        log.info("Car {} ready", carId);
    }

    public void handleMessage(String cmd, JSONObject data, long timestamp) {
        if (!running) return;

        if (CMD_TICK_MOVE.equals(cmd)) {
            String sessionId = data != null ? data.getString("sessionId") : null;
            long tick = data != null ? data.getLongValue("tick", 0) : 0;
            if (sessionId == null || sessionId.isEmpty()) {
                log.warn("Car {} received TICK_MOVE without sessionId", carId);
                return;
            }
            handleTickMove(sessionId, tick);
        }
    }

    private void handleTickMove(String sessionId, long currentTick) {
        CarStatus currentStatus = blackboard.getCarStatus(sessionId, carId);
        if (currentStatus != CarStatus.READY) {
            return;
        }

        Point nextStep = blackboard.peekNextRouteStep(sessionId, carId);
        if (nextStep == null) {
            handleRouteDone(sessionId);
            return;
        }

        int mapWidth = blackboard.getMapWidth(sessionId);
        boolean isExplored = blackboard.getMapViewBit(sessionId, nextStep.getX(), nextStep.getY(), mapWidth);
        boolean isObstacle = blackboard.getMapBlockBit(sessionId, nextStep.getX(), nextStep.getY(), mapWidth);
        if (isExplored && isObstacle) {
            handleBlocked(sessionId, nextStep, currentTick);
            return;
        }

        String lockKey = getLockKey(sessionId, carId);
        String requestId = distributedLock.tryLockWithId(lockKey, LOCK_EXPIRE_MS);
        if (requestId == null) return;

        try {
            executeMove(sessionId, nextStep, mapWidth, currentTick);
        } finally {
            distributedLock.unlock(lockKey, requestId);
        }
    }

    private void executeMove(String sessionId, Point nextStep, int mapWidth, long currentTick) {
        int mapHeight = blackboard.getMapHeight(sessionId);

        blackboard.setCarStatus(sessionId, carId, CarStatus.MOVING);

        Point popped = blackboard.popNextRouteStep(sessionId, carId);
        if (popped == null) {
            blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
            notifyController(sessionId, CMD_ROUTE_DONE, null);
            return;
        }

        blackboard.setCarPosition(sessionId, carId, popped.getX(), popped.getY());
        blackboard.illuminateArea(sessionId, popped.getX(), popped.getY(), mapWidth, mapHeight);

        if (blackboard.getMapBlockBit(sessionId, popped.getX(), popped.getY(), mapWidth)) {
            log.warn("Car {} stepped on unknown obstacle at ({}, {}) in session {}", carId, popped.getX(), popped.getY(), sessionId);
            blackboard.clearCarRouteList(sessionId, carId);
            blackboard.clearCarTarget(sessionId, carId);
            blackboard.setCarStatus(sessionId, carId, CarStatus.BLOCKED);
            blackboard.setCarBlockedTick(sessionId, carId, currentTick);
            JSONObject data = new JSONObject();
            data.put("sessionId", sessionId);
            data.put("carId", carId);
            data.put("x", popped.getX());
            data.put("y", popped.getY());
            messageBus.publish(QUEUE_CONTROLLER_CMD, CMD_BLOCKED, data);
            return;
        }

        blackboard.incrementCarSteps(sessionId, carId);

        Point remaining = blackboard.peekNextRouteStep(sessionId, carId);
        if (remaining == null) {
            handleRouteDoneLocked(sessionId, popped);
        } else {
            blackboard.setCarStatus(sessionId, carId, CarStatus.READY);
            notifyController(sessionId, CMD_MOVED, popped);
        }
    }

    private void handleRouteDoneLocked(String sessionId, Point pos) {
        blackboard.clearCarRouteList(sessionId, carId);
        blackboard.clearCarTarget(sessionId, carId);
        blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
        notifyController(sessionId, CMD_ROUTE_DONE, pos);
    }

    private void handleRouteDone(String sessionId) {
        Point pos = blackboard.getCarPosition(sessionId, carId);
        blackboard.clearCarRouteList(sessionId, carId);
        blackboard.clearCarTarget(sessionId, carId);
        blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
        notifyController(sessionId, CMD_ROUTE_DONE, pos);
    }

    private void handleBlocked(String sessionId, Point blockedPos, long currentTick) {
        blackboard.clearCarRouteList(sessionId, carId);
        blackboard.setCarStatus(sessionId, carId, CarStatus.BLOCKED);
        blackboard.setCarBlockedTick(sessionId, carId, currentTick);

        Point pos = blackboard.getCarPosition(sessionId, carId);
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("carId", carId);
        if (pos != null) {
            data.put("x", pos.getX());
            data.put("y", pos.getY());
        }
        data.put("blockedX", blockedPos.getX());
        data.put("blockedY", blockedPos.getY());
        messageBus.publish(QUEUE_CONTROLLER_CMD, CMD_BLOCKED, data);
    }

    private void notifyController(String sessionId, String cmd, Point position) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("carId", carId);
        if (position != null) {
            data.put("x", position.getX());
            data.put("y", position.getY());
        }
        messageBus.publish(QUEUE_CONTROLLER_CMD, cmd, data);
    }

    public String getCarId() { return carId; }
    public String getDisplayId() { return displayId; }
    public boolean isRunning() { return running; }
    public void stop() { running = false; }
}
