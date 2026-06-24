package inspection.common.config;

import inspection.common.blackboard.BlackboardConfig;
import inspection.common.messaging.MessageConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 统一连接配置加载器。
 *
 * 读取优先级：环境变量 > 系统属性 > config.properties > 硬编码默认值
 *
 * 用法：
 *   BlackboardConfig bbConfig = ConnectionConfig.loadBlackboardConfig();
 *   MessageConfig mqConfig = ConnectionConfig.loadMessageConfig();
 */
public final class ConnectionConfig {

    private static final String CONFIG_FILE = "config.properties";

    private static final Properties props = new Properties();

    static {
        try (InputStream in = ConnectionConfig.class.getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // config.properties 不存在时使用默认值
        }
    }

    private ConnectionConfig() {}

    /**
     * 按优先级读取配置值：环境变量 > 系统属性 > config.properties > defaultValue
     */
    private static String get(String key, String defaultValue) {
        String env = System.getenv(envKey(key));
        if (env != null && !env.isEmpty()) return env;

        String sysProp = System.getProperty(propKey(key));
        if (sysProp != null) return sysProp;

        String fileVal = props.getProperty(propKey(key));
        if (fileVal != null) return fileVal;

        return defaultValue;
    }

    private static int getInt(String key, int defaultValue) {
        String val = get(key, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** config.properties 中的 key 映射到环境变量名 */
    private static String envKey(String key) {
        return key.replace('.', '_').toUpperCase();
    }

    /** 统一使用 "." 分隔的 key 格式 */
    private static String propKey(String key) {
        return key;
    }

    public static BlackboardConfig loadBlackboardConfig() {
        BlackboardConfig config = new BlackboardConfig();
        config.setHost(get("redis.host", "localhost"));
        config.setPort(getInt("redis.port", 6379));
        String password = get("redis.password", null);
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }
        return config;
    }

    public static MessageConfig loadMessageConfig() {
        MessageConfig config = new MessageConfig();
        config.setHost(get("mq.host", "localhost"));
        config.setPort(getInt("mq.port", 5672));
        config.setUsername(get("mq.username", "guest"));
        config.setPassword(get("mq.password", "guest"));
        config.setVirtualHost(get("mq.virtualHost", "/"));
        return config;
    }
}
