package com.wsb.dto;

import com.alibaba.fastjson2.annotation.JSONField;

/**
 * 统一消息格式：{cmd, data, timestamp}
 * 用于 MQ 消息收发和 WebSocket 命令解析
 */
public class CommandMessage {

    @JSONField(name = "cmd")
    private String cmd;

    @JSONField(name = "data")
    private Object data;

    @JSONField(name = "timestamp")
    private long timestamp;

    public CommandMessage() {}

    public CommandMessage(String cmd, Object data) {
        this.cmd = cmd;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public String getCmd() { return cmd; }
    public void setCmd(String cmd) { this.cmd = cmd; }
    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
