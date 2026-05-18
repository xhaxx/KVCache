package com.lightcache.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LightCache Server 入口。
 * <p>
 * 启动 Spring Boot 内嵌 Tomcat，监听 8110 端口，暴露 REST API。
 */
@SpringBootApplication
public class LightCacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(LightCacheApplication.class, args);
    }
}
