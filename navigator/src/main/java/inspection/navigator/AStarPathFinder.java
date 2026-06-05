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
    public List<Point> findPath(Point start, Point target, int mapWidth, int mapHeight, boolean[] mapBlock) {
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
                if (mapBlock != null && neighborOffset < mapBlock.length && mapBlock[neighborOffset]) continue;

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
