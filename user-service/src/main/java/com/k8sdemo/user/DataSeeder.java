package com.k8sdemo.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时种入演示数据（幂等：表非空则跳过）。
 * 真实项目请用 Flyway/Liquibase 管理表结构与初始数据。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;

    public DataSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        userRepository.save(new User(null, "张三", 100));
        userRepository.save(new User(null, "李四", 200));
        userRepository.save(new User(null, "王五", 300));
        log.info("[user-service] 已初始化 3 条演示用户数据");
    }
}
