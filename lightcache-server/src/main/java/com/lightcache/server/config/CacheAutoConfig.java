package com.lightcache.server.config;

import com.lightcache.core.CacheEngine;
import com.lightcache.core.DefaultCacheEngine;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存引擎自动装配。
 * <p>
 * 创建 CacheEngine Bean，启动时自动 load() 快照，关闭时自动 save()。
 */
@Configuration
public class CacheAutoConfig {

    private DefaultCacheEngine engine;

    @Bean
    public CacheEngine cacheEngine() {
        engine = new DefaultCacheEngine(); // 构造函数内已自动 load()
        return engine;
    }

    @PreDestroy
    public void onShutdown() {
        if (engine != null) {
            engine.shutdown();
        }
    }
}
