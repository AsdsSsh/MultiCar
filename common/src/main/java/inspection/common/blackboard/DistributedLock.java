package inspection.common.blackboard;

import inspection.common.util.Constants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.UUID;

/**
 * Redis 分布式锁封装
 * 以 Car 为粒度加锁
 */
public class DistributedLock {
    private static final Logger logger = LoggerFactory.getLogger(DistributedLock.class);
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) " +
            "else return 0 end";
    private static final long DEFAULT_EXPIRE_MS = 5000;

    private final JedisPool jedisPool;

    public DistributedLock(BlackboardConfig config) {
        this.jedisPool = new JedisPool(config.getHost(), config.getPort());
    }

    public DistributedLock(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * 通过 BlackboardClient 构造分布式锁（复用同一个 JedisPool）
     */
    public DistributedLock(BlackboardClient blackboard) {
        this.jedisPool = blackboard.getPool();
    }

    /**
     * 尝试获取锁
     * @param lockKey 锁的key
     * @param requestId 唯一请求ID（用于释放锁时校验）
     * @param expireMs 锁过期时间（毫秒）
     * @return 是否获取成功
     */
    public boolean tryLock(String lockKey, String requestId, long expireMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            SetParams params = new SetParams();
            params.nx().px(expireMs);
            String result = jedis.set(lockKey, requestId, params);
            return "OK".equals(result);
        } catch (Exception e) {
            logger.error("获取锁失败: lockKey={}, error={}", lockKey, e.getMessage());
            return false;
        }
    }

    public boolean tryLock(String lockKey, String requestId) {
        return tryLock(lockKey, requestId, DEFAULT_EXPIRE_MS);
    }

    /**
     * 尝试获取锁，返回requestId（成功）或null（失败）
     */
    public String tryLockWithId(String lockKey, long expireMs) {
        String requestId = UUID.randomUUID().toString();
        boolean locked = tryLock(lockKey, requestId, expireMs);
        return locked ? requestId : null;
    }

    /**
     * 释放锁（使用Lua脚本保证原子性）
     */
    public void unlock(String lockKey, String requestId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(UNLOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(requestId));
        } catch (Exception e) {
            logger.error("释放锁失败: lockKey={}, error={}", lockKey, e.getMessage());
        }
    }

    /**
     * 带锁执行操作
     * @param lockKey 锁key
     * @param operation 要执行的操作
     * @return 是否成功获取锁并执行
     */
    public boolean executeWithLock(String lockKey, Runnable operation) {
        return executeWithLock(lockKey, DEFAULT_EXPIRE_MS, operation);
    }

    /**
     * 带锁执行操作（自定义过期时间）
     * @param lockKey 锁key
     * @param expireMs 锁过期时间（毫秒）
     * @param operation 要执行的操作
     * @return 是否成功获取锁并执行
     */
    public boolean executeWithLock(String lockKey, long expireMs, Runnable operation) {
        String requestId = UUID.randomUUID().toString();
        boolean locked = tryLock(lockKey, requestId, expireMs);
        if (!locked) {
            logger.warn("获取锁失败: {}", lockKey);
            return false;
        }
        try {
            operation.run();
            return true;
        } finally {
            unlock(lockKey, requestId);
        }
    }

    /**
     * 以 Car 为粒度执行加锁操作
     * 使用 Constants.getLockKey(carId) 生成锁 key
     */
    public boolean executeWithCarLock(String carId, Runnable operation) {
        String lockKey = Constants.getLockKey(carId);
        return executeWithLock(lockKey, operation);
    }

    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}
