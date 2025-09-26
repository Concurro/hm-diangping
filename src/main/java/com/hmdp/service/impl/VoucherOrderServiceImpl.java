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
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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


    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 验证秒杀时间
        Result voucher = validateSeckillTime(voucherId);
        if (!voucher.getSuccess()) {
            return voucher;  // 直接返回错误信息
        }
        // 一人一单
        // 获取专用锁对象
        Long userId = UserHolder.getUser().getId();
        String lockKey = userId + ":" + voucherId;
        Object lock = locks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.getResult(voucherId);
        }
    }

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
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return voucherOrder;
    }
}
