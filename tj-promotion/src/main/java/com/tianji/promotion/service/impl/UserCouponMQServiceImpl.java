package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.strategy.discount.Discount;
import com.tianji.promotion.strategy.discount.DiscountStrategy;
import com.tianji.promotion.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponMQServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;
    private final ICouponScopeService couponScopeService;
    private final Executor calculateSolutionExecutor;


    @Override
    // 这里分布式锁对优惠券id加锁，防止多个用户同时领取同一个优惠券
    @MyLock(name = "lock:coupon:cid:#{couponId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStrategy = MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    public void receiveCoupon(Long couponId) {
        log.info("用户领取优惠券，优惠券id：{}", couponId);


        // 1. 查询优惠券，从Redis中获取优惠券信息
        Coupon coupon = queryCouponByCache(couponId);
        if (coupon == null) {
            log.info("优惠券不存在");
            throw new BizIllegalException("优惠券不存在");
        }


        // 2. 校验优惠券发放时间
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            log.info("优惠券发放已经结束或尚未开始，now：{}, issueBeginTime：{}, issueEndTime：{}", now, coupon.getIssueBeginTime(), coupon.getIssueEndTime());
            throw new BizIllegalException("优惠券发放已经结束或尚未开始");
        }


        // 3. 校验库存是否充足
        if (coupon.getTotalNum() <= 0) {    // 注意：这里的库存是发放总数量，不是剩余数量，因为我们在Redis中缓存的就是totalNum字段，通过totalNum字段判断库存是否充足
            log.info("优惠券库存不足");
            throw new BizIllegalException("优惠券库存不足");
        }


        // 4. 校验每人已领数量
        Long userId = UserContext.getUser();
        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + couponId;    // prs:user:coupon:优惠券id
        Long count = redisTemplate.opsForHash().increment(key, userId.toString(), 1);   // count表示本次领取后的已领数量
        log.info("用户领取优惠券，优惠券id：{}, 用户id：{}, 已领数量：{}", couponId, userId, count);
        if (count > coupon.getUserLimit()) {    // 由于count是+1之后的结果，所以此处只能写大于号，不能写等于号
            log.info("超出每人限领数量");
            throw new BizIllegalException("超出每人限领数量");
        }


        // 5. 扣减优惠券库存
        redisTemplate.opsForHash().increment(
                PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId, "totalNum", -1);


        // 6. 发送MQ消息    消息内容为：用户id、优惠券id
        UserCouponDTO uc = new UserCouponDTO();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        rabbitMqHelper.send(
                MqConstants.Exchange.PROMOTION_EXCHANGE,
                MqConstants.Key.COUPON_RECEIVE,
                uc);
    }


    /**
     * 从Redis中获取优惠券信息（优惠券id、领券开始/结束时间、发行总数量、限领数量）
     * @param couponId
     * @return
     */
    private Coupon queryCouponByCache(Long couponId) {
        log.info("从Redis中获取优惠券信息，优惠券id：{}", couponId);

        // 1. 准备Redis key
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;

        // 2. 从Redis中获取优惠券信息
        Map<Object, Object> objMap = redisTemplate.opsForHash().entries(key);
        log.info("从Redis中获取优惠券信息，key：{}, objMap：{}", key, objMap);

        if (objMap == null || objMap.isEmpty()) {
            log.info("Redis中没有该优惠券信息");
            return null;
        }

        // 3. 组装Map数据
        Map<String, Object> newMap = new HashMap<>();
        newMap.put("userLimit", objMap.get("userLimit"));
        newMap.put("totalNum", objMap.get("totalNum"));
        long beginTimeMillis = Long.parseLong(objMap.get("issueBeginTime").toString());
        LocalDateTime beginTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(beginTimeMillis), ZoneId.systemDefault());
        newMap.put("issueBeginTime", beginTime);
        long endTimeMillis = Long.parseLong(objMap.get("issueEndTime").toString());
        LocalDateTime endTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(endTimeMillis), ZoneId.systemDefault());
        newMap.put("issueEndTime", endTime);
        log.info("转换为Coupon对象，newMap：{}", newMap);

        // 4. 组装Coupon对象
        Coupon coupon = BeanUtils.mapToBean(newMap, Coupon.class, false, CopyOptions.create());

        // 5. 返回Coupon对象
        return coupon;
    }


    /**
     * 兑换码兑换优惠券
     * @param code
     */
    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        log.info("用户兑换优惠券，兑换码：{}", code);
        if (code == null || code.isEmpty()) {
            log.info("兑换码为空");
            throw new BizIllegalException("兑换码为空");
        }

        // 1. 解析兑换码得到自增id，校验并解析兑换码
        long serialNum = CodeUtil.parseCode(code);
        log.info("解析兑换码得到自增id：{}", serialNum);


        // 2. 校验是否已经兑换 SETBIT KEY 4 1 ，这里直接执行setbit，通过返回值来判断是否兑换过
        boolean exchanged = exchangeCodeService.updateExchangeMark(serialNum, true);
        if (exchanged) {
            log.info("兑换码已经被兑换过了");
            throw new BizIllegalException("兑换码已经被兑换过了");
        }


        try {
            // 3. 查询兑换码对应的优惠券id
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null) {
                log.info("兑换码不存在！");
                throw new BizIllegalException("兑换码不存在！");
            }


            // 4. 是否过期
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
            if (now.isAfter(expiredTime)) {
                log.info("兑换码已经过期");
                throw new BizIllegalException("兑换码已经过期");
            }


            // 5.校验并生成用户券
            // 5.1. 查询优惠券
            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
            if (coupon == null) {
                log.info("优惠券不存在");
                throw new BizIllegalException("优惠券不存在");
            }

            // 5.2. 查询用户
            Long userId = UserContext.getUser();

            // 5.3. 校验并生成用户券，更新兑换码状态
            checkAndCreateUserCoupon(coupon, userId, serialNum);
        } catch (Exception e) {
            // 将兑换码的状态充值，重置兑换的标记 0
            exchangeCodeService.updateExchangeMark(serialNum, false);
            throw e;
        }
    }

    @Transactional  // 这里进事务，同时，事务方法一定要public修饰
    @Override
    @MyLock(name = "lock:coupon:uid:#{userId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStrategy = MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    public void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum) {
        // 4. 校验每人限领数量
        // 4.1.统计当前用户对当前优惠券的已经领取的数量
        Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, coupon.getId())
                .count();

        // 4.2. 校验限领数量
        if (count != null && count >= coupon.getUserLimit()) {
            log.info("超出领取数量");
            throw new BizIllegalException("超出领取数量");
        }


        // 5. 更新优惠券的已经发放的数量 + 1
        int r = couponMapper.incrIssueNum(coupon.getId());  // 采用这种方式，考虑并发控制，后期仍需修改
        if (r == 0) {
            log.info("优惠券库存不足啦！");
            throw new BizIllegalException("优惠券库存不足啦！");
        }


        // 6. 新增用户券，保存到DB中
        saveUserCoupon(coupon, userId);

        // 7. 更新兑换码状态
        if (serialNum != null) {
            exchangeCodeService.lambdaUpdate()
                    .set(ExchangeCode::getUserId, userId)
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .eq(ExchangeCode::getId, serialNum)
                    .update();
        }

        //throw new RuntimeException("故意报错，测试事务回滚");
    }


    /**
     * 消费者接收到优惠券领取消息后，调用此方法，检查并生成用户券
     * @param uc
     */
    @Override
    @Transactional
    public void checkAndCreateUserCouponNew(UserCouponDTO uc) {
        // 1. 从DB中查询优惠券信息
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null) {
            log.info("优惠券不存在");
            return ;
        }


        // 2. 更新优惠券的已经发放的数量 + 1
        int r = couponMapper.incrIssueNum(coupon.getId());
        if (r == 0) {
            log.info("优惠券库存不足啦！");
            return ;
        }


        // 3. 新增用户券，保存到DB中
        saveUserCoupon(coupon, uc.getUserId());
    }


    /**
     * 查询我的优惠券可用方案
     * @param orderCourses
     * @return
     */
    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
        Long userId = UserContext.getUser();
        log.info("查询我的优惠券可用方案，userId：{}, orderCourses：{}", userId,orderCourses);


        // 1. 查询该用户的所有可用优惠券 需要查coupon表和user_coupon表
        List<Coupon> coupons = getBaseMapper().queryMyCoupons(userId);
        log.info("该用户的所有可用优惠券有{}张，分别是：{}", coupons.size(), coupons);
        if (CollUtils.isEmpty(coupons)) {
            log.info("没有可用的优惠券方案");
            return CollUtils.emptyList();
        }


        // 2. 初排
        // 2.1 计算订单总金额
        int totalAmount = orderCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        log.info("订单总金额：{}", totalAmount);

        // 2.2 筛选可用券
        List<Coupon> availableCoupons = coupons.stream()
                .filter(c -> DiscountStrategy.getDiscount(c.getDiscountType()).canUse(totalAmount, c))
                .collect(Collectors.toList());
        log.info("经过初排后，该用户的可用优惠券有{}张，分别是：{}", availableCoupons.size(), availableCoupons);
        if (CollUtils.isEmpty(availableCoupons)) {
            log.info("经过初排后，没有可用的优惠券");
            return CollUtils.emptyList();
        }


        // 3. 精排
        Map<Coupon, List<OrderCourseDTO>> availableCouponMap = findAvailableCoupon(availableCoupons, orderCourses);
        log.info("经过精排后，每一个优惠券对应的可用课程: {}", availableCouponMap);
        if (availableCouponMap.isEmpty()) {
            log.info("经过精排后，没有可用的优惠券方案");
            return CollUtils.emptyList();
        }


        // 4. 组合
        // 4.1 添加组合的方案
        availableCoupons = new ArrayList<>(availableCouponMap.keySet());
        log.info("经过精排之后的可用优惠券个数：{}，分别为:{}", availableCoupons.size(), availableCoupons);
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);

        // 4.2 添加单券的方案
        for (Coupon c : availableCoupons) {
            solutions.add(List.of(c));
        }
        log.info("经过组合后， 该用户的可用优惠券有{}张，优惠券方案分别是：{}", solutions.size(), solutions);


