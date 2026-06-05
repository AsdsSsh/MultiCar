package inspection.common.messaging;

/**
 * 消息命令类型常量
 * 统一定义所有 MQ 消息中的 cmd 字段值
 */
public final class MessageType {

    private MessageType() {}

    // ===== TaskConfigurator → Controller =====
    public static final String TASK_READY = "TASK_READY";

    // ===== Controller → TaskConfigurator =====
    public static final String FORWARD_CONFIG = "FORWARD_CONFIG";
    public static final String FORWARD_RESET = "FORWARD_RESET";

    // ===== Controller → Car =====
    public static final String TICK_MOVE = "TICK_MOVE";

    // ===== Car → Controller =====
    public static final String MOVED = "MOVED";
    public static final String BLOCKED = "BLOCKED";
    public static final String ROUTE_DONE = "ROUTE_DONE";

    // ===== Controller → Navigator =====
    public static final String PLAN_ROUTE = "PLAN_ROUTE";

    // ===== Navigator → Controller =====
    public static final String ROUTE_PLANNED = "ROUTE_PLANNED";

    // ===== Controller → TargetPlanner =====
    public static final String ASSIGN_TARGET = "ASSIGN_TARGET";
    public static final String RESET_BATCH = "RESET_BATCH";

    // ===== TargetPlanner → Controller =====
    public static final String TARGET_ASSIGNED = "TARGET_ASSIGNED";

    // ===== Controller → Display (Fanout) =====
    public static final String REFRESH_ALL = "REFRESH_ALL";

    // ===== Display (WSB) → Controller =====
    public static final String SET_CONFIG = "SET_CONFIG";
    public static final String RESET = "RESET";
}
