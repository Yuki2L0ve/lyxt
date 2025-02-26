package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsRecord;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mq.message.SignInMessage;

import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-02-06
 */
public interface IPointsRecordService extends IService<PointsRecord> {
    /**
     * 添加积分记录
     * @param userId
     * @param points
     * @param type
     */
    void addPointsRecord(Long userId, Integer points, PointsRecordType type);


    /**
     * 查询我的今日积分
     * @return
     */
    List<PointsStatisticsVO> queryMyPointsToday();
}
