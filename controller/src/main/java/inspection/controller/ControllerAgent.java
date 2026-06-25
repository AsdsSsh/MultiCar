package inspection.controller;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.DistributedLock;
import inspection.common.messaging.MessageBus;
import inspection.common.model.CarStatus;
import inspection.common.model.Point;
import inspection.common.model.RouteAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static inspection.common.util.Constants.*;

public class ControllerAgent {

    private static final Logger log = LoggerFactory.getLogger(ControllerAgent.class);

    private final BlackboardClient blackboard;
    private final DistributedLock distributedLock;
    private final MessageBus messageBus;
    private final ScheduledExecutorService scheduler;

    private volatile boolean running = false;

    /** 活跃 session */
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    private long defaultTickIntervalMs = DEFAULT_TICK_INTERVAL_MS;

    public ControllerAgent(BlackboardClient blackboard, MessageBus messageBus) {
        this.blackboard = blackboard;
        this.messageBus = messageBus;
        this.distributedLock = new DistributedLock(blackboard.getJedisPool());
        // 线程池，支持多个 session 独立并行 tick
        this.scheduler = Executors.newScheduledThreadPool(8, r -> {
            Thread t = new Thread(r, "Controller-Tick");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== Session 状态 ====================

    private static class SessionState {
        final String sessionId;
        final AtomicLong tickCount = new AtomicLong(0);
        final Set<String> pendingTargetRequests = ConcurrentHashMap.newKeySet();
        volatile boolean paused = true;
        volatile boolean running = true;
        volatile ScheduledFuture<?> tickFuture;

        SessionState(String sessionId) { this.sessionId = sessionId; }
    }

    // ==================== 启动 ====================

    public void start() {
        running = true;
        log.info("Controller started (multi-session, per-session tick threads), waiting for task activations...");

        // 共享队列：接收来自 display-node 的 Web 命令
        messageBus.subscribe("ControllerCmd", (cmd, data, timestamp) -> {
            log.debug("Controller received web command: cmd={}", cmd);
            handleWebCommand(cmd, data);
        });
    }

    // ==================== Web 命令处理（共享 ControllerCmd 队列） ====================

    private void handleWebCommand(String cmd, JSONObject data) {
        String sessionId = data != null ? data.getString("sessionId") : null;
        switch (cmd) {
            case CMD_SET_CONFIG, CMD_SET_MAP_EDIT, CMD_RESET -> forwardWebCommand(cmd, data);
            case CMD_PAUSE -> handlePause(sessionId);
            case CMD_STOP -> handleStop(sessionId);
            case CMD_RESUME -> handleResume(sessionId);
            case CMD_STEP_ONCE -> handleStepOnce(sessionId);
            case CMD_ADD_CAR -> handleAddCar(sessionId, data);
            case CMD_DELETE_CAR -> handleDeleteCar(sessionId, data);
            case CMD_MOVE_CAR -> handleMoveCar(sessionId, data);
            default -> log.warn("Unknown web command: {}", cmd);
        }
    }

    // ==================== session 专属队列处理（回复 + Web 命令） ====================

    private void subscribeSessionReplies(String sessionId) {
        String queue = getControllerCmdQueue(sessionId);
        try {
            messageBus.declareQueue(queue);
        } catch (IOException e) {
            log.error("Failed to declare session reply queue: {}", queue);
        }
        messageBus.subscribe(queue, (cmd, data, timestamp) -> {
            log.debug("Session {} cmd: {}", sessionId, cmd);
            switch (cmd) {
                // 初始化
                case CMD_TASK_READY -> handleTaskReady(sessionId, data);
                // 回复
                case CMD_TARGET_ASSIGNED -> handleTargetAssigned(sessionId, data);
                case CMD_ROUTE_PLANNED -> handleRoutePlanned(sessionId, data);
                case CMD_MOVED -> handleMoved(sessionId, data);
                case CMD_BLOCKED -> handleBlocked(sessionId, data);
                case CMD_ROUTE_DONE -> handleRouteDone(sessionId, data);
                // Web 命令
                case CMD_PAUSE -> handlePause(sessionId);
                case CMD_STOP -> handleStop(sessionId);
                case CMD_RESUME -> handleResume(sessionId);
                case CMD_STEP_ONCE -> handleStepOnce(sessionId);
                case CMD_ADD_CAR -> handleAddCar(sessionId, data);
                case CMD_DELETE_CAR -> handleDeleteCar(sessionId, data);
                case CMD_MOVE_CAR -> handleMoveCar(sessionId, data);
                default -> log.warn("Unknown cmd for session {}: {}", sessionId, cmd);
            }
        });
    }

    // ==================== Session Tick 调度 ====================

    private void startSessionTick(SessionState session) {
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!session.running) return;
                if (session.paused) {
                    broadcastViewUpdate(session);
                    return;
                }
                tickSession(session);
            } catch (Exception e) {
                log.error("Error in session {} tick: {}", session.sessionId, e.getMessage(), e);
            }
        }, 1000, defaultTickIntervalMs, TimeUnit.MILLISECONDS);
        session.tickFuture = future;
        log.info("Session {} tick started (interval={}ms)", session.sessionId, defaultTickIntervalMs);
    }

    private void stopSessionTick(SessionState session) {
        if (session.tickFuture != null) {
            session.tickFuture.cancel(false);
        }
    }

    // ==================== 节拍主逻辑 ====================

    private void tickSession(SessionState session) {
        String sid = session.sessionId;
        long tick = session.tickCount.incrementAndGet();
        log.debug("=== Tick {} Session {} ===", tick, sid);

        resetTargetPlannerBatch(session);

        if (!blackboard.isTaskActive(sid)) return;

        int mapWidth = blackboard.getMapWidth(sid);
        int mapHeight = blackboard.getMapHeight(sid);
        double explored = blackboard.getExploredPercent(sid, mapWidth, mapHeight);
        if (explored >= EXPLORATION_COMPLETE_THRESHOLD) {
            log.info("Session {} exploration complete! Rate: {}%", sid, String.format("%.1f", explored));
            session.running = false;
            stopSessionTick(session);
            sessions.remove(sid);
            return;
        }

        int carCount = blackboard.getCarCount(sid);
        int maxCarNum = Math.max(carCount, blackboard.getMaxCarNumber(sid));
        List<String> readyCars = new ArrayList<>();

        for (int i = 1; i <= maxCarNum; i++) {
            String carId = String.format("Car%03d", i);
            CarStatus status = blackboard.getCarStatus(sid, carId);

            switch (status) {
                case IDLE -> {
                    requestTargetAssignment(sid, carId);
                    session.pendingTargetRequests.add(carId);
                }
                case WAITING_ROUTE -> requestRoutePlan(sid, carId);
                case READY -> readyCars.add(carId);
                case MOVING -> {
                    log.warn("Car {} stuck in MOVING in session {}, resetting", carId, sid);
                    blackboard.setCarStatus(sid, carId, CarStatus.READY);
                    readyCars.add(carId);
                }
                case BLOCKED -> checkBlockedTimeout(sid, carId, tick);
            }
        }

        for (String carId : readyCars) {
            broadcastTickMove(sid, carId);
        }

        broadcastViewUpdate(session);
    }

    // ==================== 调度命令 ====================

    private void resetTargetPlannerBatch(SessionState session) {
        String sessionId = session.sessionId;
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("tick", session.tickCount.get() + 1);
        messageBus.publish(QUEUE_TARGET_PLANNER_CMD, CMD_RESET_BATCH, data);
    }

    private void requestTargetAssignment(String sessionId, String carId) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("carId", carId);
        messageBus.publish(QUEUE_TARGET_PLANNER_CMD, CMD_ASSIGN_TARGET, data);
    }

    private void requestRoutePlan(String sessionId, String carId) {
        RouteAlgorithm algo = RouteAlgorithm.fromString(blackboard.getAlgorithm(sessionId));
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("carId", carId);
        data.put("algorithm", algo.name());
        messageBus.publish(QUEUE_NAVIGATOR_CMD, CMD_PLAN_ROUTE, data);
    }

    private void checkBlockedTimeout(String sessionId, String carId, long currentTick) {
        long blockedTick = blackboard.getCarBlockedTick(sessionId, carId);
        if (currentTick - blockedTick >= BLOCKED_TIMEOUT_TICKS) {
            log.info("Car {} blocked timeout in session {}", carId, sessionId);
            String lockKey = getLockKey(sessionId, carId);
            distributedLock.executeWithLock(lockKey, LOCK_EXPIRE_MS, () -> {
                blackboard.clearCarRouteList(sessionId, carId);
                blackboard.clearCarTarget(sessionId, carId);
                blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
            });
        }
    }

    // ==================== 消息处理 ====================

    private void handleTargetAssigned(String sessionId, JSONObject data) {
        if (sessionId == null || data == null) return;
        List<JSONObject> assignedCars = data.getList("assignedCars", JSONObject.class);
        if (assignedCars == null) return;

        for (JSONObject car : assignedCars) {
            String carId = car.getString("carId");
            if (carId != null) {
                blackboard.setCarStatus(sessionId, carId, CarStatus.WAITING_ROUTE);
                SessionState s = sessions.get(sessionId);
                if (s != null) s.pendingTargetRequests.remove(carId);
            }
        }
    }

    private void handleRoutePlanned(String sessionId, JSONObject data) {
        if (sessionId == null || data == null) return;
        String carId = data.getString("carId");
        boolean routeFound = data.getBooleanValue("routeFound", false);

        if (carId != null) {
            if (routeFound) {
                blackboard.setCarStatus(sessionId, carId, CarStatus.READY);
            } else {
                blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
                SessionState s = sessions.get(sessionId);
                if (s != null) s.pendingTargetRequests.add(carId);
            }
        }
    }

    private void handleTaskReady(String sessionId, JSONObject data) {
        if (sessionId == null) return;
        log.info("Session {} task ready", sessionId);

        try {
            int carCount = data != null ? data.getIntValue("carCount") : blackboard.getCarCount(sessionId);
            messageBus.declareSessionQueues(sessionId, carCount);
        } catch (IOException e) {
            log.error("Failed to declare session queues for {}: {}", sessionId, e.getMessage());
        }

        // 订阅 session 专属回复队列
        subscribeSessionReplies(sessionId);

        SessionState session = new SessionState(sessionId);
        sessions.put(sessionId, session);
        blackboard.setPaused(sessionId, true);

        // 启动 session 独立 tick 线程
        startSessionTick(session);

        log.info("Session {} created, paused (waiting for user)", sessionId);
        broadcastViewUpdate(session);
    }

    private void handleMoved(String sessionId, JSONObject data) {
        if (sessionId != null && data != null) {
            log.debug("Car {} moved in session {}", data.getString("carId"), sessionId);
        }
    }

    private void handleBlocked(String sessionId, JSONObject data) {
        if (sessionId != null && data != null) {
            log.info("Car {} blocked in session {}", data.getString("carId"), sessionId);
        }
    }

    private void handleRouteDone(String sessionId, JSONObject data) {
        if (sessionId != null && data != null) {
            log.info("Car {} route done in session {}", data.getString("carId"), sessionId);
        }
    }

    private void handleStop(String sessionId) {
        SessionState s = sessions.remove(sessionId);
        if (s != null) {
            s.running = false;
            s.paused = true;
            stopSessionTick(s);
            blackboard.clearSession(sessionId);
            log.info("Session {} STOPPED and cleaned", sessionId);
        }
    }

    private void handlePause(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s != null && !s.paused) {
            s.paused = true;
            blackboard.setPaused(sessionId, true);
            log.info("Session {} PAUSED", sessionId);
        }
    }

    private void handleResume(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s != null && s.paused) {
            s.paused = false;
            blackboard.setPaused(sessionId, false);
            log.info("Session {} RESUMED", sessionId);
            scheduler.execute(() -> tickSession(s));
        }
    }

    private void handleStepOnce(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s != null) scheduler.execute(() -> tickSession(s));
    }

    // ==================== 运行时小车管理 ====================

    private void handleAddCar(String sessionId, JSONObject data) {
        if (sessionId == null) return;
        SessionState s = sessions.get(sessionId);
        if (s == null) return;

        int mapWidth = blackboard.getMapWidth(sessionId);
        int mapHeight = blackboard.getMapHeight(sessionId);

        int maxNum = blackboard.getMaxCarNumber(sessionId);
        String carId = String.format("Car%03d", maxNum + 1);

        int x, y;
        if (data != null && data.containsKey("x") && data.containsKey("y")) {
            x = Math.max(0, Math.min(data.getIntValue("x"), mapWidth - 1));
            y = Math.max(0, Math.min(data.getIntValue("y"), mapHeight - 1));
        } else {
            Point pos = findAvailablePosition(sessionId, mapWidth, mapHeight);
            if (pos == null) {
                log.warn("No available position for new car in session {}", sessionId);
                return;
            }
            x = pos.getX();
            y = pos.getY();
        }

        blackboard.setCarPosition(sessionId, carId, x, y);
        blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
        blackboard.setCarSteps(sessionId, carId, 0);
        blackboard.incrementCarCount(sessionId);

        log.info("Added car {} at ({},{}) in session {}", carId, x, y, sessionId);
        broadcastViewUpdate(s);
    }

    private void handleDeleteCar(String sessionId, JSONObject data) {
        if (sessionId == null || data == null) return;
        String carId = data.getString("carId");
        if (carId == null) return;
        SessionState s = sessions.get(sessionId);
        if (s == null) return;

        blackboard.deleteCarData(sessionId, carId);
        blackboard.decrementCarCount(sessionId);

        log.info("Deleted car {} in session {}", carId, sessionId);
        broadcastViewUpdate(s);
    }

    private void handleMoveCar(String sessionId, JSONObject data) {
        if (sessionId == null || data == null) return;
        String carId = data.getString("carId");
        if (carId == null) return;
        SessionState s = sessions.get(sessionId);
        if (s == null) return;

        int mapWidth = blackboard.getMapWidth(sessionId);
        int mapHeight = blackboard.getMapHeight(sessionId);
        int x = Math.max(0, Math.min(data.getIntValue("x"), mapWidth - 1));
        int y = Math.max(0, Math.min(data.getIntValue("y"), mapHeight - 1));

        String lockKey = getLockKey(sessionId, carId);
        distributedLock.executeWithLock(lockKey, LOCK_EXPIRE_MS, () -> {
            blackboard.setCarPosition(sessionId, carId, x, y);
            blackboard.clearCarRouteList(sessionId, carId);
            blackboard.clearCarTarget(sessionId, carId);
            blackboard.setCarStatus(sessionId, carId, CarStatus.IDLE);
        });

        log.info("Moved car {} to ({},{}) in session {}", carId, x, y, sessionId);
        broadcastViewUpdate(s);
    }

    private Point findAvailablePosition(String sessionId, int mapWidth, int mapHeight) {
        Map<String, Point> carPositions = blackboard.getAllCarPositions(sessionId);
        Set<String> occupied = new HashSet<>();
        for (Point p : carPositions.values()) {
            occupied.add(p.getX() + "," + p.getY());
        }

        Random rand = new Random();
        for (int attempt = 0; attempt < 500; attempt++) {
            int x = rand.nextInt(mapWidth);
            int y = rand.nextInt(mapHeight);
            String key = x + "," + y;
            if (!occupied.contains(key) && !blackboard.isPositionBlocked(sessionId, x, y)) {
                return new Point(x, y);
            }
        }

        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                String key = x + "," + y;
                if (!occupied.contains(key) && !blackboard.isPositionBlocked(sessionId, x, y)) {
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    // ==================== Web 命令转发 ====================

    private void forwardWebCommand(String cmd, JSONObject data) {
        String forwardCmd = switch (cmd) {
            case CMD_SET_CONFIG -> CMD_FORWARD_CONFIG;
            case CMD_SET_MAP_EDIT -> CMD_FORWARD_MAP_EDIT;
            case CMD_RESET -> CMD_FORWARD_RESET;
            default -> null;
        };
        if (forwardCmd != null) {
            // SET_CONFIG 必须立即订阅 session 专属队列（无论 sessionId 来自客户端还是服务端生成）
            if (CMD_SET_CONFIG.equals(cmd)) {
                String sessionId;
                if (data == null || !data.containsKey("sessionId")) {
                    sessionId = UUID.randomUUID().toString().substring(0, 8);
                    data = data != null ? data : new JSONObject();
                    data.put("sessionId", sessionId);
                    log.info("Generated sessionId: {} for SET_CONFIG", sessionId);
                } else {
                    sessionId = data.getString("sessionId");
                }
                subscribeSessionReplies(sessionId);
                log.info("Subscribed reply queue for session: {}", sessionId);
            }
            messageBus.publish(QUEUE_TASK_CONFIG_CMD, forwardCmd, data);
        }
    }

    // ==================== 广播 ====================

    private void broadcastTickMove(String sessionId, String carId) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("carId", carId);
        data.put("tick", sessions.get(sessionId) != null ? sessions.get(sessionId).tickCount.get() : 0);
        messageBus.publish(QUEUE_CAR_POOL, CMD_TICK_MOVE, data);
    }

    private void broadcastViewUpdate(SessionState session) {
        if (session == null) return;
        JSONObject data = new JSONObject();
        data.put("sessionId", session.sessionId);
        data.put("tick", session.tickCount.get());
        messageBus.fanoutPublish(getSessionFanoutExchange(session.sessionId), CMD_REFRESH_ALL, data);
    }

    // ==================== 控制接口 ====================

    public void setTickInterval(long ms) {
        this.defaultTickIntervalMs = ms;
    }

    public boolean isRunning() { return running; }

    public void stop() {
        running = false;
        scheduler.shutdown();
        log.info("Controller stopped");
    }
}
