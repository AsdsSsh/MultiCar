package inspection.navigator;

import inspection.common.model.Point;

import java.util.*;

/**
 * A* 路径搜索 —— 使用曼哈顿距离作为启发式函数。
 * 比 BFS 更快收敛到目标，适合大地图或需要性能优化的场景。
 */
public class AStarPathFinder implements PathFinder {

    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @Override
    public List<Point> findPath(Point start, Point target, int mapWidth, int mapHeight,
                                boolean[] mapBlock, boolean[] mapView) {
        if (start.equals(target)) {
            return Collections.emptyList();
        }

        int total = mapWidth * mapHeight;
        int[] gScore = new int[total];
        Arrays.fill(gScore, Integer.MAX_VALUE);
        int startOffset = start.toBitmapOffset(mapWidth);
        gScore[startOffset] = 0;

        int[] fScore = new int[total];
        Arrays.fill(fScore, Integer.MAX_VALUE);
        fScore[startOffset] = heuristic(start, target);

        Point[] parent = new Point[total];

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(n -> n.f));
        openSet.add(new Node(start, fScore[startOffset]));

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            Point curPoint = current.point;
            int curOffset = curPoint.toBitmapOffset(mapWidth);

            if (curPoint.equals(target)) {
                return reconstructPath(parent, start, target, mapWidth);
            }

            for (int[] dir : DIRECTIONS) {
                int nx = curPoint.getX() + dir[0];
                int ny = curPoint.getY() + dir[1];

                if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) continue;

                int neighborOffset = ny * mapWidth + nx;

                // 视野约束：只阻挡「已探索 + 障碍物」的格子
                // 未探索区域假设可通过（小车不知道前方是障碍物）
                if (isBlocked(neighborOffset, mapBlock, mapView)) continue;

                int tentativeG = gScore[curOffset] + 1;
                if (tentativeG < gScore[neighborOffset]) {
                    parent[neighborOffset] = curPoint;
                    gScore[neighborOffset] = tentativeG;
                    Point neighbor = new Point(nx, ny);
                    int h = heuristic(neighbor, target);
                    fScore[neighborOffset] = tentativeG + h;
                    openSet.add(new Node(neighbor, fScore[neighborOffset]));
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * 判断格子是否被阻挡。
     * 已探索 + 障碍物 → 阻挡；未探索 → 可通过（小车不知道前方情况）。
     */
    private static boolean isBlocked(int offset, boolean[] mapBlock, boolean[] mapView) {
        if (mapBlock == null || offset >= mapBlock.length) return false;
        // 未探索的格子：假设无障碍，小车可以尝试进入
        if (mapView != null && offset < mapView.length && !mapView[offset]) return false;
        // 已探索的格子：根据实际障碍物判断
        return mapBlock[offset];
    }

    /** 曼哈顿距离启发式 */
    private int heuristic(Point a, Point b) {
        return a.manhattanDistance(b);
    }

    private List<Point> reconstructPath(Point[] parent, Point start, Point target, int mapWidth) {
        LinkedList<Point> path = new LinkedList<>();
        Point current = target;
        while (current != null && !current.equals(start)) {
            path.addFirst(current);
            Point prev = parent[current.toBitmapOffset(mapWidth)];
            if (prev == null) break;
            current = prev;
        }
        return path;
    }

    private record Node(Point point, int f) {}
}
