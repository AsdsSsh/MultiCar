package inspection.common.blackboard;

import inspection.common.model.CarStatus;
import inspection.common.model.Point;
import inspection.common.util.Constants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
                config.getTimeoutMs(),
                config.getPassword() != null && !config.getPassword().isEmpty() ? config.getPassword() : null);
    }

    public BlackboardClient(String host, int port) {
        this(new BlackboardConfig(host, port));
    }

    public BlackboardClient() {
        this(new BlackboardConfig());
    }

    public Jedis getJedis() { return jedisPool.getResource(); }
    public JedisPool getPool() { return jedisPool; }
    public JedisPool getJedisPool() { return jedisPool; }

    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }

    /** 静态工具方法 */
    public static String getDisplayId(String carId) {
        if (carId.startsWith("Car")) return "Car#" + carId.substring(3);
        return carId;
    }

    // ==================== mapView ====================

    public void setMapViewBit(String sessionId, int x, int y, int mapWidth, boolean explored) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setbit(Constants.getMapViewKey(sessionId), offset, explored);
        }
    }

    public boolean getMapViewBit(String sessionId, int x, int y, int mapWidth) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.getbit(Constants.getMapViewKey(sessionId), offset);
        }
    }

    public boolean[] getFullMapView(String sessionId, int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        boolean[] result = new boolean[total];
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] bytes = jedis.get(Constants.getMapViewKey(sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (bytes == null || bytes.length == 0) return result;
            for (int i = 0; i < total; i++) {
                int byteIdx = i / 8;
                int bitIdx = 7 - (i % 8);
                if (byteIdx < bytes.length) {
                    result[i] = ((bytes[byteIdx] >> bitIdx) & 1) == 1;
                }
            }
        }
        return result;
    }

    public void setMapViewBitsBatch(String sessionId, List<Point> points, int mapWidth) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Point p : points) {
                pipeline.setbit(Constants.getMapViewKey(sessionId), p.toBitmapOffset(mapWidth), true);
            }
            pipeline.sync();
        }
    }

    // ==================== mapBlock ====================

    public void setMapBlockBit(String sessionId, int x, int y, int mapWidth, boolean blocked) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setbit(Constants.getMapBlockKey(sessionId), offset, blocked);
        }
    }

    public boolean getMapBlockBit(String sessionId, int x, int y, int mapWidth) {
        int offset = y * mapWidth + x;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.getbit(Constants.getMapBlockKey(sessionId), offset);
        }
    }

    public boolean[] getFullMapBlock(String sessionId, int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        boolean[] result = new boolean[total];
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] bytes = jedis.get(Constants.getMapBlockKey(sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (bytes == null || bytes.length == 0) return result;
            for (int i = 0; i < total; i++) {
                int byteIdx = i / 8;
                int bitIdx = 7 - (i % 8);
                if (byteIdx < bytes.length) {
                    result[i] = ((bytes[byteIdx] >> bitIdx) & 1) == 1;
                }
            }
        }
        return result;
    }

    public void setMapBlockBitsBatch(String sessionId, List<Point> points, int mapWidth) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Point p : points) {
                pipeline.setbit(Constants.getMapBlockKey(sessionId), p.toBitmapOffset(mapWidth), true);
            }
            pipeline.sync();
        }
    }

    // ==================== Car 位置 ====================

    public Point getCarPosition(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarPositionKey(sessionId, carId));
            return Point.fromString(val);
        }
    }

    public void setCarPosition(String sessionId, String carId, Point point) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarPositionKey(sessionId, carId), point.toString());
        }
    }

    public void setCarPosition(String sessionId, String carId, int x, int y) {
        setCarPosition(sessionId, carId, new Point(x, y));
    }

    // ==================== Car 目标 ====================

    public Point getCarTarget(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarTargetKey(sessionId, carId));
            return Point.fromString(val);
        }
    }

    public void setCarTarget(String sessionId, String carId, Point target) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (target == null) {
                jedis.del(Constants.getCarTargetKey(sessionId, carId));
            } else {
                jedis.set(Constants.getCarTargetKey(sessionId, carId), target.toString());
            }
        }
    }

    public void clearCarTarget(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getCarTargetKey(sessionId, carId));
        }
    }

    // ==================== Car 路径 ====================

    public List<Point> getCarRouteList(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> list = jedis.lrange(Constants.getCarRouteListKey(sessionId, carId), 0, -1);
            List<Point> route = new ArrayList<>();
            for (String s : list) {
                Point p = Point.fromString(s);
                if (p != null) route.add(p);
            }
            return route;
        }
    }

    public Point peekNextRouteStep(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.lindex(Constants.getCarRouteListKey(sessionId, carId), -1);
            return Point.fromString(val);
        }
    }

    public Point popNextRouteStep(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.rpop(Constants.getCarRouteListKey(sessionId, carId));
            return Point.fromString(val);
        }
    }

    public void setCarRouteList(String sessionId, String carId, List<Point> route) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = Constants.getCarRouteListKey(sessionId, carId);
            jedis.del(key);
            if (route != null && !route.isEmpty()) {
                for (Point p : route) {
                    jedis.lpush(key, p.toString());
                }
            }
        }
    }

    public void clearCarRouteList(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getCarRouteListKey(sessionId, carId));
        }
    }

    public boolean hasRoute(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen(Constants.getCarRouteListKey(sessionId, carId)) > 0;
        }
    }

    // ==================== Car 状态 ====================

    public CarStatus getCarStatus(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarStatusKey(sessionId, carId));
            return CarStatus.fromString(val);
        }
    }

    public void setCarStatus(String sessionId, String carId, CarStatus status) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarStatusKey(sessionId, carId), status.name());
        }
    }

    public void setCarStatus(String sessionId, String carId, String status) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarStatusKey(sessionId, carId), status);
        }
    }

    // ==================== Car 步数 ====================

    public int getCarSteps(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarStepsKey(sessionId, carId));
            return val == null ? 0 : Integer.parseInt(val);
        }
    }

    public void setCarSteps(String sessionId, String carId, int steps) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarStepsKey(sessionId, carId), String.valueOf(steps));
        }
    }

    public void incrementCarSteps(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.incr(Constants.getCarStepsKey(sessionId, carId));
        }
    }

    // ==================== Car 受阻节拍号 ====================

    public long getCarBlockedTick(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getCarBlockedTickKey(sessionId, carId));
            return val == null ? -1 : Long.parseLong(val);
        }
    }

    public void setCarBlockedTick(String sessionId, String carId, long tick) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getCarBlockedTickKey(sessionId, carId), String.valueOf(tick));
        }
    }

    public void clearCarBlockedTick(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getCarBlockedTickKey(sessionId, carId));
        }
    }

    // ==================== TaskConfig ====================

    public Map<String, String> getTaskConfig(String sessionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(Constants.getTaskConfigKey(sessionId));
        }
    }

    public void setTaskConfig(String sessionId, Map<String, String> config) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(Constants.getTaskConfigKey(sessionId), config);
        }
    }

    public void setTaskConfigField(String sessionId, String field, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(Constants.getTaskConfigKey(sessionId), field, value);
        }
    }

    public String getTaskConfigField(String sessionId, String field) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hget(Constants.getTaskConfigKey(sessionId), field);
        }
    }

    public boolean isTaskActive(String sessionId) {
        String val = getTaskConfigField(sessionId, "taskActive");
        return "true".equals(val);
    }

    // ==================== 暂停状态 ====================

    public void setPaused(String sessionId, boolean paused) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.getSimulationPausedKey(sessionId), paused ? "1" : "0");
        }
    }

    public boolean isPaused(String sessionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.getSimulationPausedKey(sessionId));
            return "1".equals(val);
        }
    }

    public int getMapWidth(String sessionId) {
        String val = getTaskConfigField(sessionId, "mapWidth");
        return val == null ? Constants.DEFAULT_MAP_WIDTH : Integer.parseInt(val);
    }

    public int getMapHeight(String sessionId) {
        String val = getTaskConfigField(sessionId, "mapHeight");
        return val == null ? Constants.DEFAULT_MAP_HEIGHT : Integer.parseInt(val);
    }

    public int getCarCount(String sessionId) {
        String val = getTaskConfigField(sessionId, "carCount");
        return val == null ? Constants.DEFAULT_CAR_COUNT : Integer.parseInt(val);
    }

    public String getAlgorithm(String sessionId) {
        String val = getTaskConfigField(sessionId, "algorithm");
        return val == null ? Constants.DEFAULT_ALGORITHM : val;
    }

    // ==================== 探索率 ====================

    public double getExploredPercent(String sessionId, int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        try (Jedis jedis = jedisPool.getResource()) {
            long explored = jedis.bitcount(Constants.getMapViewKey(sessionId));
            return (double) explored / total * 100.0;
        }
    }

    // ==================== 所有 Car 快照 ====================

    public List<String> getAllCarIds(String sessionId) {
        List<String> ids = new ArrayList<>();
        String prefix = Constants.getSessionPrefix(sessionId);
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(prefix + "Car*:Status");
            if (keys != null) {
                for (String key : keys) {
                    String carId = key.substring(prefix.length(), key.indexOf(":Status"));
                    ids.add(carId);
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    public int getMaxCarNumber(String sessionId) {
        int maxNum = 0;
        String prefix = Constants.getSessionPrefix(sessionId);
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(prefix + "Car*:Status");
            if (keys != null) {
                for (String key : keys) {
                    String carId = key.substring(prefix.length(), key.indexOf(":Status"));
                    try {
                        int num = Integer.parseInt(carId.substring(3));
                        if (num > maxNum) maxNum = num;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return maxNum;
    }

    public int getActualCarCount(String sessionId) {
        String prefix = Constants.getSessionPrefix(sessionId);
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(prefix + "Car*:Status");
            return keys != null ? keys.size() : 0;
        }
    }

    public Map<String, Point> getAllCarPositions(String sessionId) {
        Map<String, Point> result = new HashMap<>();
        for (String carId : getAllCarIds(sessionId)) {
            Point p = getCarPosition(sessionId, carId);
            if (p != null) result.put(carId, p);
        }
        return result;
    }

    public Map<String, CarStatus> getAllCarStatuses(String sessionId) {
        Map<String, CarStatus> result = new HashMap<>();
        for (String carId : getAllCarIds(sessionId)) {
            CarStatus s = getCarStatus(sessionId, carId);
            if (s != null) result.put(carId, s);
        }
        return result;
    }

    public Map<String, Integer> getAllCarSteps(String sessionId) {
        Map<String, Integer> result = new HashMap<>();
        for (String carId : getAllCarIds(sessionId)) {
            result.put(carId, getCarSteps(sessionId, carId));
        }
        return result;
    }

    // ==================== 小车增删 ====================

    public void deleteCarData(String sessionId, String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String[] keys = {
                Constants.getCarPositionKey(sessionId, carId),
                Constants.getCarTargetKey(sessionId, carId),
                Constants.getCarRouteListKey(sessionId, carId),
                Constants.getCarStatusKey(sessionId, carId),
                Constants.getCarStepsKey(sessionId, carId),
                Constants.getCarBlockedTickKey(sessionId, carId),
                Constants.getLockKey(sessionId, carId)
            };
            jedis.del(keys);
            logger.info("Deleted all Redis data for {} in session {}", carId, sessionId);
        }
    }

    // ==================== 视野照明 ====================

    public void illuminateArea(String sessionId, int x, int y, int mapWidth, int mapHeight) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        pipeline.setbit(Constants.getMapViewKey(sessionId), ny * mapWidth + nx, true);
                    }
                }
            }
            pipeline.sync();
        }
    }

    public void reilluminateAllCars(String sessionId, int mapWidth, int mapHeight) {
        Map<String, Point> positions = getAllCarPositions(sessionId);
        if (positions.isEmpty()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Point p : positions.values()) {
                for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                    for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                        int nx = p.getX() + dx;
                        int ny = p.getY() + dy;
                        if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                            pipeline.setbit(Constants.getMapViewKey(sessionId), ny * mapWidth + nx, true);
                        }
                    }
                }
            }
            pipeline.sync();
        }
    }

    public void recalculateFullVision(String sessionId, int mapWidth, int mapHeight) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(Constants.getMapViewKey(sessionId));
            jedis.del(Constants.getMapViewKey(sessionId) + ":chunks:meta");
            Set<String> chunkKeys = jedis.keys(Constants.getMapViewKey(sessionId) + ":chunk:*");
            if (chunkKeys != null && !chunkKeys.isEmpty()) {
                jedis.del(chunkKeys.toArray(new String[0]));
            }
        }
        reilluminateAllCars(sessionId, mapWidth, mapHeight);
    }

    // ==================== 清空 ====================

    /** 清空指定 session 的所有数据 */
    public void clearSession(String sessionId) {
        String prefix = Constants.getSessionPrefix(sessionId);
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
            logger.info("Cleared session {}: {} keys", sessionId, keys != null ? keys.size() : 0);
        }
    }

    /** 清空全部数据 */
    public void clearAll() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
            logger.info("黑板数据已清空");
        }
    }
}
