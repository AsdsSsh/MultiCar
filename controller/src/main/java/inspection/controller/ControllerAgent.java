package inspection.controller;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.blackboard.BlackboardClient;
import inspection.common.blackboard.DistributedLock;
import inspection.common.messaging.MessageBus;
import inspection.common.model.CarStatus;
import inspection.common.model.RouteAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static inspection.common.util.Constants.*;

/**
 * Controller 调度控制器 — 系统唯一调度器
 *
 * 按节拍（tick）循环驱动所有业务流程：
 *   1. 检查任务是否激活
 *   2. 检查探索率是否 ≥ 99.9%
 *   3. 状态调度：遍历所有小车，按5种状态分发命令
 *   4. 节拍移动：向所有READY车发送TICK_MOVE
 *   5. 广播刷新：通过UpdateView fanout通知Display更新
 *   6. 处理Web命令：检查ControllerCmd中的Web转发命令
 *
 * 架构约束：
 *   - Controller是唯一调度器，所有知识源只受Controller调度
 *   - Controller写入CarID:Status的调度型变迁（WAITING_ROUTE/READY/IDLE）
 *   - TargetPlanner和Navigator不写CarID:Status
 */
public class ControllerAgent {

    private static final Logger log = LoggerFactory.getLogger(ControllerAgent.class);

    private final BlackboardClient blackboard;
    private final DistributedLock distributedLock;
    private final MessageBus messageBus;
    private final ScheduledExecutorService scheduler;

    /** 当前节拍号 */
    private final AtomicLong tickCount = new AtomicLong(0);

    /** 待处理的目标分配请求集合 */
    private final Set<String> pendingTargetRequests = new HashSet<>();

    /** 运行状态 */
    private volatile boolean running = false;

    /** 暂停状态（启动后默认运行） */
    private volatile boolean paused = false;

    /** 节拍间隔（毫秒） */
    private long tickIntervalMs = DEFAULT_TICK_INTERVAL_MS;

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

