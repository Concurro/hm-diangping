package com.hmdp.mapper;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    @Update("UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = #{voucherId} AND stock > 0")
    int deductStock(Long voucherId);

}
