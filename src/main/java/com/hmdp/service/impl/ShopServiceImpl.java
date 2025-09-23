package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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
    public Result queryById(Long id) {
        // 1.缓存穿透
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(CACHE_SHOP_TYPE_KEY + id);
        if (!map.isEmpty()) {
            if (map.containsKey("NULL_FLAG")) {
                return Result.fail("店铺不存在");
            }
            // 2.缓存命中
            log.debug("缓存命中");
            Shop shop = new Shop();
            BeanUtil.fillBeanWithMap(map, shop, false);
            return Result.ok(shop);
        }
        // 3.缓存重建
        Shop shop = getById(id);
        if (shop == null) {
            // 缓存NULL值
            stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_TYPE_KEY + id, Map.of("NULL_FLAG", "true"));
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY + id, CACHE_NULL_TTL, TimeUnit.MINUTES);

            // 4.缓存重建失败
            return Result.fail("店铺不存在");
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
        return Result.ok(shop);
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
