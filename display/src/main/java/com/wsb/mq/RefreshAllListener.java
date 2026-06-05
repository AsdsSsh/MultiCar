package com.wsb.mq;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wsb.service.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 监听 UpdateView Fanout → REFRESH_ALL
 * Controller 每个节拍广播此消息
 */
@Component
public class RefreshAllListener {

    private static final Logger log = LoggerFactory.getLogger(RefreshAllListener.class);

    private final PushService pushService;

    public RefreshAllListener(PushService pushService) {
        this.pushService = pushService;
    }

    @RabbitListener(queues = "WSB_Refresh")
    public void onMessage(Message message) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            JSONObject msg = JSON.parseObject(body);
            String cmd = msg.getString("cmd");
            if (!"REFRESH_ALL".equals(cmd)) return;

            JSONObject data = msg.getJSONObject("data");
            int tick = data != null && data.containsKey("tick") ? data.getIntValue("tick") : 0;

            log.info("[MQ] 收到 REFRESH_ALL  tick={}", tick);
            pushService.pushStateUpdate(tick);
        } catch (Exception e) {
            log.error("[MQ] 处理 REFRESH_ALL 失败", e);
        }
    }
}
