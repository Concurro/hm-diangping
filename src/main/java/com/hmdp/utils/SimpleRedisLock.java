package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import static java.util.concurrent.TimeUnit.SECONDS;

public record SimpleRedisLock(String name, StringRedisTemplate sRedis) implements ILock {

    private static final String key_prefix = "lock:";

    @Override
    public boolean tryLock(long timeoutSec) {
        var key = key_prefix + name;
        var threadName = Thread.currentThread().getName();
        return Boolean.TRUE.equals(sRedis.opsForValue().setIfAbsent(key, threadName, timeoutSec, SECONDS));
    }

    @Override
    public void unlock() {
        sRedis.delete(key_prefix + name);
    }
}
