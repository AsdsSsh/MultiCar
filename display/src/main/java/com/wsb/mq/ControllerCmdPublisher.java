package com.wsb.mq;

import com.alibaba.fastjson2.JSON;
import com.wsb.dto.CommandMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向 ControllerCmd 队列发送命令（来自 Web 前端的控制指令）
 */
@Component
public class ControllerCmdPublisher {

    @Value("${wsb.mq.controller-cmd-queue}")
    private String controllerCmdQueue;

    private final RabbitTemplate rabbitTemplate;

    public ControllerCmdPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(CommandMessage msg) {
        String json = JSON.toJSONString(msg);
        rabbitTemplate.convertAndSend(controllerCmdQueue, json);
    }
}
