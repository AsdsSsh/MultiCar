package inspection.common.service_discovery;

import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;

public class InstanceId {

    public static String generate(String type) {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
        return type + "-" + host + "-" + suffix;
    }
}
