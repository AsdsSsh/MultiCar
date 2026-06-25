package inspection.common.service_discovery;

import com.alibaba.fastjson2.JSON;
import inspection.common.blackboard.BlackboardConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final String KEY_PREFIX = "service:";
    private static final int INTERVAL_SEC = 2;
    private static final int EXPIRE_SEC = 5;

    private final String serviceType;
    private final String instanceId;
    private final String redisKey;
    private final Jedis jedis;
    private final ScheduledExecutorService scheduler;

    public HeartbeatService(BlackboardConfig config, String serviceType) {
        this.serviceType = serviceType;
        this.instanceId = InstanceId.generate(serviceType);
        this.redisKey = KEY_PREFIX + this.instanceId;

        this.jedis = new Jedis(config.getHost(), config.getPort());
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            jedis.auth(config.getPassword());
        }
        jedis.connect();
        log.info("HeartbeatService 创建: type={}, instanceId={}, redisKey={}", serviceType, instanceId, redisKey);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-" + serviceType);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        String info = JSON.toJSONString(Map.of(
                "type", serviceType,
                "instanceId", instanceId,
                "host", instanceId.split("-")[1],
                "pid", ManagementFactory.getRuntimeMXBean().getName().split("@")[0],
                "startedAt", System.currentTimeMillis()
        ));
        scheduler.scheduleAtFixedRate(() -> {
            try {
                jedis.set(redisKey, info, SetParams.setParams().ex(EXPIRE_SEC));
            } catch (Exception e) {
                log.warn("心跳写入失败: key={}, error={}", redisKey, e.getMessage());
            }
        }, 0, INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            jedis.del(redisKey);
        } catch (Exception ignored) {
        }
        jedis.close();
    }
}
