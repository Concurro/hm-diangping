package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import static java.util.concurrent.TimeUnit.SECONDS;

public record SimpleRedisLock(String name, StringRedisTemplate sRedis) implements ILock {

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";


    @Override
    public boolean tryLock(long timeoutSec) {
        var key = KEY_PREFIX + name;
        var threadId = ID_PREFIX + Thread.currentThread().getId();
        return Boolean.TRUE.equals(sRedis.opsForValue().setIfAbsent(key, threadId, timeoutSec, SECONDS));
    }

    @Override
    public void unlock() {
        var key = KEY_PREFIX + name;
        var sv = sRedis.opsForValue().get(key);
        var threadId = ID_PREFIX + Thread.currentThread().getId();
        if (sv != null && sv.equals(threadId)) {
            sRedis.delete(key);
        }
    }
}
