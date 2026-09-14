package com.k8sdemo.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 下单核心流程（刻意写得直白，演示三种服务间通信方式）：
 * 1. Redis 缓存：先查用户缓存，未命中则 Feign 同步调用 user-service 并回填（TTL 60s）
 * 2. 生成订单写 Redis（TTL 1h）
 * 3. RabbitMQ：发 order.created 消息（user-service 异步消费）
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserClient userClient;
    private final RabbitTemplate rabbitTemplate;

    public OrderService(StringRedisTemplate redisTemplate,
                        ObjectMapper objectMapper,
                        UserClient userClient,
                        RabbitTemplate rabbitTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userClient = userClient;
        this.rabbitTemplate = rabbitTemplate;
    }

    public Order createOrder(OrderRequest request) {
        // 1. 查缓存 → 未命中走 Feign → 回填缓存
        String cacheKey = "user:info:" + request.getUserId();
        String cached = redisTemplate.opsForValue().get(cacheKey);
        UserDto user;
        if (cached != null) {
            user = readJson(cached, UserDto.class);
            log.info("[order-service] 用户缓存命中: {}", cacheKey);
        } else {
            user = userClient.getUser(request.getUserId());
            redisTemplate.opsForValue().set(cacheKey, writeJson(user), Duration.ofSeconds(60));
            log.info("[order-service] 用户缓存未命中，已 Feign 查询并回填: {}", cacheKey);
        }

        // 2. 生成订单写 Redis
        Order order = new Order();
        order.setId(System.currentTimeMillis());
        order.setUserId(request.getUserId());
        order.setProductName(request.getProductName());
        order.setAmount(request.getAmount());
        order.setCreateTime(LocalDateTime.now().toString());
        redisTemplate.opsForValue().set("order:" + order.getId(), writeJson(order), Duration.ofHours(1));

        // 3. 发 MQ 消息
        rabbitTemplate.convertAndSend(MqConfig.ORDER_EXCHANGE, MqConfig.ORDER_CREATED_ROUTING_KEY, writeJson(order));
        log.info("[order-service] 订单创建成功: id={}, 用户={}, 商品={}", order.getId(), user.getName(), order.getProductName());
        return order;
    }

    public Order getOrder(Long id) {
        String json = redisTemplate.opsForValue().get("order:" + id);
        return json == null ? null : readJson(json, Order.class);
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
