package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {
    private final ICouponScopeService couponScopeService;
    private final IExchangeCodeService exchangeCodeService;
    private final IUserCouponService userCouponService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 新增优惠券接口
     * @param dto
     */
    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO dto) {
        log.info("新增优惠券接口: {}", dto);

        // 1. 保存优惠券到coupon表中
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        this.save(coupon);


        // 2. 判断是否有范围限定
        if (!dto.getSpecific()) {   // 说明没有限定优惠券的使用范围，则直接返回
            log.info("没有限定优惠券的使用范围，直接返回");
            return;
        }


        // 3. 说明有限定优惠券的使用范围
        Long couponId = coupon.getId();
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            log.info("限定范围不能为空");
            throw new BizIllegalException("限定范围不能为空");
        }


        // 4. 保存优惠券的使用范围到coupon_scope表中
        List<CouponScope> list = scopes.stream()
                .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(couponId).setType(1))
                .collect(Collectors.toList());
        couponScopeService.saveBatch(list);
    }


    /**
     * 管理端分页查询优惠券接口
     * @param query
     * @return
     */
    @Override
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query) {
        log.info("管理端分页查询优惠券接口: {}", query);
        Integer status = query.getStatus();
        String name = query.getName();
        Integer type = query.getType();

        // 1. 分页查询
        Page<Coupon> page = lambdaQuery()
                .eq(type != null, Coupon::getDiscountType, type)
                .eq(status != null, Coupon::getStatus, status)
                .like(StringUtils.isNotBlank(name), Coupon::getName, name)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());


        // 2. 处理VO
        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            log.info("没有查询到优惠券");
            return PageDTO.empty(page);
        }
        List<CouponPageVO> list = BeanUtils.copyList(records, CouponPageVO.class);


        // 3. 返回
        return PageDTO.of(page, list);
    }


    /**
     * 管理管开始发放优惠券接口
     * @param dto
     */
    @Override
    @Transactional
    public void issueCoupon(CouponIssueFormDTO dto) {
        log.info("管理端开始发放优惠券接口，线程名： {}", Thread.currentThread().getName());
        log.info("管理端开始发放优惠券接口，dto: {}", dto);

        // 1. 查询优惠券
        Coupon coupon = this.getById(dto.getId());
        log.info("查询到的优惠券：{}", coupon);
        if (coupon == null) {
            log.info("优惠券不存在！");
            throw new BadRequestException("优惠券不存在！");
        }


        // 2. 判断优惠券状态，是否是暂停或待发放
        if(coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE){
            log.info("只有待发放和暂停中的优惠券才能发放！");
            throw new BizIllegalException("只有待发放和暂停中的优惠券才能发放！");
        }


        // 3. 判断是否是立刻发放
        LocalDateTime issueBeginTime = dto.getIssueBeginTime();
        LocalDateTime now = LocalDateTime.now();
        // 该变量代表优惠券是否可以立刻发放
        boolean isBegin = issueBeginTime == null || !issueBeginTime.isAfter(now);
        log.info("优惠券是否可以立刻发放：{}", isBegin);


        // 4. 更新优惠券
        // 4.1. 拷贝属性到PO
        Coupon c = BeanUtils.copyBean(dto, Coupon.class);

        // 4.2. 更新状态
        if (isBegin) {  // 立刻发放
            c.setStatus(CouponStatus.ISSUING);
            c.setIssueBeginTime(now);
        } else {        // 定时发放
            c.setStatus(CouponStatus.UN_ISSUE);
        }
        log.info("更新优惠券状态：{}", c);

        // 4.3. 写入数据库
        updateById(c);


        // 如果优惠券可以立即发放，则将优惠券信息（优惠券id、领券开始/结束时间、发行总数量、限领数量）采用hash存入redis
        if (isBegin) {
            // 组织数据
            Map<String, String> map = new HashMap<>(4);
            map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(now)));
            // 注意这里别写成coupon.getIssueEndTime()，因为我只能用swagger测立即发放，此时coupon.getIssueEndTime()是null
            map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(dto.getIssueEndTime())));
            map.put("totalNum", String.valueOf(coupon.getTotalNum()));
            map.put("userLimit", String.valueOf(coupon.getUserLimit()));

            // 写入缓存
            String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + coupon.getId();
            redisTemplate.opsForHash().putAll(key, map);
        }


        // 5. 如果优惠券的领取方式是指定发放，那么需要生成兑换码
        // 如果优惠券发放方式为指定发放，并且优惠券之前的状态是待发放，则需要生成兑换码
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT) {
            coupon.setIssueEndTime(c.getIssueEndTime());  // 兑换码兑换的截止时间，就是优惠券的发放截止时间
            exchangeCodeService.asyncgenerateExchangeCode(coupon);  // 异步生成兑换码
        }
    }


    /**
     * 用户端查询发放中的优惠券列表
     * @return
     */

    @Override
    public List<CouponVO> queryIssuingCoupons() {
        log.info("用户端查询发放中的优惠券列表");

        // 1. 查询发放中的优惠券列表   条件：状态为发放中，领取方式为手动领取
        List<Coupon> coupons = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        log.info("发放中的优惠券列表：{}", coupons);
        if (CollUtils.isEmpty(coupons)) {
            log.info("没有查询到发放中的优惠券");
            return CollUtils.emptyList();
        }


        // 2. 统计当前用户已经领取的优惠券的信息

        // 2.1. 得到 状态为发放中，领取方式为手动领取的 优惠券id列表
        List<Long> couponIds = coupons.stream().map(Coupon::getId).collect(Collectors.toList());

        // 2.2. 查询当前用户针对正在发放中的优惠券的领取记录
        List<UserCoupon> userCoupons = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .in(UserCoupon::getCouponId, couponIds)
                .list();

        // 2.3. 统计当前用户对每一个优惠券的已经领取数量
        Map<Long, Long> issuedMap = userCoupons.stream()
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        // 2.4. 统计当前用户对每一个优惠券的已经领取并且未使用的数量
        Map<Long, Long> unusedMap = userCoupons.stream()
                .filter(uc -> uc.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));


        // 3. 封装VO结果
        List<CouponVO> voList = new ArrayList<>(coupons.size());
        for (Coupon c : coupons) {
            // 3.1. 拷贝PO属性到VO
            CouponVO vo = BeanUtils.copyBean(c, CouponVO.class);

            // 3.2. 是否可以领取：已经被领取的数量issue_num < 优惠券总数量total_num && 当前用户已经领取的数量 < 每人限领数量user_limit
            vo.setAvailable(
                    c.getIssueNum() < c.getTotalNum()
                            && issuedMap.getOrDefault(c.getId(), 0L) < c.getUserLimit()
            );


            // 3.3. 是否可以使用：当前用户已经领取并且未使用的优惠券数量 > 0
            vo.setReceived(unusedMap.getOrDefault(c.getId(),  0L) > 0);

            voList.add(vo);
        }


        log.info("用户端查询发放中的优惠券列表结果：{}", voList);
        return voList;
    }


}
