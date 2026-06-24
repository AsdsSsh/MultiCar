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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    /** 进程引用（用于运行时删除小车） */
    private final Map<String, Process> carProcesses = new ConcurrentHashMap<>();

    private long defaultTickIntervalMs = DEFAULT_TICK_INTERVAL_MS;

    public ControllerAgent(BlackboardClient blackboard, MessageBus messageBus) {
        this.blackboard = blackboard;
        this.messageBus = messageBus;
        this.distributedLock = new DistributedLock(blackboard.getJedisPool());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Controller-Tick");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== Session 状态 ====================

    private static class SessionState {
        final String sessionId;
        final AtomicLong tickCount = new AtomicLong(0);
        final Set<String> pendingTargetRequests = new HashSet<>();
        volatile boolean paused = true;
        volatile boolean running = true;

        SessionState(String sessionId) { this.sessionId = sessionId; }
    }

    // ==================== 启动 ====================

    public void start() {
        running = true;
        log.info("Controller started (multi-session), waiting for task activations...");

        subscribeToCommands();

        scheduler.scheduleAtFixedRate(this::tick, 1000, defaultTickIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ==================== 消息订阅 ====================

    private void subscribeToCommands() {
        messageBus.subscribe(QUEUE_CONTROLLER_CMD, (cmd, data, timestamp) -> {
            log.debug("Controller received: cmd={}", cmd);
            String sessionId = data != null ? data.getString("sessionId") : null;

            switch (cmd) {
                case CMD_TARGET_ASSIGNED -> handleTargetAssigned(sessionId, data);
                case CMD_ROUTE_PLANNED -> handleRoutePlanned(sessionId, data);
                case CMD_TASK_READY -> handleTaskReady(sessionId, data);
                case CMD_MOVED -> handleMoved(sessionId, data);
                case CMD_BLOCKED -> handleBlocked(sessionId, data);
                case CMD_ROUTE_DONE -> handleRouteDone(sessionId, data);
                case CMD_SET_CONFIG -> forwardWebCommand(cmd, data);
                case CMD_SET_MAP_EDIT -> forwardWebCommand(cmd, data);
                case CMD_RESET -> forwardWebCommand(cmd, data);
                case CMD_PAUSE -> handlePause(sessionId);
                case CMD_RESUME -> handleResume(sessionId);
                case CMD_STEP_ONCE -> handleStepOnce(sessionId);
                default -> log.warn("Unknown command: {}", cmd);
            }
        });
    }

    // ==================== 节拍主循环 ====================

    private void tick() {
        try {
            for (SessionState session : sessions.values()) {
                if (session.paused) {
                    broadcastViewUpdate(session);
                    continue;
                }
                tickSession(session);
            }
        } catch (Exception e) {
            log.error("Error in tick: {}", e.getMessage(), e);
        }
    }

    private void tickSession(SessionState session) {
        String sid = session.sessionId;
        long tick = session.tickCount.incrementAndGet();
        log.debug("=== Tick {} Session {} ===", tick, sid);

        resetTargetPlannerBatch(sid);

        if (!blackboard.isTaskActive(sid)) return;

        int mapWidth = blackboard.getMapWidth(sid);
        int mapHeight = blackboard.getMapHeight(sid);
        double explored = blackboard.getExploredPercent(sid, mapWidth, mapHeight);
        if (explored >= EXPLORATION_COMPLETE_THRESHOLD) {
            log.info("Session {} exploration complete! Rate: {}%", sid, String.format("%.1f", explored));
            session.running = false;
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

    private void resetTargetPlannerBatch(String sessionId) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
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

        sessions.put(sessionId, new SessionState(sessionId));
        blackboard.setPaused(sessionId, true);
        log.info("Session {} created, paused (waiting for user)", sessionId);
        broadcastViewUpdate(sessions.get(sessionId));
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
        }
    }

    private void handleStepOnce(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s != null) tickSession(s);
    }

    // ==================== Web 命令转发（带 sessionId） ====================

    private void forwardWebCommand(String cmd, JSONObject data) {
        String forwardCmd = switch (cmd) {
            case CMD_SET_CONFIG -> CMD_FORWARD_CONFIG;
            case CMD_SET_MAP_EDIT -> CMD_FORWARD_MAP_EDIT;
            case CMD_RESET -> CMD_FORWARD_RESET;
            default -> null;
        };
        if (forwardCmd != null) {
            // 为 SET_CONFIG 生成 sessionId
            if (CMD_SET_CONFIG.equals(cmd) && (data == null || !data.containsKey("sessionId"))) {
                String sessionId = UUID.randomUUID().toString().substring(0, 8);
                data = data != null ? data : new JSONObject();
                data.put("sessionId", sessionId);
                log.info("Generated sessionId: {} for SET_CONFIG", sessionId);
            }
            messageBus.publish(QUEUE_TASK_CONFIG_CMD, forwardCmd, data);
        }
    }

    // ==================== 广播 ====================

    private void broadcastTickMove(String sessionId, String carId) {
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("tick", sessions.get(sessionId) != null ? sessions.get(sessionId).tickCount.get() : 0);
        messageBus.publish(getCarQueueName(carId), CMD_TICK_MOVE, data);
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
