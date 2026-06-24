package inspection.common.blackboard;

import inspection.common.model.CarStatus;
import inspection.common.model.Point;
import inspection.common.util.Constants;
import inspection.common.util.MapCompression;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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
     * 获取完整的探索视野 bitmap（一次 GET 批量读取，避免逐位网络往返）
     */
    public boolean[] getFullMapView(int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        boolean[] result = new boolean[total];
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] bytes = jedis.get(Constants.REDIS_KEY_MAP_VIEW.getBytes(StandardCharsets.UTF_8));
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

    /**
     * 获取完整的障碍物 bitmap（一次 GET 批量读取，避免逐位网络往返）
     */
    public boolean[] getFullMapBlock(int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        boolean[] result = new boolean[total];
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] bytes = jedis.get(Constants.REDIS_KEY_MAP_BLOCK.getBytes(StandardCharsets.UTF_8));
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

    // ==================== 仿真暂停状态 ====================

    public void setPaused(boolean paused) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(Constants.REDIS_KEY_SIMULATION_PAUSED, paused ? "1" : "0");
        }
    }

    public boolean isPaused() {
        try (Jedis jedis = jedisPool.getResource()) {
            String val = jedis.get(Constants.REDIS_KEY_SIMULATION_PAUSED);
            return "1".equals(val);
        }
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

    /**
     * 获取探索百分比（使用 Redis 原生 BITCOUNT，服务端直接统计）
     */
    public double getExploredPercent(int mapWidth, int mapHeight) {
        int total = mapWidth * mapHeight;
        try (Jedis jedis = jedisPool.getResource()) {
            long explored = jedis.bitcount(Constants.REDIS_KEY_MAP_VIEW);
            return (double) explored / total * 100.0;
        }
    }

    // ==================== 获取所有 Car 的状态快照（给 D 用） ====================

    /**
     * 获取所有小车的 ID 列表（扫描 Redis 中实际存在的 Car*:Status 键）
     * 区别于 getCarCount() 读取配置值，此方法确保返回的 ID 都真实存在
     */
    public List<String> getAllCarIds() {
        List<String> ids = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys("Car*:Status");
            if (keys != null) {
                for (String key : keys) {
                    String carId = key.replace(":Status", "");
                    ids.add(carId);
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * 扫描 Redis 中实际存在的小车，返回最大编号
     * 用于动态添加小车时确定新车编号
     */
    public int getMaxCarNumber() {
        int maxNum = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys("Car*:Status");
            if (keys != null) {
                for (String key : keys) {
                    // key 格式: Car003:Status → 提取 003
                    String carId = key.replace(":Status", "");
                    try {
                        // Car003 → 3
                        int num = Integer.parseInt(carId.substring(3));
                        if (num > maxNum) maxNum = num;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return maxNum;
    }

    /**
     * 扫描 Redis 中实际存在的小车数量
     */
    public int getActualCarCount() {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys("Car*:Status");
            return keys != null ? keys.size() : 0;
        }
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

    // ==================== 小车增删 ====================

    /**
     * 删除一辆小车的所有 Redis 数据（运行时操作，不影响地图原始配置）
     */
    public void deleteCarData(String carId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String[] keys = {
                Constants.getCarPositionKey(carId),
                Constants.getCarTargetKey(carId),
                Constants.getCarRouteListKey(carId),
                Constants.getCarStatusKey(carId),
                Constants.getCarStepsKey(carId),
                Constants.getCarBlockedTickKey(carId),
                Constants.getLockKey(carId)
            };
            jedis.del(keys);
            logger.info("Deleted all Redis data for {}", carId);
        }
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

    /**
     * 熄灭以 (x,y) 为中心的 3×3 区域（将探索状态恢复为未探索）
     * 用于移动小车前清理旧位置的视野标记
     */
    public void darkenArea(int x, int y, int mapWidth, int mapHeight) {
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        int offset = ny * mapWidth + nx;
                        pipeline.setbit(Constants.REDIS_KEY_MAP_VIEW, offset, false);
                    }
                }
            }
            pipeline.sync();
        }
    }

    /**
     * 根据当前所有小车位置，重新照亮所有视野区域。
     * 用于移动/删除小车后恢复被错误清除的视野（当多个小车共享同一区域时）。
     */
    public void reilluminateAllCars(int mapWidth, int mapHeight) {
        Map<String, Point> positions = getAllCarPositions();
        if (positions.isEmpty()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Point p : positions.values()) {
                for (int dx = -Constants.VISION_RANGE; dx <= Constants.VISION_RANGE; dx++) {
                    for (int dy = -Constants.VISION_RANGE; dy <= Constants.VISION_RANGE; dy++) {
                        int nx = p.getX() + dx;
                        int ny = p.getY() + dy;
                        if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                            int offset = ny * mapWidth + nx;
                            pipeline.setbit(Constants.REDIS_KEY_MAP_VIEW, offset, true);
                        }
                    }
                }
            }
            pipeline.sync();
        }
    }

    /**
     * 完全重算视野：先删除整个 mapView bitmap（含分块缓存），再根据当前所有小车位置重新照亮。
     * 用于仿真开始前（tick==0）移动/删除小车时确保视野数据与小车位置严格一致。
     */
    public void recalculateFullVision(int mapWidth, int mapHeight) {
        try (Jedis jedis = jedisPool.getResource()) {
            // 删除 bitmap 和分块元数据
            jedis.del(Constants.REDIS_KEY_MAP_VIEW);
            jedis.del(Constants.REDIS_KEY_MAP_VIEW + ":chunks:meta");
            // 清理可能存在的分块数据
            Set<String> chunkKeys = jedis.keys(Constants.REDIS_KEY_MAP_VIEW + ":chunk:*");
            if (chunkKeys != null && !chunkKeys.isEmpty()) {
                jedis.del(chunkKeys.toArray(new String[0]));
            }
        }
        // 根据所有小车当前位置重新照亮
        reilluminateAllCars(mapWidth, mapHeight);
        logger.info("Full vision recalculated for {}×{} map from {} cars",
                mapWidth, mapHeight, getAllCarPositions().size());
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

    // ==================== 分块地图存储 ====================

    /**
     * 将地图以分块方式存储到 Redis
     * 每个块以 JSON 数组存储，key 格式: mapView:chunk:{chunkX}_{chunkY}
     */
    public void storeMapViewChunked(int[][] mapView, int mapWidth, int mapHeight) {
        List<MapCompression.MapChunk> chunks = MapCompression.splitIntoChunks(mapView, mapWidth, mapHeight);
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (MapCompression.MapChunk chunk : chunks) {
                String chunkKey = "mapView:chunk:" + chunk.getChunkId();
                // 将压缩数据序列化为 JSON
                String json = serializeChunkData(chunk);
                pipeline.set(chunkKey, json);
            }
            // 存储分块元数据
            jedis.set("mapView:chunks:meta", mapWidth + "x" + mapHeight + ":" + 
                ((int)Math.ceil((double)mapWidth / MapCompression.CHUNK_SIZE)));
            pipeline.sync();
        }
    }

    /**
     * 从 Redis 加载分块存储的地图
     */
    public int[][] loadMapViewChunked(int mapWidth, int mapHeight) {
        int chunksX = (int) Math.ceil((double) mapWidth / MapCompression.CHUNK_SIZE);
        int chunksY = (int) Math.ceil((double) mapHeight / MapCompression.CHUNK_SIZE);
        List<MapCompression.MapChunk> chunks = new ArrayList<>();

        try (Jedis jedis = jedisPool.getResource()) {
            for (int cy = 0; cy < chunksY; cy++) {
                for (int cx = 0; cx < chunksX; cx++) {
                    String chunkKey = "mapView:chunk:" + cx + "_" + cy;
                    String json = jedis.get(chunkKey);
                    if (json != null) {
                        MapCompression.MapChunk chunk = deserializeChunkData(json, cx, cy);
                        if (chunk != null) chunks.add(chunk);
                    }
                }
            }
        }
        return MapCompression.mergeChunks(chunks, mapWidth, mapHeight);
    }

    /**
     * 存储地图块的增量更新（只更新变化的块）
     * 用于小车移动后只更新涉及的块
     */
    public void updateMapViewChunkIncremental(int[][] fullMapView, int mapWidth, int mapHeight, 
                                               Set<String> changedChunkIds) {
        List<MapCompression.MapChunk> allChunks = MapCompression.splitIntoChunks(fullMapView, mapWidth, mapHeight);
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (MapCompression.MapChunk chunk : allChunks) {
                if (changedChunkIds.contains(chunk.getChunkId())) {
                    String chunkKey = "mapView:chunk:" + chunk.getChunkId();
                    String json = serializeChunkData(chunk);
                    pipeline.set(chunkKey, json);
                }
            }
            pipeline.sync();
        }
    }

    /**
     * 获取地图中某个坐标所在的块 ID
     */
    public static String getChunkIdForCell(int x, int y) {
        int cx = x / MapCompression.CHUNK_SIZE;
        int cy = y / MapCompression.CHUNK_SIZE;
        return cx + "_" + cy;
    }

    /**
     * 获取小车视野范围涉及的所有块 ID（用于增量更新）
     */
    public static Set<String> getAffectedChunkIds(int x, int y, int visionRange, int mapWidth, int mapHeight) {
        Set<String> ids = new HashSet<>();
        for (int dx = -visionRange; dx <= visionRange; dx++) {
            for (int dy = -visionRange; dy <= visionRange; dy++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                    ids.add(getChunkIdForCell(nx, ny));
                }
            }
        }
        return ids;
    }

    private String serializeChunkData(MapCompression.MapChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append(chunk.chunkX).append(",").append(chunk.chunkY).append(",")
          .append(chunk.startX).append(",").append(chunk.startY).append(",")
          .append(chunk.width).append(",").append(chunk.height).append("|");
        // 压缩数据序列化：每行用分号分隔，行内用冒号分隔
        if (chunk.compressedData != null) {
            for (int i = 0; i < chunk.compressedData.length; i++) {
                if (i > 0) sb.append(";");
                int[] row = chunk.compressedData[i];
                if (row != null) {
                    for (int j = 0; j < row.length; j++) {
                        if (j > 0) sb.append(":");
                        sb.append(row[j]);
                    }
                }
            }
        }
        return sb.toString();
    }

    private MapCompression.MapChunk deserializeChunkData(String json, int cx, int cy) {
        try {
            String[] parts = json.split("\\|");
            if (parts.length < 2) return null;
            String[] header = parts[0].split(",");
            if (header.length < 6) return null;

            MapCompression.MapChunk chunk = new MapCompression.MapChunk();
            chunk.chunkX = Integer.parseInt(header[0]);
            chunk.chunkY = Integer.parseInt(header[1]);
            chunk.startX = Integer.parseInt(header[2]);
            chunk.startY = Integer.parseInt(header[3]);
            chunk.width = Integer.parseInt(header[4]);
            chunk.height = Integer.parseInt(header[5]);

            if (parts[1].isEmpty()) {
                chunk.compressedData = new int[0][];
            } else {
                String[] rows = parts[1].split(";");
                chunk.compressedData = new int[rows.length][];
                for (int i = 0; i < rows.length; i++) {
                    String[] vals = rows[i].split(":");
                    chunk.compressedData[i] = new int[vals.length];
                    for (int j = 0; j < vals.length; j++) {
                        chunk.compressedData[i][j] = Integer.parseInt(vals[j]);
                    }
                }
            }
            return chunk;
        } catch (Exception e) {
            logger.warn("Failed to deserialize chunk data: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 清空全部数据 ====================

    public void clearAll() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
            logger.info("黑板数据已清空");
        }
    }
}
