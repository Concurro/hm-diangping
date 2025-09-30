package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("script/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, List.of(), voucherId.toString(), userId.toString());
        int code = result.intValue();
        if (code != 0) {
            return switch (code) {
                case 1 ->  Result.fail("库存不足");
                case 2 ->  Result.fail("不能重复下单");
                default ->  Result.fail("系统异常");
            };
        }
        long orderId = redisIdWorker.nextId("order:");


        return Result.ok(0);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 验证秒杀时间
//        Result voucher = validateSeckillTime(voucherId);
//        if (!voucher.getSuccess()) {
//            return voucher;  // 直接返回错误信息
//        }
//        // 一人一单
//        // 获取专用锁对象
//        Long userId = UserHolder.getUser().getId();
//        String lockKey = userId + ":" + voucherId;
//        RLock lock = redissonClient.getLock(lockKey);
//        var isLock = lock.tryLock();
//        if (!isLock) Result.fail("一个用户只能下一单!");
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.getResult(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }



    @Transactional
    public Result getResult(Long voucherId) {
        boolean isRepeat = checkOrderByID(voucherId);
        if (isRepeat) {
            return Result.fail("您已经抢购过一次了");
        }
        // 2. 扣减库存
        deductStock(voucherId);

        // 3. 创建订单
        VoucherOrder voucherOrder = createVoucherOrder(voucherId);
        return Result.ok(voucherOrder.getId());
    }

    private boolean checkOrderByID(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        return count != 0;
    }

    private Result validateSeckillTime(Long voucherId) {
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        return Result.ok(voucher);
    }

    private void deductStock(Long voucherId) {
        int updatedRows = seckillVoucherMapper.deductStock(voucherId);
        if (updatedRows != 1) {
            throw new RuntimeException("库存不足");
        }
    }

    private VoucherOrder createVoucherOrder(Long voucherId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order:");
        voucherOrder.setId(orderId);
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return voucherOrder;
    }
}
