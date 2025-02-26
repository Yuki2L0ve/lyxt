package com.tianji.promotion.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;

import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-07
 */
public interface ICouponService extends IService<Coupon> {
    /**
     * 新增优惠券接口
     * @param dto
     */
    void saveCoupon(CouponFormDTO dto);

    /**
     * 管理端分页查询优惠券接口
     * @param query
     * @return
     */
    PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query);


    /**
     * 发放优惠券
     * @param couponIssueDTO
     */
    void issueCoupon(CouponIssueFormDTO couponIssueDTO);


    /**
     * 用户端查询发放中的优惠券列表
     * @return
     */
    List<CouponVO> queryIssuingCoupons();
}
