package inspection.common.model;

/**
 * 小车状态枚举
 * 5种状态：空闲 / 等待路径 / 就绪 / 移动中 / 受阻
 */
public enum CarStatus {
    /**
     * 空闲：无目标无路径
     */
    IDLE("空闲", "#9E9E9E"),
    
    /**
     * 等待路径：已分配目标，等待Navigator规划路径
     */
    WAITING_ROUTE("等待路径", "#2196F3"),
    
    /**
     * 就绪：有目标有路径，等待Controller发TICK_MOVE
     */
    READY("就绪", "#4CAF50"),
    
    /**
     * 移动中：正在执行移动一步
     */
    MOVING("移动中", "#FF9800"),
    
    /**
     * 受阻：下一步有障碍物，等待Controller超时处理
     */
    BLOCKED("受阻", "#F44336");

    private final String displayName;
    private final String color;

    CarStatus(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColor() {
        return color;
    }

    public static CarStatus fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            return valueOf(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
