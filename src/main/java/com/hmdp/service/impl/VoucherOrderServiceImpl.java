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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final BlockingQueue<VoucherOrder> ordersTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private final static ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        EXECUTOR_SERVICE.submit(new voucherOrderHandler());
    }

    private class voucherOrderHandler implements Runnable {
        @Override
        @SuppressWarnings("all")
        public void run() {
            while (true) {
                try {
                    VoucherOrder take = ordersTasks.take();
                    handleVoucherOrder(take);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        String lockKey = "lock:order:" + userId;
        RLock lock = redissonClient.getLock(lockKey);
        var isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败");
            return;
        }
        try {
            proxy.getResult(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, List.of(), voucherId.toString(), userId.toString());
        int code = result.intValue();
        if (code != 0) {
            return switch (code) {
                case 1 -> Result.fail("库存不足");
                case 2 -> Result.fail("不能重复下单");
                default -> Result.fail("系统异常");
            };
        }
        var voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order:");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        ordersTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


    @Transactional
    public void getResult(VoucherOrder voucherOrder) {
        boolean isRepeat = checkOrderByID(voucherOrder);
        if (isRepeat) {
            log.error("重复下单");
            return;
        }
        // 2. 扣减库存
        deductStock(voucherOrder);

        // 3. 创建订单
        save(voucherOrder);
    }

    private boolean checkOrderByID(VoucherOrder voucherOrder) {
        int count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
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

    private void deductStock(VoucherOrder voucherOrder) {
        int updatedRows = seckillVoucherMapper.deductStock(voucherOrder.getVoucherId());
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
