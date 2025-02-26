package com.tianji.promotion.service;

import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 兑换码 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-07
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {
    /**
     * 异步生成兑换码
     * @param coupon
     */
    void asyncgenerateExchangeCode(Coupon coupon);

    /**
     * 校验是否已经兑换 SETBIT KEY 4 1 ，这里直接执行setbit，通过返回值来判断是否兑换过
     * @param serialNum
     * @param b
     * @return
     */
    boolean updateExchangeMark(long serialNum, boolean b);
}
