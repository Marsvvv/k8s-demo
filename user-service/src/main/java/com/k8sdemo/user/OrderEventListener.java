package com.k8sdemo.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费 order-service 发出的下单消息（异步解耦演示）。
 * 真实业务里这里会给用户加积分/发通知，本项目只打日志。
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @RabbitListener(queues = MqConfig.ORDER_CREATED_QUEUE)
    public void onOrderCreated(String message) {
        log.info("[user-service] 收到下单消息，用户积分 +10，消息内容: {}", message);
    }
}
