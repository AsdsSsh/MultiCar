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
    public static final int VISION_RANGE = 1;

    // ===== 探索完成阈值 =====
    public static final double EXPLORATION_COMPLETE_THRESHOLD = 99.9;

    // ===== 受阻超时节拍数 =====
    public static final int BLOCKED_TIMEOUT_TICKS = 2;

    // ===== 分布式锁过期时间（毫秒） =====
    public static final long LOCK_EXPIRE_MS = 5000;

    // ===== 目标分配距离规则 =====
    public static final int MIN_TARGET_DISTANCE = 10;

    // ===== Session 命名空间 =====
    public static String getSessionPrefix(String sessionId) {
        return "session:" + sessionId + ":";
    }

    // ===== MQ 队列名（共享，不随 session 变化） =====
    public static final String QUEUE_CONTROLLER_CMD = "ControllerCmd";
    public static final String QUEUE_NAVIGATOR_CMD = "NavigatorCmd";
    public static final String QUEUE_TARGET_PLANNER_CMD = "TargetPlannerCmd";
    public static final String QUEUE_TASK_CONFIG_CMD = "TaskConfigCmd";
    public static final String QUEUE_CAR_POOL = "CarPool";

    /** 每 session 一个 Fanout 交换器 */
    public static String getSessionFanoutExchange(String sessionId) {
        return "UpdateView_" + sessionId;
    }

    /** 每 session 一个刷新队列 */
    public static String getSessionRefreshQueue(String sessionId) {
        return "WSB_Refresh_" + sessionId;
    }

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
    public static final String CMD_STOP = "STOP";
    public static final String CMD_RESUME = "RESUME";
    public static final String CMD_STEP_ONCE = "STEP_ONCE";
    public static final String CMD_ADD_CAR = "ADD_CAR";
    public static final String CMD_DELETE_CAR = "DELETE_CAR";
    public static final String CMD_MOVE_CAR = "MOVE_CAR";

    /** 小车队列（所有 session 共用，通过消息中的 sessionId 区分） */
    public static String getCarQueueName(String carId) {
        return "Car_" + carId;
    }

    // ===== Redis Key（带 session 前缀） =====
    public static final String REDIS_KEY_LOCK_PREFIX = "lock:";

    public static String getMapViewKey(String sessionId) {
        return getSessionPrefix(sessionId) + "mapView";
    }

    public static String getMapBlockKey(String sessionId) {
        return getSessionPrefix(sessionId) + "mapBlock";
    }

    public static String getTaskConfigKey(String sessionId) {
        return getSessionPrefix(sessionId) + "TaskConfig";
    }

    public static String getSimulationPausedKey(String sessionId) {
        return getSessionPrefix(sessionId) + "simulation:paused";
    }

    public static String getCarPositionKey(String sessionId, String carId) {
        return getSessionPrefix(sessionId) + carId + ":Position";
    }

    public static String getCarTargetKey(String sessionId, String carId) {
        return getSessionPrefix(sessionId) + carId + ":Target";
    }

    public static String getCarRouteListKey(String sessionId, String carId) {
        return getSessionPrefix(sessionId) + carId + ":RouteList";
    }

    public static String getCarStatusKey(String sessionId, String carId) {
        return getSessionPrefix(sessionId) + carId + ":Status";
    }

    public static String getCarStepsKey(String sessionId, String carId) {
        return getSessionPrefix(sessionId) + carId + ":Steps";
    }

    public static String getCarBlockedTickKey(String sessionId, String carId) {
        return getSessionPrefix(sessionId) + carId + ":BlockedTick";
    }

    public static String getLockKey(String sessionId, String carId) {
        return REDIS_KEY_LOCK_PREFIX + sessionId + ":" + carId;
    }

    // ===== 生成小车ID列表 =====
    public static List<String> generateCarIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(String.format("Car%03d", i));
        }
        return ids;
    }

    // ===== 小车初始位置（4角+中心，超过5辆自动散开） =====
    public static Point getCarInitialPosition(int index, int mapWidth, int mapHeight) {
        return switch (index) {
            case 0 -> new Point(1, 1);
            case 1 -> new Point(mapWidth - 2, 1);
            case 2 -> new Point(1, mapHeight - 2);
            case 3 -> new Point(mapWidth - 2, mapHeight - 2);
            case 4 -> new Point(mapWidth / 2, mapHeight / 2);
            default -> {
                // 超过5辆：网格散开，避免堆叠
                int n = index - 5;
                int cols = 4;
                int margin = 3;
                int usableW = mapWidth - 2 * margin;
                int usableH = mapHeight - 2 * margin;
                int row = n / cols;
                int col = n % cols;
                int x = margin + (usableW * (col + 1)) / (cols + 1);
                int y = margin + Math.min((usableH * (row + 1)) / (row + 2), mapHeight - margin);
                yield new Point(Math.min(x, mapWidth - 2), Math.min(y, mapHeight - 2));
            }
        };
    }

    // ===== 小车颜色 =====
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
