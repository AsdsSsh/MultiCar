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

/**
 * Car 小车知识源 — 5状态状态机
 *
 * 状态变迁：
 *   [*] → IDLE : 注册到黑板
 *   IDLE → WAITING_ROUTE : Controller调度（Controller写Status）
 *   WAITING_ROUTE → READY : Navigator写入RouteList后（Controller写Status）
 *   READY → MOVING : 收到TICK_MOVE，下一步无障碍
 *   MOVING → READY : 移动完成，路径仍有下一步
 *   MOVING → IDLE : 路径走完，通知ROUTE_DONE
 *   MOVING → BLOCKED : 下一步有障碍物，通知BLOCKED
 *   BLOCKED → IDLE : 等待≥2节拍，Controller清空路径+目标（Controller写Status）
 *
 * 架构约束（v3.3）：
 *   - Car自主写入执行型变迁：MOVING/READY/IDLE/BLOCKED
 *   - Controller写入调度型变迁：WAITING_ROUTE/READY/IDLE
 *   - Car只订阅Car_CarID队列，只发布到ControllerCmd队列
 *   - 不直接与其他知识源通信
 */
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

    /**
     * 启动小车
     */
    public void start() {
        running = true;
        log.info("Car {} starting...", carId);

        // 注册到黑板
        blackboard.setCarStatus(carId, CarStatus.IDLE);
        log.info("Car {} registered, status=IDLE", carId);

        // 订阅专属队列
        try {
            messageBus.subscribe(getCarQueueName(carId), this::handleMessage);
        } catch (Exception e) {
            log.error("Car {} failed to subscribe: {}", carId, e.getMessage());
        }

        log.info("Car {} ready, waiting for commands...", carId);
    }

    /** 当前节拍号（从TICK_MOVE消息中获取） */
    private volatile long currentTick = 0;

    /**
     * 消息处理入口
     */
    private void handleMessage(String cmd, JSONObject data, long timestamp) {
        if (!running) return;

        log.debug("Car {} received: cmd={}", carId, cmd);

        switch (cmd) {
            case CMD_TICK_MOVE -> {
                // 从消息中获取当前tick号
                if (data != null) {
                    currentTick = data.getLongValue("tick", currentTick);
                }
                handleTickMove();
            }
            default -> log.warn("Car {} unknown command: {}", carId, cmd);
        }
    }

    /**
     * 处理 TICK_MOVE — 执行一步移动
     *
     * 完整操作序列（v3.3）：
     *   1. 检查 Status == READY
     *   2. peekNextRouteStep → 获取下一步位置
     *   3. 路径为空 → handleRouteDone()
     *   4. 检查下一步 mapBlock → 有障碍 → handleBlocked()
     *   5. 加锁 lock:CarID
     *   6. Status → MOVING
     *   7. popNextRouteStep → 从路径移除
     *   8. 清除旧位置 mapBlock 动态障碍
     *   9. 更新 Car:Position
     *   10. 设置新位置 mapBlock 动态障碍
     *   11. illuminateArea → 点亮3×3
     *   12. incrementCarSteps → 递增步数
     *   13. 路径仍有下一步 → Status=READY，通知MOVED
     *   14. 路径走完 → handleRouteDone()
     *   15. 释放锁
     */
    private void handleTickMove() {
        // 1. 检查当前状态
        CarStatus currentStatus = blackboard.getCarStatus(carId);
        if (currentStatus != CarStatus.READY) {
            log.debug("Car {} not READY (current={}), ignoring TICK_MOVE", carId, currentStatus);
            return;
        }

        // 2. 获取下一步
        Point nextStep = blackboard.peekNextRouteStep(carId);
        if (nextStep == null) {
            // 路径为空，路径走完
            handleRouteDone();
            return;
        }

        // 3. 检查障碍物
        int mapWidth = blackboard.getMapWidth();
        if (blackboard.getMapBlockBit(nextStep.getX(), nextStep.getY(), mapWidth)) {
            // 有障碍物
            handleBlocked(nextStep);
            return;
        }

        // 4-15. 加锁执行移动
        String lockKey = getLockKey(carId);
        String requestId = distributedLock.tryLockWithId(lockKey, LOCK_EXPIRE_MS);

        if (requestId == null) {
            log.warn("Car {} failed to acquire lock, skipping tick", carId);
            return;
        }

        try {
            executeMove(nextStep, mapWidth);
        } finally {
            distributedLock.unlock(lockKey, requestId);
        }
    }

    /**
     * 执行移动（在锁保护下）
     */
    private void executeMove(Point nextStep, int mapWidth) {
        int mapHeight = blackboard.getMapHeight();

        // 6. Status → MOVING
        blackboard.setCarStatus(carId, CarStatus.MOVING);

        // 7. 从路径弹出下一步
        Point popped = blackboard.popNextRouteStep(carId);
        if (popped == null) {
            // 路径在读取和弹出之间被清空
            blackboard.setCarStatus(carId, CarStatus.IDLE);
            notifyController(CMD_ROUTE_DONE, null);
            return;
        }

        // 8. 更新位置（不再操作 mapBlock，mapBlock 只包含静态障碍）
        blackboard.setCarPosition(carId, popped.getX(), popped.getY());

        // 9. 点亮3×3视野
        blackboard.illuminateArea(popped.getX(), popped.getY(), mapWidth, mapHeight);

        // 10. 递增步数
        blackboard.incrementCarSteps(carId);

        // 11-12. 判断路径是否走完
        Point remainingStep = blackboard.peekNextRouteStep(carId);
        if (remainingStep == null) {
            // 路径走完
            handleRouteDoneLocked(popped);
        } else {
            // 路径仍有下一步 → 回到READY
            blackboard.setCarStatus(carId, CarStatus.READY);
            notifyController(CMD_MOVED, popped);
        }
    }

    /**
     * 处理路径走完（锁内）
     */
    private void handleRouteDoneLocked(Point currentPos) {
        blackboard.clearCarRouteList(carId);
        blackboard.clearCarTarget(carId);
        blackboard.setCarStatus(carId, CarStatus.IDLE);
        notifyController(CMD_ROUTE_DONE, currentPos);
        log.info("Car {} route done at ({}, {}), status -> IDLE",
                carId, currentPos.getX(), currentPos.getY());
    }

    /**
     * 处理路径走完（锁外调用）
     */
    private void handleRouteDone() {
        Point currentPos = blackboard.getCarPosition(carId);
        blackboard.clearCarRouteList(carId);
        blackboard.clearCarTarget(carId);
        blackboard.setCarStatus(carId, CarStatus.IDLE);
        notifyController(CMD_ROUTE_DONE, currentPos);
        log.info("Car {} route done, status -> IDLE", carId);
    }

    /**
     * 处理受阻（v3.3简化版）
     *
     * Car不做重规划尝试，仅：
     *   1. 清空RouteList
     *   2. 更新Status=BLOCKED
     *   3. 记录blockedTick
     *   4. 通知Controller BLOCKED
     *   等待Controller超时后转IDLE
     */
    private void handleBlocked(Point blockedPos) {
        log.warn("Car {} blocked at next step ({}, {})", carId, blockedPos.getX(), blockedPos.getY());

        // 1. 清空路径
        blackboard.clearCarRouteList(carId);

        // 2. 更新状态
        blackboard.setCarStatus(carId, CarStatus.BLOCKED);

        // 3. 记录受阻节拍号（使用Controller发来的tick号）
        blackboard.setCarBlockedTick(carId, currentTick);

        // 4. 通知Controller
        Point currentPos = blackboard.getCarPosition(carId);
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        if (currentPos != null) {
            data.put("x", currentPos.getX());
            data.put("y", currentPos.getY());
        }
        data.put("blockedX", blockedPos.getX());
        data.put("blockedY", blockedPos.getY());
        messageBus.publish(QUEUE_CONTROLLER_CMD, CMD_BLOCKED, data);
    }

    // ==================== 通知Controller ====================

    /**
     * 向Controller发送通知消息
     */
    private void notifyController(String cmd, Point position) {
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        if (position != null) {
            data.put("x", position.getX());
            data.put("y", position.getY());
        }
        messageBus.publish(QUEUE_CONTROLLER_CMD, cmd, data);
    }

    // ==================== 控制接口 ====================

    public String getCarId() {
        return carId;
    }

    public String getDisplayId() {
        return displayId;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        log.info("Car {} stopped", carId);
    }
}
