package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;

import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 *
 * @author Hera
 * @since 2025-01-06
 */
public interface ILearningLessonService extends IService<LearningLesson> {

    /**
     * 添加课程信息到用户课程表
     * @param userId     用户id
     * @param courseIds  课程id列表
     */
    void addUserLessons(Long userId, List<Long> courseIds);

    /**
     * 分页查询用户课程信息
     * @param pageQuery
     * @return
     */
    PageDTO<LearningLessonVO> queryMyLessons(PageQuery pageQuery);

    /**
     * 查询当前正在学习的课程
     * @return
     */
    LearningLessonVO queryMyCurrentLesson();

    /**
     * 校验用户是否已报名当前课程
     * @param courseId
     * @return
     */
    Long isLessonValid(Long courseId);

    /**
     * 根据课程id查询指定课程信息
     * @param courseId
     * @return
     */
    LearningLessonVO queryLessonByCourseId(Long courseId);

    /**
     * 根据课程id删除指定课程信息
     * @param courseId
     */
    void deleteCourseFromLesson(Long courseId);

    /**
     * 统计课程学习人数
     * @param courseId
     * @return
     */
    Integer countLearningLessonByCourse(Long courseId);

    /**
     * 创建学习计划
     * @param courseId
     * @param freq
     */
    void createLearningPlan(Long courseId, Integer freq);

    /**
     * 查询我的学习计划
     * @param pageQuery
     * @return
     */
    LearningPlanPageVO queryMyPlans(PageQuery pageQuery);
}
