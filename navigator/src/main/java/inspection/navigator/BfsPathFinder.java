package inspection.navigator;

import inspection.common.model.Point;

import java.util.*;

/**
 * BFS 路径搜索 —— 保证最短路径（步数最少）。
 * 时间复杂度 O(W×H)，空间复杂度 O(W×H)。
 * 30×30 = 900 节点，完全可接受。
 */
public class BfsPathFinder implements PathFinder {

    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @Override
    public List<Point> findPath(Point start, Point target, int mapWidth, int mapHeight, boolean[] mapBlock) {
        if (start.equals(target)) {
            return Collections.emptyList();
        }

        int total = mapWidth * mapHeight;
        boolean[] visited = new boolean[total];
        Point[] parent = new Point[total];

        Queue<Point> queue = new LinkedList<>();
        queue.add(start);
        visited[start.toBitmapOffset(mapWidth)] = true;

        while (!queue.isEmpty()) {
            Point current = queue.poll();

            for (int[] dir : DIRECTIONS) {
                int nx = current.getX() + dir[0];
                int ny = current.getY() + dir[1];

                if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) continue;

                int offset = ny * mapWidth + nx;
                if (visited[offset]) continue;
                if (mapBlock != null && offset < mapBlock.length && mapBlock[offset]) continue;

                visited[offset] = true;
                Point next = new Point(nx, ny);
                parent[offset] = current;

                if (nx == target.getX() && ny == target.getY()) {
                    return reconstructPath(parent, start, target, mapWidth);
                }

                queue.add(next);
            }
        }

        return Collections.emptyList();
    }

    /** 回溯 parent 构建路径（不含起点 start） */
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
}
