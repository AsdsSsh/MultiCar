package inspection.common.util;

import inspection.common.model.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统常量
 */
public final class Constants {

    private Constants() {}

    // ===== 默认配置 =====
    public static final int DEFAULT_MAP_WIDTH = 30;
    public static final int DEFAULT_MAP_HEIGHT = 30;
    public static final int DEFAULT_CAR_COUNT = 5;
    public static final double DEFAULT_OBSTACLE_DENSITY = 0.1;
    public static final long DEFAULT_TICK_INTERVAL_MS = 500;
    public static final String DEFAULT_ALGORITHM = "BFS";

    // ===== 视野范围 =====
    public static final int VISION_RANGE = 1;  // 3x3 = 2*VISION_RANGE+1

    // ===== 探索完成阈值 =====
    public static final double EXPLORATION_COMPLETE_THRESHOLD = 99.9;

    // ===== 受阻超时节拍数 =====
    public static final int BLOCKED_TIMEOUT_TICKS = 2;

    // ===== 分布式锁过期时间（毫秒） =====
    public static final long LOCK_EXPIRE_MS = 5000;

    // ===== 目标分配距离规则 =====
    public static final int MIN_TARGET_DISTANCE = 10;

    // ===== MQ 队列名 =====
    public static final String QUEUE_CONTROLLER_CMD = "ControllerCmd";
    public static final String QUEUE_NAVIGATOR_CMD = "NavigatorCmd";
    public static final String QUEUE_TARGET_PLANNER_CMD = "TargetPlannerCmd";
    public static final String QUEUE_TASK_CONFIG_CMD = "TaskConfigCmd";
    public static final String EXCHANGE_UPDATE_VIEW = "UpdateView";

    // ===== MQ 命令名 =====
    public static final String CMD_SET_CONFIG = "SET_CONFIG";
    public static final String CMD_SET_MAP_EDIT = "SET_MAP_EDIT";
    public static final String CMD_RESET = "RESET";
    public static final String CMD_FORWARD_CONFIG = "FORWARD_CONFIG";
    public static final String CMD_FORWARD_MAP_EDIT = "FORWARD_MAP_EDIT";
    public static final String CMD_FORWARD_RESET = "FORWARD_RESET";
    public static final String CMD_TASK_READY = "TASK_READY";
    public static final String CMD_ASSIGN_TARGET = "ASSIGN_TARGET";
    public static final String CMD_RESET_BATCH = "RESET_BATCH";
    public static final String CMD_TARGET_ASSIGNED = "TARGET_ASSIGNED";
    public static final String CMD_PLAN_ROUTE = "PLAN_ROUTE";
    public static final String CMD_ROUTE_PLANNED = "ROUTE_PLANNED";
    public static final String CMD_TICK_MOVE = "TICK_MOVE";
    public static final String CMD_MOVED = "MOVED";
    public static final String CMD_BLOCKED = "BLOCKED";
    public static final String CMD_ROUTE_DONE = "ROUTE_DONE";
    public static final String CMD_REFRESH_ALL = "REFRESH_ALL";
    public static final String CMD_PAUSE = "PAUSE";
    public static final String CMD_RESUME = "RESUME";
    public static final String CMD_STEP_ONCE = "STEP_ONCE";
    public static final String CMD_ADD_CAR = "ADD_CAR";
    public static final String CMD_DELETE_CAR = "DELETE_CAR";
    public static final String CMD_MOVE_CAR = "MOVE_CAR";

    public static String getCarQueueName(String carId) {
        return "Car_" + carId;
    }

    // ===== Redis Key 前缀 =====
    public static final String REDIS_KEY_MAP_VIEW = "mapView";
    public static final String REDIS_KEY_MAP_BLOCK = "mapBlock";
    public static final String REDIS_KEY_TASK_CONFIG = "TaskConfig";
    public static final String REDIS_KEY_SIMULATION_PAUSED = "simulation:paused";
    public static final String REDIS_KEY_LOCK_PREFIX = "lock:";

    public static String getCarPositionKey(String carId) {
        return carId + ":Position";
    }

    public static String getCarTargetKey(String carId) {
        return carId + ":Target";
    }

    public static String getCarRouteListKey(String carId) {
        return carId + ":RouteList";
    }

    public static String getCarStatusKey(String carId) {
        return carId + ":Status";
    }

    public static String getCarStepsKey(String carId) {
        return carId + ":Steps";
    }

    public static String getCarBlockedTickKey(String carId) {
        return carId + ":BlockedTick";
    }

    public static String getLockKey(String carId) {
        return REDIS_KEY_LOCK_PREFIX + carId;
    }

    // ===== 生成小车ID列表 =====
    public static List<String> generateCarIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(String.format("Car%03d", i));
        }
        return ids;
    }

    // ===== 小车初始位置（4角+中心） =====
    public static Point getCarInitialPosition(int index, int mapWidth, int mapHeight) {
        return switch (index) {
            case 0 -> new Point(1, 1);                          // 001: 左上角
            case 1 -> new Point(mapWidth - 2, 1);               // 002: 右上角
            case 2 -> new Point(1, mapHeight - 2);              // 003: 左下角
            case 3 -> new Point(mapWidth - 2, mapHeight - 2);   // 004: 右下角
            case 4 -> new Point(mapWidth / 2, mapHeight / 2);   // 005: 中心
            default -> new Point(mapWidth / 2, mapHeight / 2);
        };
    }

    // ===== 小车颜色（前端显示） =====
    public static String getCarColor(String carId) {
        return switch (carId) {
            case "Car001" -> "#00E676";
            case "Car002" -> "#FFC107";
            case "Car003" -> "#FF9800";
            case "Car004" -> "#00BCD4";
            case "Car005" -> "#E91E63";
            default -> "#9E9E9E";
        };
    }
}
