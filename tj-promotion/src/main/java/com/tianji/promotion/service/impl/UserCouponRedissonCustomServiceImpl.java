//package com.tianji.promotion.service.impl;
//
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.tianji.common.exceptions.BizIllegalException;
//import com.tianji.common.utils.UserContext;
//import com.tianji.promotion.domain.po.Coupon;
//import com.tianji.promotion.domain.po.ExchangeCode;
//import com.tianji.promotion.domain.po.UserCoupon;
//import com.tianji.promotion.enums.CouponStatus;
//import com.tianji.promotion.enums.ExchangeCodeStatus;
//import com.tianji.promotion.mapper.CouponMapper;
//import com.tianji.promotion.mapper.UserCouponMapper;
//import com.tianji.promotion.service.IExchangeCodeService;
//import com.tianji.promotion.service.IUserCouponService;
//import com.tianji.promotion.utils.CodeUtil;
//import com.tianji.promotion.utils.MyLock;
//import com.tianji.promotion.utils.MyLockStrategy;
//import com.tianji.promotion.utils.MyLockType;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.springframework.aop.framework.AopContext;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDateTime;
//
///**
// * <p>
// * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
// * </p>
// *
// * @author lzj
// * @since 2025-02-07
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class UserCouponRedissonCustomServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
//
//    private final CouponMapper couponMapper;
//    private final IExchangeCodeService exchangeCodeService;
//
//    @Override
//    //@Transactional    // 此处的事务注解取消
//    public void receiveCoupon(Long couponId) {
//        log.info("用户领取优惠券，优惠券id：{}", couponId);
//
//
//        // 1. 查询优惠券
//        Coupon coupon = couponMapper.selectById(couponId);
//        if (coupon == null) {
//            log.info("优惠券不存在");
//            throw new BizIllegalException("优惠券不存在");
//        }
//
//
//        // 2. 校验优惠券状态是否为正在发放
//        if (coupon.getStatus() != CouponStatus.ISSUING) {
//            log.info("该优惠券状态不是正在发放");
//            throw new BizIllegalException("该优惠券状态不是正在发放");
//        }
//
//
//        // 3. 校验优惠券发放时间
//        LocalDateTime now = LocalDateTime.now();
//        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
//            log.info("优惠券发放已经结束或尚未开始，now：{}, issueBeginTime：{}, issueEndTime：{}", now, coupon.getIssueBeginTime(), coupon.getIssueEndTime());
//            throw new BizIllegalException("优惠券发放已经结束或尚未开始");
//        }
//
//
//        // 4. 校验库存是否充足
//        if (coupon.getIssueNum() >= coupon.getTotalNum()) {
//            log.info("优惠券库存不足");
//            throw new BizIllegalException("优惠券库存不足");
//        }
//
//
//        // 5. 校验并生成用户券
//        Long userId = UserContext.getUser();
//        IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
//        // 这种写法是调用代理对象的方法，方法是有事务处理的
//        userCouponServiceProxy.checkAndCreateUserCoupon(coupon, userId, null);
//    }
//
//
//    /**
//     * 兑换码兑换优惠券
//     * @param code
//     */
//    @Override
//    @Transactional
//    public void exchangeCoupon(String code) {
//        log.info("用户兑换优惠券，兑换码：{}", code);
//        if (code == null || code.isEmpty()) {
//            log.info("兑换码为空");
//            throw new BizIllegalException("兑换码为空");
//        }
//
//        // 1. 解析兑换码得到自增id，校验并解析兑换码
//        long serialNum = CodeUtil.parseCode(code);
//        log.info("解析兑换码得到自增id：{}", serialNum);
//
//
//        // 2. 校验是否已经兑换 SETBIT KEY 4 1 ，这里直接执行setbit，通过返回值来判断是否兑换过
//        boolean exchanged = exchangeCodeService.updateExchangeMark(serialNum, true);
//        if (exchanged) {
//            log.info("兑换码已经被兑换过了");
//            throw new BizIllegalException("兑换码已经被兑换过了");
//        }
//
//
//        try {
//            // 3. 查询兑换码对应的优惠券id
//            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
//            if (exchangeCode == null) {
//                log.info("兑换码不存在！");
//                throw new BizIllegalException("兑换码不存在！");
//            }
//
//
//            // 4. 是否过期
//            LocalDateTime now = LocalDateTime.now();
//            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
//            if (now.isAfter(expiredTime)) {
//                log.info("兑换码已经过期");
//                throw new BizIllegalException("兑换码已经过期");
//            }
//
//
//            // 5.校验并生成用户券
//            // 5.1. 查询优惠券
//            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
//            if (coupon == null) {
//                log.info("优惠券不存在");
//                throw new BizIllegalException("优惠券不存在");
//            }
//
//            // 5.2. 查询用户
//            Long userId = UserContext.getUser();
//
//            // 5.3. 校验并生成用户券，更新兑换码状态
//            checkAndCreateUserCoupon(coupon, userId, serialNum);
//        } catch (Exception e) {
//            // 将兑换码的状态充值，重置兑换的标记 0
//            exchangeCodeService.updateExchangeMark(serialNum, false);
//            throw e;
//        }
//    }
//
//    @Transactional  // 这里进事务，同时，事务方法一定要public修饰
//    @Override
//    @MyLock(name = "lock:coupon:uid:#{userId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStrategy = MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
//    public void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum) {
//        // 4. 校验每人限领数量
//        // 4.1.统计当前用户对当前优惠券的已经领取的数量
//        Integer count = this.lambdaQuery()
//                .eq(UserCoupon::getUserId, userId)
//                .eq(UserCoupon::getCouponId, coupon.getId())
//                .count();
//
//        // 4.2. 校验限领数量
//        if (count != null && count >= coupon.getUserLimit()) {
//            log.info("超出领取数量");
//            throw new BizIllegalException("超出领取数量");
//        }
//
//
//        // 5. 更新优惠券的已经发放的数量 + 1
//        int r = couponMapper.incrIssueNum(coupon.getId());  // 采用这种方式，考虑并发控制，后期仍需修改
//        if (r == 0) {
//            log.info("优惠券库存不足啦！");
//            throw new BizIllegalException("优惠券库存不足啦！");
//        }
//
//
//        // 6. 新增用户券，保存到DB中
//        saveUserCoupon(coupon, userId);
//
//        // 7. 更新兑换码状态
//        if (serialNum != null) {
//            exchangeCodeService.lambdaUpdate()
//                    .set(ExchangeCode::getUserId, userId)
//                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
//                    .eq(ExchangeCode::getId, serialNum)
//                    .update();
//        }
//
//        //throw new RuntimeException("故意报错，测试事务回滚");
//    }
//
//
//    /**
//     * 新增用户券，保存到DB中
//     * @param coupon
//     * @param userId
//     */
//    private void saveUserCoupon(Coupon coupon, Long userId) {
//        log.info("新增用户券，优惠券id：{}, 用户id：{}", coupon.getId(), userId);
//
//        // 1. 基本信息
//        UserCoupon uc = new UserCoupon();
//        uc.setUserId(userId);
//        uc.setCouponId(coupon.getId());
//
//
//        // 2. 有效期信息
//        LocalDateTime termBeginTime = coupon.getTermBeginTime();    // 优惠券使用的开始时间
//        LocalDateTime termEndTime = coupon.getTermEndTime();        // 优惠券使用的结束时间
//        if (termBeginTime == null && termEndTime == null) { // 说明是固定天数
//            termBeginTime = LocalDateTime.now();
//            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
//        }
//        uc.setTermBeginTime(termBeginTime);
//        uc.setTermEndTime(termEndTime);
//
//
//        // 3.保存到DB中
//        this.save(uc);
//    }
//}
