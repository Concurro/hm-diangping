package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    public RedissonClient redissonConfig() {
        var config = new Config();
        config.useSingleServer()
                .setAddress("redis://192.168.0.2:6379")
                .setPassword("123321");
        return Redisson.create(config);
    }
}
