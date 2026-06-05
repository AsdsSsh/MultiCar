package com.wsb.service;

import com.alibaba.fastjson2.JSON;
import com.wsb.dto.CarState;
import com.wsb.dto.SimulationState;
import com.wsb.redis.BlackboardReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 读取 Redis 黑板 → 组装 STATE_UPDATE → 广播到所有 WebSocket 客户端
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final BlackboardReader reader;

    public PushService(BlackboardReader reader) {
        this.reader = reader;
    }

    public void addSession(WebSocketSession session) {
        sessions.add(session);
        log.info("[WS] 客户端连接  id={}  当前连接数={}", session.getId(), sessions.size());
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
        log.info("[WS] 客户端断开  id={}  当前连接数={}", session.getId(), sessions.size());
    }

    /** 收到 REFRESH_ALL 后调用：读 Redis → 组装快照 → 广播 */
    public void pushStateUpdate(int tick) {
        if (sessions.isEmpty()) return;

        try {
            Map<String, Object> config = reader.readTaskConfig();
            int w = toInt(config.get("mapWidth"), 40);
            int h = toInt(config.get("mapHeight"), 30);

            SimulationState state = new SimulationState();
            state.setTick(tick);
            state.setConfig(config);
            state.setMapView(reader.readMapView(w, h));
            state.setMapBlock(reader.readMapBlock(w, h));
            state.setCars(reader.readAllCars(w, h));

            String json = JSON.toJSONString(state);
            broadcast(json);
        } catch (Exception e) {
            log.error("[PUSH] 组装 STATE_UPDATE 失败", e);
        }
    }

    private void broadcast(String json) {
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    synchronized (s) {
                        s.sendMessage(msg);
                    }
                } catch (IOException e) {
                    log.warn("[WS] 推送失败  id={}", s.getId());
                }
            }
        }
    }

    private int toInt(Object v, int defaultVal) {
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return defaultVal; }
    }
}
