package com.wsb.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wsb.dto.CommandMessage;
import com.wsb.mq.ControllerCmdPublisher;
import com.wsb.service.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 端点处理器
 *
 * 接收：前端发来的控制命令（SET_CONFIG / RESET / PAUSE / RESUME / STEP_ONCE / PING）
 * 发送：收到 MQ REFRESH_ALL 后通过 PushService 广播 STATE_UPDATE
 */
@Component
public class SimulationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulationWebSocketHandler.class);

    private final PushService pushService;
    private final ControllerCmdPublisher cmdPublisher;

    public SimulationWebSocketHandler(PushService pushService, ControllerCmdPublisher cmdPublisher) {
        this.pushService = pushService;
        this.cmdPublisher = cmdPublisher;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        pushService.addSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        pushService.removeSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();
            JSONObject json = JSON.parseObject(payload);
            String cmd = json.getString("cmd");
            if (cmd == null) return;

            // 心跳直接回复 pong
            if ("PING".equals(cmd)) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
                }
                return;
            }

            // 控制命令转发到 MQ ControllerCmd 队列
            Object data = json.get("data");
            CommandMessage msg = new CommandMessage(cmd, data != null ? data : new JSONObject());
            msg.setTimestamp(json.getLongValue("timestamp", System.currentTimeMillis()));
            cmdPublisher.publish(msg);

            log.info("[WS] 收到命令 → MQ  cmd={}", cmd);
        } catch (Exception e) {
            log.error("[WS] 处理消息失败", e);
        }
    }
}
