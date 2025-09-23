package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id)  {
        Shop shop = null;
        try {
            shop = queryWithMutex(id);
        } catch (InterruptedException e){
            return Result.fail("服务器异常");
        }
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryByIdWithOptimisticLock(Long id) {
        // 1.缓存穿透
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(CACHE_SHOP_TYPE_KEY + id);
        if (!map.isEmpty()) {
            if (map.containsKey("NULL_FLAG")) {
                return null;
            }
            // 2.缓存命中
            log.debug("缓存命中");
            Shop shop = new Shop();
            BeanUtil.fillBeanWithMap(map, shop, false);
            return shop;
        }
        // 3.缓存重建
        Shop shop = getById(id);
        if (shop == null) {
            // 缓存NULL值
            stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_TYPE_KEY + id, Map.of("NULL_FLAG", "true"));
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY + id, CACHE_NULL_TTL, TimeUnit.MINUTES);

            // 4.缓存重建失败
            return null;
        }
        // 5.缓存重建成功
        Map<String, Object> shopmap = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true) // 忽略null值
                .setFieldValueEditor((fieldName, value) -> value == null ? "" : value.toString()) // 值转为字符串，处理null值
        );
        // 6.写入缓存
        stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_TYPE_KEY + id, shopmap);
        // 设置有效期
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY + id, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回
        return shop;
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        String cacheKey = CACHE_SHOP_TYPE_KEY + id;

        while (true) {  // 使用循环替代递归
            // 1. 尝试从缓存查询
            Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(cacheKey);
            if (!map.isEmpty()) {
                if (map.containsKey("NULL_FLAG")) {
                    return null; // 空值缓存
                }
//                log.debug("缓存命中");
                return BeanUtil.fillBeanWithMap(map, new Shop(), false);
            }

            // 2. 尝试获取互斥锁
            String lockKey = "lock:shop:" + id;
            if (tryLack(lockKey)) {
                try {
                    // 3. 双重检查：获取锁后再次验证缓存
                    map = stringRedisTemplate.opsForHash().entries(cacheKey);
                    if (!map.isEmpty()) {
                        if (map.containsKey("NULL_FLAG")) return null;
                        return BeanUtil.fillBeanWithMap(map, new Shop(), false);
                    }

                    // 4. 缓存重建
                    Shop shop = getById(id);
                    if (shop == null) {
                        // 缓存空值防止穿透
                        stringRedisTemplate.opsForHash().put(cacheKey, "NULL_FLAG", "true");
                        stringRedisTemplate.expire(cacheKey, CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }

                    // 5. 数据转换并写入缓存
                    Map<String, Object> shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((f, v) -> v != null ? v.toString() : "")
                    );
                    stringRedisTemplate.opsForHash().putAll(cacheKey, shopMap);
                    stringRedisTemplate.expire(cacheKey, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                        return shop;

                    } finally {
                        unLock(lockKey); // 确保锁释放
                    }
            } else {
                // 获取锁失败时短暂休眠后重试
                Thread.sleep(50);
            }
        }
    }

    private boolean tryLack(String k) {
        return BooleanUtil
                .isTrue(stringRedisTemplate
                        .opsForValue()
                        .setIfAbsent(k, "1", 10, TimeUnit.SECONDS));
    }

    private void unLock(String k) {
        stringRedisTemplate.delete(k);
    }



    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY + id);
        return Result.ok();
    }
}
