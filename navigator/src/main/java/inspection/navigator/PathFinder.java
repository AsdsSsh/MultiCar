package inspection.navigator;

import inspection.common.model.Point;

import java.util.List;

/**
 * 路径搜索器接口。
 * 支持 BFS 和 A* 两种实现，通过 RouteAlgorithm 选择。
 */
public interface PathFinder {

    /**
     * 在网格地图上搜索从 start 到 target 的最短路径。
     *
     * @param start     起点
     * @param target    终点
     * @param mapWidth  地图宽度
     * @param mapHeight 地图高度
     * @param mapBlock  障碍物数组（bitmap offset → boolean），true = 有障碍
     * @param mapView   探索视野数组（bitmap offset → boolean），true = 已探索
     *                  未探索区域假设可通过（小车不知道前方是障碍物）
     * @return 路径点列表（不含起点），无法到达则返回空列表
     */
    List<Point> findPath(Point start, Point target, int mapWidth, int mapHeight,
                         boolean[] mapBlock, boolean[] mapView);
}
