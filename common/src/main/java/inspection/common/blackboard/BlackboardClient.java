package inspection.common.blackboard;

import inspection.common.model.CarStatus;
import inspection.common.model.Point;
import inspection.common.util.Constants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Redis 黑板客户端
 * 封装所有 Redis CRUD 操作 + 分布式锁
 * 给 B、C、D 提供统一的 Redis 读写接口
 */
public class BlackboardClient {
    private static final Logger logger = LoggerFactory.getLogger(BlackboardClient.class);
    private final JedisPool jedisPool;

    public BlackboardClient(BlackboardConfig config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getMaxTotal());
        poolConfig.setMaxIdle(config.getMaxIdle());
        this.jedisPool = new JedisPool(poolConfig,
                config.getHost(),
                config.getPort(),
                config.getTimeoutMs());
    }

    public BlackboardClient(String host, int port) {
        this(new BlackboardConfig(host, port));
    }

    public BlackboardClient() {
        this(new BlackboardConfig());
    }

    // ==================== 连接管理 ====================

    public Jedis getJedis() {
        return jedisPool.getResource();
    }

    public JedisPool getPool() {
        return jedisPool;
    }

    /** 别名，兼容队友代码 */
    public JedisPool getJedisPool() {
        return jedisPool;
    }

    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }

    // ==================== mapView 探索视野 (Bitmap) ====================

    /**
     * 设置某个格子的探索状态
     */
    public void setMapViewBit(int x, int y, int mapWidth, boolean explored) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setbit(Constants.REDIS_KEY_MAP_VIEW, offset, explored);
        }
    }

    /**
     * 获取某个格子是否已探索
     */
    public boolean getMapViewBit(int x, int y, int mapWidth) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.getbit(Constants.REDIS_KEY_MAP_VIEW, offset);
        }
    }

    /**
     * 获取完整的探索视野 bitmap
     */
    public boolean[] getFullMapView(int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        boolean[] result = new boolean[total];
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < total; i++) {
                result[i] = jedis.getbit(Constants.REDIS_KEY_MAP_VIEW, i);
            }
        }
        return result;
    }

    /**
     * 批量设置探索状态（Pipeline优化）
     */
    public void setMapViewBitsBatch(List<Point> points, int mapWidth) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Point p : points) {
                int offset = p.toBitmapOffset(mapWidth);
                pipeline.setbit(Constants.REDIS_KEY_MAP_VIEW, offset, true);
            }
            pipeline.sync();
        }
    }

    // ==================== mapBlock 障碍物 (Bitmap) ====================

    public void setMapBlockBit(int x, int y, int mapWidth, boolean blocked) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setbit(Constants.REDIS_KEY_MAP_BLOCK, offset, blocked);
        }
    }

    public boolean getMapBlockBit(int x, int y, int mapWidth) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.getbit(Constants.REDIS_KEY_MAP_BLOCK, offset);
        }
    }

    public boolean[] getFullMapBlock(int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        boolean[] result = new boolean[total];
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < total; i++) {
                result[i] = jedis.getbit(Constants.REDIS_KEY_MAP_BLOCK, i);
            }
        }
        return result;
    }

    public void setMapBlockBitsBatch(List<Point> points, int mapWidth) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Point p : points) {
                int offset = p.toBitmapOffset(mapWidth);
                pipeline.setbit(Constants.REDIS_KEY_MAP_BLOCK, offset, true);
            }
            pipeline.sync();
        }
    }

    // ==================== Car 位置 ====================

    public Point getCarPosition(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarPositionKey(carId));
            return Point.fromString(val);
        }
    }

    public void setCarPosition(String carId, Point point) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarPositionKey(carId), point.toString());
        }
    }

    public void setCarPosition(String carId, int x, int y) {
        setCarPosition(carId, new Point(x, y));
    }

    // ==================== Car 目标 ====================

    public Point getCarTarget(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarTargetKey(carId));
            return Point.fromString(val);
        }
    }

    public void setCarTarget(String carId, Point target) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (target == null) {
                jedis.del(Constants.getCarTargetKey(carId));
            } else {
                jedis.set(Constants.getCarTargetKey(carId), target.toString());
            }
        }
    }

    public void clearCarTarget(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getCarTargetKey(carId));
        }
    }

    // ==================== Car 路径 (List) ====================

    public List<Point> getCarRouteList(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> list = jedis.lrange(Constants.getCarRouteListKey(carId), 0, -1);
            List<Point> route = new ArrayList<>();
            for (String s : list) {
                Point p = Point.fromString(s);
                if (p != null) route.add(p);
            }
            return route;
        }
    }

    /**
     * 查看下一步（不移除）
     */
    public Point peekNextRouteStep(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.lindex(Constants.getCarRouteListKey(carId), -1);
            return Point.fromString(val);
        }
    }

    /**
     * 弹出下一步（从右边弹出 = 取出并移除最后一步）
     */
    public Point popNextRouteStep(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.rpop(Constants.getCarRouteListKey(carId));
            return Point.fromString(val);
        }
    }

    public void setCarRouteList(String carId, List<Point> route) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = Constants.getCarRouteListKey(carId);
            jedis.del(key);
            if (route != null && !route.isEmpty()) {
                for (Point p : route) {
                    jedis.lpush(key, p.toString());
                }
            }
        }
    }

    public void clearCarRouteList(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getCarRouteListKey(carId));
        }
    }

    public boolean hasRoute(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen(Constants.getCarRouteListKey(carId)) > 0;
        }
    }

    // ==================== Car 状态 ====================

    public CarStatus getCarStatus(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarStatusKey(carId));
            return CarStatus.fromString(val);
        }
    }

    public void setCarStatus(String carId, CarStatus status) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarStatusKey(carId), status.name());
        }
    }

    public void setCarStatus(String carId, String status) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarStatusKey(carId), status);
        }
    }

    // ==================== Car 步数 ====================

    public int getCarSteps(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarStepsKey(carId));
            return val == null ? 0 : Integer.parseInt(val);
        }
    }

    public void setCarSteps(String carId, int steps) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarStepsKey(carId), String.valueOf(steps));
        }
    }

    public void incrementCarSteps(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.incr(Constants.getCarStepsKey(carId));
        }
    }

    // ==================== Car 受阻节拍号 ====================

    public long getCarBlockedTick(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarBlockedTickKey(carId));
            return val == null ? -1 : Long.parseLong(val);
        }
    }

    public void setCarBlockedTick(String carId, long tick) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarBlockedTickKey(carId), String.valueOf(tick));
        }
    }

    public void clearCarBlockedTick(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getCarBlockedTickKey(carId));
        }
    }

    // ==================== TaskConfig 任务配置 (Hash) ====================

    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(Constants.REDIS_KEY_TASK_CONFIG);
        }
    }

    public void setTaskConfig(Map<String, String> config) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(Constants.REDIS_KEY_TASK_CONFIG, config);
        }
    }

    public void setTaskConfigField(String field, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(Constants.REDIS_KEY_TASK_CONFIG, field, value);
        }
    }

    public String getTaskConfigField(String field) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hget(Constants.REDIS_KEY_TASK_CONFIG, field);
        }
    }

    public boolean isTaskActive() {
        String val = getTaskConfigField("taskActive");
        return "true".equals(val);
    }

    public int getMapWidth() {
        String val = getTaskConfigField("mapWidth");
        return val == null ? Constants.DEFAULT_MAP_WIDTH : Integer.parseInt(val);
    }

    public int getMapHeight() {
        String val = getTaskConfigField("mapHeight");
        return val == null ? Constants.DEFAULT_MAP_HEIGHT : Integer.parseInt(val);
    }

    public int getCarCount() {
        String val = getTaskConfigField("carCount");
        return val == null ? Constants.DEFAULT_CAR_COUNT : Integer.parseInt(val);
    }

    public String getAlgorithm() {
        String val = getTaskConfigField("algorithm");
        return val == null ? Constants.DEFAULT_ALGORITHM : val;
    }

    // ==================== 探索率计算 ====================

    public double getExploredPercent(int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        long explored = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < total; i++) {
                if (jedis.getbit(Constants.REDIS_KEY_MAP_VIEW, i)) {
                    explored++;
                }
            }
        }
        return (double) explored / total * 100.0;
    }

    // ==================== 获取所有 Car 的状态快照（给 D 用） ====================

    public List<String> getAllCarIds() {
        int carCount = getCarCount();
        return Constants.generateCarIds(carCount);
    }

    public Map<String, Point> getAllCarPositions() {
        Map<String, Point> result = new HashMap<>();
        for (String carId : getAllCarIds()) {
            Point p = getCarPosition(carId);
            if (p != null) result.put(carId, p);
        }
        return result;
    }

    public Map<String, CarStatus> getAllCarStatuses() {
        Map<String, CarStatus> result = new HashMap<>();
        for (String carId : getAllCarIds()) {
            CarStatus s = getCarStatus(carId);
            if (s != null) result.put(carId, s);
        }
        return result;
    }

    public Map<String, Integer> getAllCarSteps() {
        Map<String, Integer> result = new HashMap<>();
        for (String carId : getAllCarIds()) {
            result.put(carId, getCarSteps(carId));
        }
        return result;
    }

    // ==================== 视野照明 ====================

    /**
     * 点亮以 (x,y) 为中心的 3×3 区域
     */
    public void illuminateArea(int x, int y, int mapWidth, int mapHeight) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        int offset = ny * mapWidth + nx;
                        pipeline.setbit(Constants.REDIS_KEY_MAP_VIEW, offset, true);
                    }
                }
            }
            pipeline.sync();
        }
    }

    // ==================== Car 展示ID ====================

    /**
     * 获取小车展示ID（如 Car001 → Car#001）
     */
    public static String getDisplayId(String carId) {
        if (carId.startsWith("Car")) {
            return "Car#" + carId.substring(3);
        }
        return carId;
    }

    // ==================== 清空全部数据 ====================

    public void clearAll() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
            logger.info("黑板数据已清空");
        }
    }
}
