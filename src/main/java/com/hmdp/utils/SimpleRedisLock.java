package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;

public record SimpleRedisLock(String name, StringRedisTemplate sRedis) implements ILock {

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("script/slock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        var key = KEY_PREFIX + name;
        var threadId = ID_PREFIX + Thread.currentThread().getId();
        return Boolean.TRUE.equals(sRedis.opsForValue().setIfAbsent(key, threadId, timeoutSec, SECONDS));
    }

    @Override
    public void unlock() {
        sRedis.execute(UNLOCK_SCRIPT, List.of(KEY_PREFIX + name), ID_PREFIX + Thread.currentThread().getId());
    }

}


