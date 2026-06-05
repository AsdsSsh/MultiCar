package inspection.taskconfigurator;

import inspection.common.messaging.MessageBus;
import com.alibaba.fastjson2.JSONObject;

public class TestSendConfig {
    public static void main(String[] args) throws Exception {
        MessageBus bus = new MessageBus();
        bus.connect();

        JSONObject data = new JSONObject();
        data.put("mapWidth", 30);
        data.put("mapHeight", 30);
        data.put("carCount", 5);
        data.put("obstacleDensity", 0.1);
        data.put("algorithm", "BFS");

        bus.publish("TaskConfigCmd", "FORWARD_CONFIG", data);
        System.out.println("已发送 FORWARD_CONFIG 命令");

        Thread.sleep(2000);
        bus.close();
    }
}
