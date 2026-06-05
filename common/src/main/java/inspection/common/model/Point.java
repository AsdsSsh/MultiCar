package inspection.common.model;

import com.alibaba.fastjson2.annotation.JSONField;

import java.util.Objects;

/**
 * 二维坐标点 (x, y)
 * 用于表示地图上的位置
 */
public class Point {
    @JSONField(name = "x")
    private int x;
    
    @JSONField(name = "y")
    private int y;

    public Point() {}

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    /**
     * 计算到另一个点的欧几里得距离
     */
    public double distanceTo(Point other) {
        return Math.sqrt(Math.pow(this.x - other.x, 2) + Math.pow(this.y - other.y, 2));
    }

    /**
     * 计算到另一个点的曼哈顿距离
     */
    public int manhattanDistance(Point other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    /**
     * 计算到另一个点的曼哈顿距离（静态方法）
     */
    public static int manhattanDistance(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Point point = (Point) o;
        return x == point.x && y == point.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return "{\"x\":" + x + ",\"y\":" + y + "}";
    }

    /**
     * 从字符串解析，格式: {"x":5,"y":3}
     */
    public static Point fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        str = str.trim();
        if (str.startsWith("{")) {
            try {
                return com.alibaba.fastjson2.JSON.parseObject(str, Point.class);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 计算 Redis Bitmap 的 offset
     * offset = y * mapWidth + x
     */
    public int toBitmapOffset(int mapWidth) {
        return y * mapWidth + x;
    }

    /**
     * 从 Bitmap offset 还原坐标
     */
    public static Point fromBitmapOffset(int offset, int mapWidth) {
        return new Point(offset % mapWidth, offset / mapWidth);
    }
}
