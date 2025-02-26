package com.tianji.promotion.service.impl;

import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {
    private final StringRedisTemplate redisTemplate;


    /**
     * 异步生成兑换码
     * @param coupon
     */
    @Override
    @Async("generateExchangeCodeExecutor")  // 使用自定义线程池中的线程进行异步处理
    public void asyncgenerateExchangeCode(Coupon coupon) {
        log.info("开始异步生成兑换码，线程名：{}", Thread.currentThread().getName());
        Long couponId = coupon.getId(); // 表示优惠券的id
        Integer totalNum = coupon.getTotalNum();    // 表示优惠券的发放总数量，也就是需要生成的兑换码总数量
        LocalDateTime issueEndTime = coupon.getIssueEndTime();  // 兑换码的截止时间，就是优惠券领取的截止时间
        log.info("准备给优惠券{}生成兑换码，数量：{}，截止时间：{}", couponId, totalNum, issueEndTime);


        // 1. 生成自增id    借助于redis的incr命令
        Long serialNum = redisTemplate.opsForValue().increment(PromotionConstants.COUPON_CODE_SERIAL_KEY, totalNum);
        log.info("生成的兑换码序列号为：{}", serialNum);
        if (serialNum == null) {
            log.error("生成兑换码失败");
            return;
        }


        // 2. 循环生成兑换码
        int R = serialNum.intValue();
        int L = R - totalNum + 1;
        log.info("生成的兑换码范围为：{}-{}", L, R);
        List<ExchangeCode> list = new ArrayList<>();
        for (int i = L; i <= R; ++ i) {
            // 调用工具类生成兑换码
            String code = CodeUtil.generateCode(i, couponId);   // 参数1是自增id值，参数2是优惠券id（内部会计算出0-15之间数字，然后找对应的密钥）
            ExchangeCode exchangeCode = new ExchangeCode();
            exchangeCode.setId(i);  // 设置兑换码id
            exchangeCode.setCode(code);  // 设置兑换码
            exchangeCode.setExchangeTargetId(couponId);  // 设置兑换目标id（即优惠券id）
            exchangeCode.setExpiredTime(issueEndTime);  // 设置兑换码的截止时间，就是优惠券领取的截止时间
            list.add(exchangeCode);
        }


        // 3. 将兑换码信息批量保存到数据库
        this.saveBatch(list);


        // 4. 写入Redis缓存，member：couponId，score：兑换码的最大序列号
        redisTemplate.opsForZSet().add(PromotionConstants.COUPON_RANGE_KEY, coupon.getId().toString(), R);
    }


    /**
     * 校验是否已经兑换 SETBIT KEY 4 1 ，这里直接执行setbit，通过返回值来判断是否兑换过
     * @param serialNum
     * @param mark
     * @return
     */
    @Override
    public boolean updateExchangeMark(long serialNum, boolean mark) {
        // 修改兑换码的自增id对应的offset值
        Boolean flag = redisTemplate.opsForValue().setBit(PromotionConstants.COUPON_CODE_MAP_KEY, serialNum, mark);
        log.info("修改兑换码的自增id为{}的offset值为{}，结果：{}", serialNum, mark, flag);
        return flag != null && flag;
    }
}
