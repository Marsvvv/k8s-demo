package com.k8sdemo.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 调用 user-service。
 * 在 k8s 里 USER_SERVICE_URL=http://user-service:8080，
 * 直接使用 k8s 的 Service DNS 做服务发现，无需注册中心。
 */
@FeignClient(name = "user-service", url = "${USER_SERVICE_URL:http://localhost:8080}")
public interface UserClient {

    @GetMapping("/users/{id}")
    UserDto getUser(@PathVariable("id") Long id);
}
