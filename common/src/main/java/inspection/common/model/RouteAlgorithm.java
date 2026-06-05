package inspection.common.model;

/**
 * 路径算法枚举
 */
public enum RouteAlgorithm {
    BFS("BFS", "广度优先搜索"),
    A_STAR("A_STAR", "A*启发式搜索");

    private final String code;
    private final String displayName;

    RouteAlgorithm(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static RouteAlgorithm fromString(String str) {
        if (str == null || str.isEmpty()) return BFS;
        try {
            return valueOf(str);
        } catch (IllegalArgumentException e) {
            return BFS;
        }
    }
}
