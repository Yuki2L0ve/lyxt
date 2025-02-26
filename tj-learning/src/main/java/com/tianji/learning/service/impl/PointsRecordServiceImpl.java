package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {
    private final StringRedisTemplate redisTemplate;

    /**
     * 添加积分记录
     * @param userId
     * @param points
     * @param type
     */
    @Override
    public void addPointsRecord(Long userId, Integer points, PointsRecordType type) {
        // 1. 校验参数
        if (userId == null || points == null) {
            log.info("参数错误，userId或points为空");
            return;
        }
        log.info("userId={}, points={}", userId, points);
        int realPoints = points;   // 实际可以增加的积分


        // 2. 判断该积分类型是否有上限，根据maxPoints是否大于0来判断
        int maxPoints = type.getMaxPoints();
        if (maxPoints > 0) {
            // 2.1 查询该用户已得积分
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime dayStartTime = DateUtils.getDayStartTime(now);
            LocalDateTime dayEndTime = DateUtils.getDayEndTime(now);
            // select sum(points) as totalPoints from points_record where user_id =? and type =? and create_time between? and?
            QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
            wrapper.select("sum(points) as totalPoints");
            wrapper.eq("user_id", userId);
            wrapper.eq("type", type);
            wrapper.between("create_time", dayStartTime, dayEndTime);
            Map<String, Object> map = this.getMap(wrapper);
            int currentPoints = 0;  // 当前用户已得积分
            if (map != null) {
                BigDecimal totalPoints = (BigDecimal) map.get("totalPoints");
                currentPoints = totalPoints.intValue();
            }

            // 2.2 判断是否超过上限
            if (currentPoints >= maxPoints) {
                log.info("该积分类型已达上限");
                return ;
            }

            // 2.3 计算实际可以增加的积分
            if (currentPoints + realPoints > maxPoints) {    // 实际增加的积分超过上限
                realPoints = maxPoints - currentPoints;    // 实际增加的积分等于上限减去当前积分
            }
        }
        log.info("实际增加的积分: {}", realPoints);


        // 3. 新增积分记录
        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setPoints(realPoints);
        record.setType(type);
        this.save(record);


        // 4. 累加并保存总积分到Redis中，采用zset结构
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);
        log.info("累加并保存总积分到Redis中: key={}, userId={}, realPoints={}", key, userId, realPoints);
    }


    /**
     * 查询我的今日积分
     * @return
     */
    @Override
    public List<PointsStatisticsVO> queryMyPointsToday() {
        // 1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        log.info("当前登录用户id: {}", userId);

        // 2. 查询今日的积分记录
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayStartTime = DateUtils.getDayStartTime(now);
        LocalDateTime dayEndTime = DateUtils.getDayEndTime(now);
        // select type, sum(points) as points from points_record where user_id =? and create_time between ? and ? group by type
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.select("type", "sum(points) as points");    // 这里只是借助PointsRecord类中的points字段来暂存sum(points)的结果
        wrapper.eq("user_id", userId);
        wrapper.between("create_time", dayStartTime, dayEndTime);
        wrapper.groupBy("type");
        List<PointsRecord> records = this.list(wrapper);
        log.info("今日的积分记录: {}", records);
        if (CollUtils.isEmpty(records)) {
            log.info("今日没有积分记录");
            return CollUtils.emptyList();
        }


        // 3. 封装VO返回值
        List<PointsStatisticsVO> voList = new ArrayList<>();
        for (PointsRecord record : records) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(record.getType().getDesc());
            vo.setMaxPoints(record.getType().getMaxPoints());
            vo.setPoints(record.getPoints());
            voList.add(vo);
        }

        return voList;
    }
}
