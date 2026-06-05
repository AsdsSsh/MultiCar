package com.wsb.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：声明交换机、队列、绑定关系，配置 JSON 序列化
 */
@Configuration
public class RabbitMqConfig {

    @Value("${wsb.mq.fanout-exchange}")
    private String fanoutExchange;

    @Value("${wsb.mq.controller-cmd-queue}")
    private String controllerCmdQueue;

    /** 声明 Fanout 交换机（与 Controller 一致） */
    @Bean
    public FanoutExchange updateViewExchange() {
        return new FanoutExchange(fanoutExchange);
    }

    /** WSB 私有队列，绑定到 UpdateView Fanout */
    @Bean
    public Queue wsbRefreshQueue() {
        return new Queue("WSB_Refresh", true, false, false);
    }

    @Bean
    public Binding wsbRefreshBinding(FanoutExchange updateViewExchange, Queue wsbRefreshQueue) {
        return BindingBuilder.bind(wsbRefreshQueue).to(updateViewExchange);
    }

    /** 发送命令到 ControllerCmd 队列 */
    @Bean
    public Queue controllerCmdQueueBean() {
        return new Queue(controllerCmdQueue, true, false, false);
    }

    /** JSON 消息转换器 */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
