package com.tianji.learning.mapper;

import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 学生课程表 Mapper 接口
 * </p>
 *
 * @author Hera
 * @since 2025-01-06
 */
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {
    /**
     * 本周总的学习计划小节数量
     * @param userId
     * @return
     */
    Integer queryTotalPlan(@Param("userId") Long userId);
}
