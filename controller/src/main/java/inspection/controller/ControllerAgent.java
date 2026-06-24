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

    /** 已启动的 Car 进程（用于运行时删除） */
    private final Map<String, Process> carProcesses = new HashMap<>();

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
                case CMD_SET_MAP_EDIT -> forwardWebCommand(cmd, data);
                case CMD_RESET -> forwardWebCommand(cmd, data);
                case CMD_PAUSE -> handlePause();
                case CMD_RESUME -> handleResume();
                case CMD_STEP_ONCE -> handleStepOnce(data);
                case CMD_ADD_CAR -> handleAddCar(data);
                case CMD_DELETE_CAR -> handleDeleteCar(data);
                case CMD_MOVE_CAR -> handleMoveCar(data);
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

        // 3. 状态调度：遍历所有小车（按实际最大编号扫描，支持动态添加）
        int carCount = blackboard.getCarCount();
        int maxCarNum = Math.max(carCount, blackboard.getMaxCarNumber());
        List<String> readyCars = new ArrayList<>();

        for (int i = 1; i <= maxCarNum; i++) {
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
        // 任务初始化完成后，默认暂停，等待用户手动开始
        paused = true;
        blackboard.setPaused(true);
        log.info("Simulation PAUSED (initial state), waiting for user to start");
        // 立即推送初始状态到前端（不等下一个 tick 周期）
        broadcastViewUpdate(0);
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
            blackboard.setPaused(true);
            log.info("Simulation PAUSED");
        }
    }

    /**
     * 处理 RESUME 命令
     */
    private void handleResume() {
        if (paused) {
            paused = false;
            blackboard.setPaused(false);
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

    /**
     * 处理 ADD_CAR 命令 — 在探索过程中动态添加小车
     * 
     * 1. 扫描 Redis 中现有小车，找到最大编号 + 1 作为新车编号
     * 2. 在 Redis 黑板中初始化新车数据（不清除已有数据）
     * 3. 声明新车 MQ 队列
     * 4. 启动新的 Car JVM 进程（CarMain newCarId）
     *
     * @param data 可选的位置信息 { x, y }，为空时自动分配位置
     */
    private void handleAddCar(JSONObject data) {
        // 扫描现有小车，找到最大编号
        int maxCarNum = blackboard.getMaxCarNumber();
        int newCarNum = maxCarNum + 1;
        String newCarId = String.format("Car%03d", newCarNum);

        // 重新计算实际小车数量（扫描 Redis keys）
        int actualCarCount = blackboard.getActualCarCount();

        log.info("Adding new car: {} (existing cars: {}, max num: {})", newCarId, actualCarCount, maxCarNum);

        // 初始化新车：位置、状态、视野
        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();

        // 支持用户指定位置，未指定则自动分配
        Point initPos;
        if (data != null && data.containsKey("x") && data.containsKey("y")) {
            int x = Math.max(0, Math.min(data.getIntValue("x"), mapWidth - 1));
            int y = Math.max(0, Math.min(data.getIntValue("y"), mapHeight - 1));
            initPos = new Point(x, y);
            log.info("New car {} using user-specified position ({}, {})", newCarId, x, y);
        } else {
            // 使用实际小车数作为 0-based index（用于初始位置分配）
            initPos = getCarInitialPosition(actualCarCount, mapWidth, mapHeight);
            log.info("New car {} using auto-assigned position ({}, {})", newCarId, initPos.getX(), initPos.getY());
        }

        blackboard.setCarPosition(newCarId, initPos);
        blackboard.setCarStatus(newCarId, CarStatus.IDLE);
        blackboard.setCarSteps(newCarId, 0);
        // 增量点亮新车初始视野（不覆盖已有探索数据）
        blackboard.illuminateArea(initPos.getX(), initPos.getY(), mapWidth, mapHeight);

        // 声明新车的 MQ 队列
        try {
            messageBus.declareQueue(getCarQueueName(newCarId));
        } catch (IOException e) {
            log.error("Failed to declare queue for {}: {}", newCarId, e.getMessage());
        }

        // 更新 carCount 为实际小车数 + 1
        int newCarCount = actualCarCount + 1;
        blackboard.setTaskConfigField("carCount", String.valueOf(newCarCount));

        // 启动新的 Car JVM 进程
        try {
            spawnCarProcess(newCarId);
        } catch (IOException e) {
            log.error("Failed to spawn car process for {}: {}", newCarId, e.getMessage());
        }

        log.info("New car {} initialized at ({}, {}), status=IDLE, carCount={}",
                newCarId, initPos.getX(), initPos.getY(), newCarCount);
    }

    /**
     * 处理 DELETE_CAR：删除小车（运行时操作，不影响地图原始配置）
     *
     * 流程：
     *   0. 获取删除前小车位置（用于日志）
     *   1. 清理 Redis 中小车的所有数据
     *   2. 杀死对应的 Car JVM 进程
     *   3. 更新 carCount
     *   4. 仿真未启动时：完全重算视野（先清空再按剩余小车位置照亮）
     */
    private void handleDeleteCar(JSONObject data) {
        if (data == null) return;
        String carId = data.getString("carId");
        if (carId == null || carId.isEmpty()) {
            log.warn("DELETE_CAR: carId is empty");
            return;
        }
        log.info("Deleting car: {}", carId);

        // 0. 获取删除前小车位置（必须在 deleteCarData 之前）
        Point oldPos = blackboard.getCarPosition(carId);

        // 1. 清理 Redis 数据
        blackboard.deleteCarData(carId);

        // 2. 杀死 Car 进程
        Process process = carProcesses.remove(carId);
        if (process != null) {
            if (process.isAlive()) {
                process.destroyForcibly();
                log.info("Terminated Car process for {}", carId);
            }
        } else {
            log.warn("No process found for {}, car may not be running", carId);
        }

        // 3. 更新 carCount
        int actualCarCount = blackboard.getActualCarCount();
        blackboard.setTaskConfigField("carCount", String.valueOf(actualCarCount));
        log.info("Car {} deleted, actualCarCount updated to {}", carId, actualCarCount);

        // 4. 仿真未启动时：完全重算视野，确保只有当前存在的小车位置被照亮
        long currentTick = tickCount.get();
        if (currentTick == 0) {
            int mapWidth = blackboard.getMapWidth();
            int mapHeight = blackboard.getMapHeight();
            blackboard.recalculateFullVision(mapWidth, mapHeight);
            log.info("Simulation not started yet, recalculated full vision after deleting {}",
                    carId);
        }

        // 5. 广播视图更新
        broadcastViewUpdate(currentTick);
    }

    /**
     * 处理 MOVE_CAR：移动小车到新位置（运行时操作，不影响地图原始配置）
     *
     * 流程：
     *   1. 读取旧位置
     *   2. 清除小车当前的目标和路径
     *   3. 设置新位置
     *   4. 视野处理：
     *      - tick==0：完全重算视野（先清空再按所有小车当前位置照亮）
     *      - tick>0：仅增量点亮新位置（保留历史探索）
     *   5. 设置状态为 IDLE（让调度器重新分配目标）
     */
    private void handleMoveCar(JSONObject data) {
        if (data == null) return;
        String carId = data.getString("carId");
        if (carId == null || carId.isEmpty()) {
            log.warn("MOVE_CAR: carId is empty");
            return;
        }

        int x = data.getIntValue("x", -1);
        int y = data.getIntValue("y", -1);
        if (x < 0 || y < 0) {
            log.warn("MOVE_CAR: invalid position ({}, {})", x, y);
            return;
        }

        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();
        if (x >= mapWidth || y >= mapHeight) {
            log.warn("MOVE_CAR: position ({}, {}) out of bounds ({}×{})", x, y, mapWidth, mapHeight);
            return;
        }

        // 0. 读取旧位置（用于后续清理视野）
        Point oldPos = blackboard.getCarPosition(carId);

        log.info("Moving car {} from ({}, {}) to ({}, {})",
                carId,
                oldPos != null ? oldPos.getX() : -1,
                oldPos != null ? oldPos.getY() : -1,
                x, y);

        // 1. 清除当前目标和路径
        blackboard.clearCarTarget(carId);
        blackboard.clearCarRouteList(carId);

        // 2. 设置新位置
        blackboard.setCarPosition(carId, x, y);

        // 3. 视野处理
        long currentTick = tickCount.get();
        if (currentTick == 0) {
            // 仿真尚未启动：完全重算视野，严格按当前所有小车位置照亮
            blackboard.recalculateFullVision(mapWidth, mapHeight);
            log.info("Simulation not started yet, recalculated full vision after moving {} to ({}, {})",
                    carId, x, y);
        } else {
            // 仿真已启动：仅增量点亮新位置（保留所有历史探索数据）
            blackboard.illuminateArea(x, y, mapWidth, mapHeight);
        }

        // 5. 设置状态为 IDLE，让调度器重新分配目标和路径
        blackboard.setCarStatus(carId, CarStatus.IDLE);

        log.info("Car {} moved to ({}, {}), target/route cleared, status=IDLE", carId, x, y);

        // 6. 广播视图更新
        broadcastViewUpdate(currentTick);
    }

    /**
     * 启动新的 Car JVM 进程
     * 使用与一键启动.bat 相同的 classpath 和启动参数
     */
    private void spawnCarProcess(String carId) throws IOException {
        String javaHome = System.getProperty("java.home");
        String javaExe = javaHome + "\\bin\\java.exe";
        String userDir = System.getProperty("user.dir");

        // 构建 classpath（与一键启动.bat 一致）
        String m2Repo = System.getProperty("user.home") + "\\.m2\\repository";
        String commonJar = userDir + "\\common\\target\\common-1.0-SNAPSHOT.jar";
        String carJar = userDir + "\\car\\target\\car-1.0-SNAPSHOT.jar";
        String[] depJars = {
            m2Repo + "\\redis\\clients\\jedis\\5.0.2\\jedis-5.0.2.jar",
            m2Repo + "\\com\\rabbitmq\\amqp-client\\5.18.0\\amqp-client-5.18.0.jar",
            m2Repo + "\\com\\alibaba\\fastjson2\\fastjson2\\2.0.47\\fastjson2-2.0.47.jar",
            m2Repo + "\\org\\slf4j\\slf4j-api\\2.0.9\\slf4j-api-2.0.9.jar",
            m2Repo + "\\ch\\qos\\logback\\logback-classic\\1.5.6\\logback-classic-1.5.6.jar",
            m2Repo + "\\ch\\qos\\logback\\logback-core\\1.5.6\\logback-core-1.5.6.jar",
            m2Repo + "\\org\\apache\\commons\\commons-pool2\\2.12.0\\commons-pool2-2.12.0.jar",
        };

        StringBuilder cp = new StringBuilder();
        cp.append(commonJar);
        for (String dep : depJars) {
            cp.append(";").append(dep);
        }
        cp.append(";").append(carJar);

        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-cp", cp.toString(),
            "inspection.car.CarMain", carId
        );
        pb.directory(new java.io.File(userDir));
        pb.redirectErrorStream(true);
        pb.inheritIO();  // 输出到当前控制台

        Process process = pb.start();
        // 记录进程引用，以便运行时删除
        carProcesses.put(carId, process);
        log.info("Spawned Car process for {} (PID: ~{})", carId, process.pid());
    }

    // ==================== Web命令转发 ====================

    /**
     * 转发Web命令到TaskConfigurator
     */
    private void forwardWebCommand(String cmd, JSONObject data) {
        String forwardCmd = switch (cmd) {
            case CMD_SET_CONFIG -> CMD_FORWARD_CONFIG;
            case CMD_SET_MAP_EDIT -> CMD_FORWARD_MAP_EDIT;
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
