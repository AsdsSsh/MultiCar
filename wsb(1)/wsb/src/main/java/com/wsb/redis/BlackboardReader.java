package com.wsb.redis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wsb.dto.CarState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 读取 Redis 黑板数据
 * 每次收到 REFRESH_ALL 时调用，组装完整仿真状态快照
 */
@Component
public class BlackboardReader {

    @Value("${wsb.redis.car-key-prefix}")
    private String carKeyPrefix;

    private final RedisTemplate<String, Object> redis;

    public BlackboardReader(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    /** 读取 TaskConfig Hash */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readTaskConfig() {
        Map<Object, Object> entries = redis.opsForHash().entries("TaskConfig");
        Map<String, Object> config = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object val = e.getValue();
            // 尝试转为数值
            if (val instanceof String) {
                try { config.put(key, Integer.parseInt((String) val)); continue; } catch (NumberFormatException ignored) {}
                try { config.put(key, Double.parseDouble((String) val)); continue; } catch (NumberFormatException ignored) {}
            }
            config.put(key, val);
        }
        return config;
    }

    /** 读取 mapView Bitmap → int[][] */
    public int[][] readMapView(int width, int height) {
        return readBitmap("mapView", width, height);
    }

    /** 读取 mapBlock Bitmap → int[][] */
    public int[][] readMapBlock(int width, int height) {
        return readBitmap("mapBlock", width, height);
    }

    private int[][] readBitmap(String key, int width, int height) {
        int[][] result = new int[height][width];
        // 使用原始 RedisConnection.get(byte[]) 读取 Bitmap 原始字节
        // 注意：不能用 stringCommands()，因为 StringRedisConnection 会受序列化器影响
        byte[] bytes = redis.execute((RedisCallback<byte[]>) connection ->
            connection.get(key.getBytes())
        );
        if (bytes == null || bytes.length == 0) return result;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bitIndex = y * width + x;
                int byteIdx = bitIndex / 8;
                int bitOffset = 7 - (bitIndex % 8);
                if (byteIdx < bytes.length) {
                    result[y][x] = (bytes[byteIdx] >> bitOffset) & 1;
                }
            }
        }
        return result;
    }

    /** 扫描所有小车 ID */
    public Set<String> scanCarIds() {
        Set<String> ids = new LinkedHashSet<>();
        // 扫描 Car*:Status 来发现所有小车
        Set<String> keys = redis.keys(carKeyPrefix + "*:Status");
        if (keys != null) {
            for (String key : keys) {
                String carId = key.replace(":Status", "");
                ids.add(carId);
            }
        }
        return ids;
    }

    /** 读取所有小车状态 */
    public List<CarState> readAllCars(int mapWidth, int mapHeight) {
        List<CarState> cars = new ArrayList<>();
        Set<String> carIds = scanCarIds();
        for (String carId : carIds) {
            CarState c = new CarState();
            c.setCarId(carId);

            // Position → {x, y} (Jackson 序列化器可能解析为 Map 或 String)
            Object posObj = redis.opsForValue().get(carId + ":Position");
            if (posObj instanceof Map) {
                Map<?, ?> pos = (Map<?, ?>) posObj;
                c.setX(toInt(pos.get("x")));
                c.setY(toInt(pos.get("y")));
            } else if (posObj instanceof String) {
                JSONObject pos = JSON.parseObject((String) posObj);
                if (pos != null) {
                    c.setX(pos.getIntValue("x"));
                    c.setY(pos.getIntValue("y"));
                }
            }

            // Status → "IDLE" / "MOVING" ...
            Object status = redis.opsForValue().get(carId + ":Status");
            c.setStatus(status != null ? String.valueOf(status) : "IDLE");

            // Target → {"x":..., "y":...} JSON (Jackson 序列化器可能解析为 Map 或 String)
            Object target = redis.opsForValue().get(carId + ":Target");
            if (target instanceof Map) {
                Map<?, ?> t = (Map<?, ?>) target;
                c.setTargetX(toInt(t.get("x")));
                c.setTargetY(toInt(t.get("y")));
            } else if (target instanceof String) {
                JSONObject t = JSON.parseObject((String) target);
                if (t != null) {
                    c.setTargetX(t.getIntValue("x"));
                    c.setTargetY(t.getIntValue("y"));
                }
            }

            // RouteList → 元素格式可能是 {"x":n,"y":m} 字符串 或 [x,y] 数组
            List<Object> routeRaw = redis.opsForList().range(carId + ":RouteList", 0, -1);
            List<int[]> route = new ArrayList<>();
            if (routeRaw != null) {
                for (Object r : routeRaw) {
                    if (r instanceof String) {
                        // 格式: {"x":5,"y":3}
                        String s = ((String) r).trim();
                        if (s.startsWith("{")) {
                            try {
                                JSONObject pt = JSON.parseObject(s);
                                route.add(new int[]{toInt(pt.get("x")), toInt(pt.get("y"))});
                            } catch (Exception ignored) {}
                        } else if (s.startsWith("[")) {
                            // 格式: [5,3]
                            try {
                                List<?> p = JSON.parseArray(s);
                                if (p != null && p.size() >= 2) {
                                    route.add(new int[]{toInt(p.get(0)), toInt(p.get(1))});
                                }
                            } catch (Exception ignored) {}
                        }
                    } else if (r instanceof List) {
                        List<?> p = (List<?>) r;
                        route.add(new int[]{toInt(p.get(0)), toInt(p.get(1))});
                    }
                }
            }
            c.setRoute(route);

            // Steps
            Object steps = redis.opsForValue().get(carId + ":Steps");
            c.setSteps(toInt(steps));

            cars.add(c);
        }
        return cars;
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }
}
