package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Redis ID生成器工具类
 * @author vcgh
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳 (2025-01-01 00:00:00 UTC)
     */
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成下一个ID
     * @param keyPrefix key前缀
     * @return 生成的ID
     */
    public long nextId(String keyPrefix){
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment(String.format("icr:%s%s", keyPrefix, date));
        return timestamp << COUNT_BITS | (count != null ? count : 0L);
    }
}
