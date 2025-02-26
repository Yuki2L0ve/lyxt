package com.tianji.promotion.service;

import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-07
 */
public interface IUserCouponService extends IService<UserCoupon> {
    /**
     * 用户领取优惠券
     * @param id
     */
    void receiveCoupon(Long id);


    /**
     * 兑换码兑换优惠券
     * @param code
     */
    void exchangeCoupon(String code);


    /**
     * 由于事务方法需要public修饰，并且被spring管理。因此要把事务方法向上抽取到service接口中
     * @param coupon
     * @param userId
     * @param serialNum
     */
    public void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum);


    /**
     * 消费者接收到优惠券领取消息
     * @param uc
     */
    void checkAndCreateUserCouponNew(UserCouponDTO uc);


    /**
     * 查询我的优惠券可用方案
     * @param orderCourses
     * @return
     */
    List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses);
}