//        // 5. 开始计算每一种组合的优惠明细
//        log.info("开始计算每一种组合的优惠明细");
//        List<CouponDiscountDTO> dtoList = new ArrayList<>();
//        for (List<Coupon> solution : solutions) {
//            CouponDiscountDTO dto = calculateSolutionDiscount(availableCouponMap, orderCourses, solution);
//            log.info("方案最终优惠 {}  方案中优惠券使用了 {}  规则是 {}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
//            dtoList.add(dto);
//        }
//        log.info("优惠明细结果dtoList: {}", dtoList);


        // 6. 使用多线程改造第5步，并发计算每一种组合方案的优惠信息
        log.info("开始使用多线程计算优惠券方案优惠信息");
        List<CouponDiscountDTO> dtoList = Collections.synchronizedList(new ArrayList<>(solutions.size()));  // 线程安全的list
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture.supplyAsync(() -> {
                CouponDiscountDTO dto = calculateSolutionDiscount(availableCouponMap, orderCourses, solution);
                return dto;
            }, calculateSolutionExecutor).thenAccept(dto -> {
                log.info("方案最终优惠 {}  方案中优惠券使用了 {}  规则是 {}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
                dtoList.add(dto);
                latch.countDown();
            });
        }
        try {
            latch.await(2, TimeUnit.SECONDS);   // 主线程最多等待2秒，如果超时，则抛出异常
        } catch (InterruptedException e) {
            log.error("多线程计算优惠券方案优惠信息失败", e);
        }
        log.info("经过多线程计算后，有{}种方案，具体的优惠券方案优惠信息：{}", dtoList.size(), dtoList);


        // 7. 筛选最优解
        List<CouponDiscountDTO> bestDtoList = findBestSolution(dtoList);
        log.info("筛选最优解后，有{}种方案，具体的优惠券方案优惠信息：{}", bestDtoList.size(), bestDtoList);

        return bestDtoList;
    }


    /**
     * 筛选最优解，遵循两个原则：
     * - 用券相同时，优惠金额最高的方案
     * - 优惠金额相同时，用券最少的方案
     * @param dtoList
     * @return
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> dtoList) {
        // log.info("筛选最优解，dtoList：{}", dtoList);

        // 1. 准备两个Map记录最优解
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();   // 记录用券相同，金额最高的方案
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();    // 记录金额相同，用券最少的方案


        // 2. 循环遍历，向两个Map中添加记录，筛选最优解
        for (CouponDiscountDTO solution : dtoList) {
            // 2.1 计算当前方案的id组合
            String ids = solution.getIds().stream()
                    .sorted(Long::compare).map(String::valueOf).collect(Collectors.joining(","));

            // 2.2 比较用券相同时，优惠金额是否最大
            CouponDiscountDTO best = moreDiscountMap.get(ids);
            if (best != null && best.getDiscountAmount() >= solution.getDiscountAmount()) {
                // 当前方案优惠金额少，跳过
                continue;
            }

            // 2.3 比较金额相同时，用券数量是否最少
            best = lessCouponMap.get(solution.getDiscountAmount());
            int size = solution.getIds().size();
            if (size > 1 && best != null && best.getIds().size() <= size) {
                // 当前方案用券更多，放弃
                continue;
            }

            // 2.4 更新最优解
            moreDiscountMap.put(ids, solution);
            lessCouponMap.put(solution.getDiscountAmount(), solution);
        }


        // 3 求交集
        Collection<CouponDiscountDTO> bestSolutions = CollUtils
                .intersection(moreDiscountMap.values(), lessCouponMap.values());


        // 4 排序，按优惠金额降序
        return bestSolutions.stream()
                .sorted(Comparator.comparingInt(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
    }


    /**
     * 计算每一种组合方案的优惠信息
     * @param availableCouponMap    优惠券和可用课程的映射集合
     * @param orderCourses           订单中的课程集合
     * @param solution               方案
     * @return
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> availableCouponMap,
                                                        List<OrderCourseDTO> orderCourses,
                                                        List<Coupon> solution) {
        // log.info("计算每一种组合的优惠明细，availableCouponMap：{}, orderCourses：{}, solution：{}", availableCouponMap, orderCourses, solution);

        // 1. 初始化方案结果DTO
        CouponDiscountDTO dto = new CouponDiscountDTO();


        // 2. 初始化商品id和商品折扣明细的映射，初始折扣明细全都设置为0，设置map结构，key为商品id，value初始值为0
        Map<Long, Integer> detailMap = orderCourses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, oc -> 0));


        // 3. 计算方案的优惠信息
        // 3.1 循环方案中的优惠券
        for (Coupon coupon : solution) {
            // 3.2 获取该优惠券限定范围对应的可用课程
            List<OrderCourseDTO> availableCourses = availableCouponMap.get(coupon);

            // 3.2 计算可用课程的总价（课程原价 - 折扣明细）
            int totalAmount = availableCourses.stream()
                    .mapToInt(oc -> oc.getPrice() - detailMap.get(oc.getId())).sum();

            // 3.3 判断该优惠券是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(totalAmount, coupon)) {
                log.info("该优惠券不可用");
                continue;   // 该优惠券不可用，跳过，继续下一个优惠券的处理
            }

            // 3.4 计算使用该优惠券后的折扣值
            int discountAmount = discount.calculateDiscount(totalAmount, coupon);

            // 3.5 计算商品的折扣明细，更新到detailMap中
            calculateDiscountDetails(detailMap, availableCourses, totalAmount, discountAmount);

            // 3.6 累加每一个优惠券的优惠金额，赋值给dto对象
            dto.getIds().add(coupon.getId());  // 只要执行了当前这句话，就意味着这个优惠券生效了  coupon.getId()
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount());    // 不能直接覆盖，还应该是所有生效的优惠券累加的结果
        }
        return dto;
    }


    /**
     * 计算商品的折扣明细，更新到detailMap中
     * 本方法就是在使用优惠券后，计算每个商品的折扣明细
     * 规则：前面的商品按比例计算，最后一个商品折扣明细 = 总的优惠金额 - 前面所有商品的折扣金额之和
     * @param detailMap 商品id和商品折扣明细的映射
     * @param availableCourses   该优惠券对应的可用课程集合
     * @param totalAmount        该优惠券对应的可用课程的总价
     * @param discountAmount     该优惠券对应的折扣金额
     */
    private void calculateDiscountDetails(Map<Long, Integer> detailMap,
                                          List<OrderCourseDTO> availableCourses,
                                          int totalAmount, int discountAmount) {
        // log.info("计算商品的折扣明细，detailMap：{}, availableCourses：{}, totalAmount：{}, discountAmount：{}", detailMap, availableCourses, totalAmount, discountAmount);

        int times = 0;  // 代表已经处理的商品个数
        int remainDiscount = discountAmount;
        // 循环遍历可用订单课程
        for (OrderCourseDTO course : availableCourses) {
            // 更新课程已计算数量
            ++ times;
            int discount = 0;

            // 判断是否是最后一个课程
            if (times == availableCourses.size()) { // 说明是最后一个课程
                // 是最后一个课程，总折扣金额 - 之前所有商品的折扣金额之和
                discount = remainDiscount;
            } else {                 // 不是最后一个课程
                // 计算折扣明细（课程价格在总价中占的比例，乘以总的折扣）
                discount = discountAmount * course.getPrice() / totalAmount;
                remainDiscount -= discount;
            }

            // 将商品的折扣明细添加到detailMap中
            detailMap.put(course.getId(), discount + detailMap.get(course.getId()));
        }
    }


    /**
     * 精排，查询每一个优惠券对应的课程，分为两步
     * 1. 首先要基于优惠券的限定范围对课程筛选，找出可用课程。如果没有可用课程，则优惠券不可用。
     * 2. 然后对可用课程计算总价，判断是否达到优惠门槛，没有达到门槛则优惠券不可用
     * @param coupons  初排之后的优惠券集合
     * @param orderCourses      订单中的课程集合
     * @return
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupon(List<Coupon> coupons, List<OrderCourseDTO> orderCourses) {
        log.info("进入精排，查询每一个优惠券对应的课程，coupons：{}, orderCourses：{}", coupons, orderCourses);

        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>();
        // 1. 循环遍历初排后的优惠券集合
        for (Coupon coupon : coupons) {
            // 2. 找出每一个优惠券的可用课程
            List<OrderCourseDTO> availableCourses = orderCourses;

            // 2.1 判断优惠券是否限定了范围
            if (coupon.getSpecific()) {
                // 2.2 限定了范围，则查询券的可用范围
                List<CouponScope> scopes = couponScopeService.lambdaQuery()
                        .eq(CouponScope::getCouponId, coupon.getId()).list();

                // 2.3 获取限定范围对应的分类id集合
                Set<Long> scopeIds = scopes.stream().map(CouponScope::getBizId).collect(Collectors.toSet());

                // 2.4 筛选课程，从orderCourses中筛选出该范围内的课程
                availableCourses = orderCourses.stream()
                        .filter(c -> scopeIds.contains(c.getCateId())).collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) {
                log.info("该优惠券没有任何可用课程");
                continue;   // 说明当前优惠券限定了范围，但是在订单中的课程中没有找到可用课程，说明该优惠券不可用，所以忽略该优惠券，继续下一个优惠券的处理
            }


            // 3. 计算可用课程的总金额
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();


            // 4. 判断是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount.canUse(totalAmount, coupon)) {
                map.put(coupon, availableCourses);
            }
        }


        return map;
    }


    /**
     * 新增用户券，保存到DB中
     * @param coupon
     * @param userId
     */
    private void saveUserCoupon(Coupon coupon, Long userId) {
        log.info("新增用户券，优惠券id：{}, 用户id：{}", coupon.getId(), userId);

        // 1. 基本信息
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(coupon.getId());


        // 2. 有效期信息
        LocalDateTime termBeginTime = coupon.getTermBeginTime();    // 优惠券使用的开始时间
        LocalDateTime termEndTime = coupon.getTermEndTime();        // 优惠券使用的结束时间
        if (termBeginTime == null && termEndTime == null) { // 说明是固定天数
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        uc.setTermBeginTime(termBeginTime);
        uc.setTermEndTime(termEndTime);


        // 3.保存到DB中
        this.save(uc);
    }
}
