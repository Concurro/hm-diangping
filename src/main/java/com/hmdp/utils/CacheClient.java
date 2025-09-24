package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
 /*

 缓存客户端 ---<(H)>---
 作者: vcgh
 日期: 2025/09/24
 描述: 缓存客户端

 */

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    // 线程池
    private static final ExecutorService CACHE_THREAD_POOL = Executors.newFixedThreadPool(10);
    // 创建缓存客户端
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 设置缓存
    public void set(String key, Object value, Long time, TimeUnit unit) {
        Map<String, Object> valueMap = BeanUtil.beanToMap(value, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true) // 忽略null值
                .setFieldValueEditor((k, v) -> v == null ? "" : v.toString()) // 值转为字符串，处理null值
        );
        stringRedisTemplate.opsForHash().putAll(key, valueMap);
        stringRedisTemplate.expire(key, time, unit);
    }
    // 逻辑过期缓存
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> db, Long time, TimeUnit unit) {
        // 1.缓存穿透
        String key = keyPrefix + id;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if (!map.isEmpty()) {
            if (map.containsKey("NULL_FLAG")) {
                return null;
            }
            // 2.缓存命中
            log.debug("缓存命中");
            return BeanUtil.toBean(map, type);
        }
        // 3.缓存重建
        R r = db.apply(id);
        if (r == null) {
            // 缓存NULL值
            stringRedisTemplate.opsForHash().putAll(key, Map.of("NULL_FLAG", "true"));
            stringRedisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 4.缓存重建失败
            return null;
        }
        // 5.缓存重建成功
        this.set(key, r, time, unit);
        return r;
    }
    // 互斥锁缓存
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> db, Long time, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        // 1. 尝试从缓存查询
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(cacheKey);
        if (!map.isEmpty()) {
            if (map.containsKey("NULL_FLAG")) return null; // 空值缓存
            return BeanUtil.toBean(map, type);
        }
        // 2. 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLack(lockKey)) {
            try {
                // 3. 双重检查：获取锁后再次验证缓存
                map = stringRedisTemplate.opsForHash().entries(cacheKey);
                if (!map.isEmpty()) {
                    if (map.containsKey("NULL_FLAG")) return null;
                    return BeanUtil.toBean(map, type);
                }
                // 4. 缓存重建
                R r = db.apply(id);
                if (r == null) {
                    // 缓存空值防止穿透
                    stringRedisTemplate.opsForHash().put(cacheKey, "NULL_FLAG", "true");
                    stringRedisTemplate.expire(cacheKey, CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 5. 数据转换并写入缓存
                this.set(cacheKey, r, time, unit);
                return r;
            } finally {
                unLock(lockKey); // 确保锁释放
            }
        } else {
            // 6. 锁未获取成功，等待并递归调用
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return queryWithMutex(keyPrefix, id, type, db, time, unit);
        }
    }
    // 逻辑过期缓存
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> db, Long time, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(cacheKey);
        if (map.isEmpty()) return null;

        // 防御性检查 ---<(H)>---
        if (!map.containsKey("data") || !map.containsKey("expireTime")) {
            log.warn("缓存结构不完整: {}", cacheKey);
            return null;
        }
        try {
            // 获取数据和过期时间
            String dataJson = (String) map.get("data");
            String expireTimeStr = (String) map.get("expireTime");
            // 解析过期时间
            LocalDateTime expireTime = LocalDateTime.parse(expireTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (expireTime.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean(dataJson, type);
            }

            log.debug("缓存已过期: {}", cacheKey);
            String lockKey = LOCK_SHOP_KEY + id;
            if (tryLack(lockKey)) {
                // 双重检查逻辑
                Map<Object, Object> doubleMap = stringRedisTemplate.opsForHash().entries(cacheKey);
                if (!doubleMap.isEmpty()) {
                    String doubleDataJson = (String) doubleMap.get("data");
                    String doubleExpireTimeStr = (String) doubleMap.get("expireTime");
                    LocalDateTime doubleExpireTime = LocalDateTime.parse(doubleExpireTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    if (doubleExpireTime.isAfter(LocalDateTime.now())) {
                        return JSONUtil.toBean(doubleDataJson, type);
                    }
                }
                CACHE_THREAD_POOL.submit(() -> {
                    try {
                        this.saveShopRedis(keyPrefix, id, time, unit, db);
                    } catch (Exception e) {
                        log.error("缓存重建失败", e);
                    } finally {
                        unLock(lockKey);
                    }
                });
            }
            // 返回过期数据
            return JSONUtil.toBean(dataJson, type);
        } catch (Exception e) {
            log.error("解析缓存数据出错", e);
            return null;
        }
    }
    // 缓存重建
    public <R, ID> void saveShopRedis(String cacheKey, ID id, Long time, TimeUnit unit, @NotNull Function<ID, R> db) {
        R r = db.apply(id);
        if (r == null) return;
        setWithLogicalExpire(cacheKey + id, r, time, unit);
    }
    // 逻辑过期缓存
    public void setWithLogicalExpire(String key, Object value, Long time, @NotNull TimeUnit unit) {
        HashMap<String, String> map = new HashMap<>();
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(unit.toSeconds(time));
        map.put("data", JSONUtil.toJsonStr(value));
        map.put("expireTime", expireTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        stringRedisTemplate.opsForHash().putAll(key, map);
    }
    // 尝试获取锁
    private boolean tryLack(String k) {
        return BooleanUtil
                .isTrue(stringRedisTemplate
                        .opsForValue()
                        .setIfAbsent(k, "1", 10, TimeUnit.SECONDS));
    }

    // 释放锁
    private void unLock(String k) {
        stringRedisTemplate.delete(k);
    }
}