    /**
     * 启动控制器（阻塞等待任务激活）
     */
    public void start() {
        running = true;
        log.info("Controller started, waiting for task activation...");

        // 订阅ControllerCmd队列，处理知识源回复和Web命令
        subscribeToCommands();

        // 启动节拍循环
        scheduler.scheduleAtFixedRate(this::tick, 1000, tickIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 订阅ControllerCmd队列
     */
    private void subscribeToCommands() {
        messageBus.subscribe(QUEUE_CONTROLLER_CMD, (cmd, data, timestamp) -> {
            log.debug("Controller received: cmd={}", cmd);
            switch (cmd) {
                case CMD_TARGET_ASSIGNED -> handleTargetAssigned(data);
                case CMD_ROUTE_PLANNED -> handleRoutePlanned(data);
                case CMD_TASK_READY -> handleTaskReady(data);
                case CMD_MOVED -> handleMoved(data);
                case CMD_BLOCKED -> handleBlocked(data);
                case CMD_ROUTE_DONE -> handleRouteDone(data);
                case CMD_SET_CONFIG -> forwardWebCommand(cmd, data);
                case CMD_RESET -> forwardWebCommand(cmd, data);
                case CMD_PAUSE -> handlePause();
                case CMD_RESUME -> handleResume();
                case CMD_STEP_ONCE -> handleStepOnce(data);
                default -> log.warn("Unknown command: {}", cmd);
            }
        });
    }

    // ==================== 节拍主循环 ====================

    /**
     * 节拍主循环（由调度器调用）
     */
    private void tick() {
        try {
            // 如果暂停，跳过节拍执行（但继续广播刷新以保持前端连接）
            if (paused) {
                broadcastViewUpdate(tickCount.get());
                return;
            }
            tickInternal();
        } catch (Exception e) {
            log.error("Error in tick: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行一个节拍（内部逻辑）
     */
    private void tickInternal() {
        long tick = tickCount.incrementAndGet();
        log.debug("=== Tick {} ===", tick);

        // 0. 重置 TargetPlanner 的批次分配缓存
        resetTargetPlannerBatch();

        // 1. 检查任务是否激活
        if (!blackboard.isTaskActive()) {
            log.debug("Tick {}: Task not active, waiting...", tick);
            return;
        }

        // 2. 检查探索率
        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();
        double explored = blackboard.getExploredPercent(mapWidth, mapHeight);
        if (explored >= EXPLORATION_COMPLETE_THRESHOLD) {
            log.info("Exploration complete! Rate: {}%", String.format("%.1f", explored));
            stop();
            return;
        }

        // 3. 状态调度：遍历所有小车
        int carCount = blackboard.getCarCount();
        List<String> readyCars = new ArrayList<>();

        for (int i = 1; i <= carCount; i++) {
            String carId = String.format("Car%03d", i);
            CarStatus status = blackboard.getCarStatus(carId);

            switch (status) {
                case IDLE -> {
                    // 空闲 → 请求目标分配
                    requestTargetAssignment(carId);
                    pendingTargetRequests.add(carId);
                }
                case WAITING_ROUTE -> {
                    // 等待路径 → 请求路径规划
                    requestRoutePlan(carId);
                }
                case READY -> {
                    // 就绪 → 等待移动
                    readyCars.add(carId);
                }
                case MOVING -> {
                    // 移动中 → 异常恢复，重置为就绪
                    log.warn("Car {} stuck in MOVING, resetting to READY", carId);
                    blackboard.setCarStatus(carId, CarStatus.READY);
                    readyCars.add(carId);
                }
                case BLOCKED -> {
                    // 受阻 → 检查超时
                    checkBlockedTimeout(carId, tick);
                }
            }
        }

        // 4. 节拍移动：向所有READY车发送TICK_MOVE
        for (String carId : readyCars) {
            broadcastTickMove(carId);
        }

        // 5. 广播刷新
        broadcastViewUpdate(tick);
    }

    // ==================== 状态调度 ====================

    /**
     * 重置 TargetPlanner 的批次分配缓存，避免目标被永久占用
     */
    private void resetTargetPlannerBatch() {
        JSONObject data = new JSONObject();
        data.put("cmd", "RESET_BATCH");
        messageBus.publish(QUEUE_TARGET_PLANNER_CMD, CMD_RESET_BATCH, data);
        log.debug("Reset TargetPlanner batch");
    }

    /**
     * 请求目标分配
     */
    private void requestTargetAssignment(String carId) {
        log.debug("Requesting target assignment for {}", carId);
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        messageBus.publish(QUEUE_TARGET_PLANNER_CMD, CMD_ASSIGN_TARGET, data);
    }

    /**
     * 请求路径规划
     */
    private void requestRoutePlan(String carId) {
        RouteAlgorithm algo = RouteAlgorithm.fromString(blackboard.getAlgorithm());
        log.debug("Requesting route plan for {} with {}", carId, algo);
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        data.put("algorithm", algo.name());
        messageBus.publish(QUEUE_NAVIGATOR_CMD, CMD_PLAN_ROUTE, data);
    }

    /**
     * 检查受阻超时
     */
    private void checkBlockedTimeout(String carId, long currentTick) {
        long blockedTick = blackboard.getCarBlockedTick(carId);
        long diff = currentTick - blockedTick;

        if (diff >= BLOCKED_TIMEOUT_TICKS) {
            log.info("Car {} blocked timeout (tick diff={}), resetting to IDLE", carId, diff);
            String lockKey = getLockKey(carId);
            // 使用 Redis 分布式锁保护复合操作
            try {
                boolean ok = distributedLock.executeWithLock(lockKey, LOCK_EXPIRE_MS, () -> {
                    blackboard.clearCarRouteList(carId);
                    blackboard.clearCarTarget(carId);
                    blackboard.setCarStatus(carId, CarStatus.IDLE);
                });
                if (!ok) {
                    log.warn("Failed to acquire lock for {} blocked timeout", carId);
                }
            } catch (Exception e) {
                log.error("Error handling blocked timeout for {}: {}", carId, e.getMessage());
            }
        } else {
            log.debug("Car {} blocked, tick diff={}, waiting...", carId, diff);
        }
    }

    // ==================== 消息处理 ====================

    /**
     * 处理 TARGET_ASSIGNED 回复
     */
    private void handleTargetAssigned(JSONObject data) {
        if (data == null) return;
        List<JSONObject> assignedCars = data.getList("assignedCars", JSONObject.class);
        if (assignedCars == null) return;

        for (JSONObject car : assignedCars) {
            String carId = car.getString("carId");
            if (carId != null) {
                // Controller 写入 Status=WAITING_ROUTE
                blackboard.setCarStatus(carId, CarStatus.WAITING_ROUTE);
                pendingTargetRequests.remove(carId);
                log.debug("Target assigned for {}, status -> WAITING_ROUTE", carId);
            }
        }
    }

    /**
     * 处理 ROUTE_PLANNED 回复
     */
    private void handleRoutePlanned(JSONObject data) {
        if (data == null) return;
        String carId = data.getString("carId");
        boolean routeFound = data.getBooleanValue("routeFound", false);

        if (carId != null) {
            if (routeFound) {
                blackboard.setCarStatus(carId, CarStatus.READY);
                log.debug("Route planned for {}, status -> READY", carId);
            } else {
                // 路径未找到，转回IDLE重新分配，加入pending重新走流程
                blackboard.setCarStatus(carId, CarStatus.IDLE);
                pendingTargetRequests.add(carId);
                log.warn("Route not found for {}, status -> IDLE, will retry", carId);
            }
        }
    }

    /**
     * 处理 TASK_READY 回复
     */
    private void handleTaskReady(JSONObject data) {
        log.info("Task ready: {}", data);
        // 任务初始化完成，可以开始调度
    }

    /**
     * 处理 MOVED 通知
     */
    private void handleMoved(JSONObject data) {
        // Car移动成功，仅做日志记录
        if (data != null) {
            log.debug("Car {} moved to ({}, {})",
                    data.getString("carId"), data.getIntValue("x"), data.getIntValue("y"));
        }
    }

    /**
     * 处理 BLOCKED 通知
     */
    private void handleBlocked(JSONObject data) {
        if (data != null) {
            log.info("Car {} blocked at ({}, {}), blockedPos=({}, {})",
                    data.getString("carId"),
                    data.getIntValue("x"), data.getIntValue("y"),
                    data.getIntValue("blockedX"), data.getIntValue("blockedY"));
        }
        // Controller在后续节拍检查超时
    }

    /**
     * 处理 ROUTE_DONE 通知
     */
    private void handleRouteDone(JSONObject data) {
        if (data != null) {
            log.info("Car {} route done at ({}, {})",
                    data.getString("carId"), data.getIntValue("x"), data.getIntValue("y"));
        }
        // Car已经自己设置Status=IDLE，Controller在下一节拍重新分配
    }

    /**
     * 处理 PAUSE 命令
     */
    private void handlePause() {
        if (!paused) {
            paused = true;
            log.info("Simulation PAUSED");
        }
    }

    /**
     * 处理 RESUME 命令
     */
    private void handleResume() {
        if (paused) {
            paused = false;
            log.info("Simulation RESUMED");
        }
    }

    /**
     * 处理 STEP_ONCE 命令（单步执行）
     */
    private void handleStepOnce(JSONObject data) {
        log.info("Simulation STEP_ONCE");
        // 执行一个节拍，无论是否暂停
        tickInternal();
    }

    // ==================== Web命令转发 ====================

    /**
     * 转发Web命令到TaskConfigurator
     */
    private void forwardWebCommand(String cmd, JSONObject data) {
        String forwardCmd = switch (cmd) {
            case CMD_SET_CONFIG -> CMD_FORWARD_CONFIG;
            case CMD_RESET -> CMD_FORWARD_RESET;
            default -> null;
        };
        if (forwardCmd != null) {
            log.info("Forwarding web command {} -> {}", cmd, forwardCmd);
            messageBus.publish(QUEUE_TASK_CONFIG_CMD, forwardCmd, data);
        }
    }

    // ==================== 广播 ====================

    /**
     * 向单台READY车发送TICK_MOVE（附带当前tick号）
     */
    private void broadcastTickMove(String carId) {
        JSONObject data = new JSONObject();
        data.put("tick", tickCount.get());
        messageBus.publish(getCarQueueName(carId), CMD_TICK_MOVE, data);
        log.debug("TICK_MOVE sent to {}", carId);
    }

    /**
     * 广播视图刷新
     */
    private void broadcastViewUpdate(long tick) {
        JSONObject data = new JSONObject();
        data.put("tick", tick);
        messageBus.fanoutPublish(EXCHANGE_UPDATE_VIEW, CMD_REFRESH_ALL, data);
    }

    // ==================== 控制接口 ====================

    public void setTickInterval(long ms) {
        this.tickIntervalMs = ms;
        log.info("Tick interval set to {}ms", ms);
    }

    public long getTickCount() {
        return tickCount.get();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        log.info("Controller stopped");
    }
}
