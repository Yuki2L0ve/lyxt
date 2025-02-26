package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.*;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.PlanStatus;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author Hera
 * @since 2025-01-06
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final LearningRecordMapper recordMapper;

    /**
     * 添加课程信息到用户课程表
     * @param userId     用户id
     * @param courseIds  课程id列表
     */
    @Override
    public void addUserLessons(Long userId, List<Long> courseIds) {
        log.info("添加课程信息到用户课程表 userId:{}, courseIds:{}", userId, courseIds);

        // 1. 查询课程有效期
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cInfoList)) {
            log.error("课程信息不存在，无法添加到课表 courseIds:{}", courseIds);
            return ;
        }

        // 2. 循环遍历课程集合，将课程保存到用户课程表
        log.info("循环遍历课程集合，将课程保存到用户课程表");
        List<LearningLesson> list = new ArrayList<>(courseIds.size());
        for (CourseSimpleInfoDTO cInfo  : cInfoList) {
            LearningLesson lesson = new LearningLesson();
            // 2.1 获取过期时间
            Integer validDuration = cInfo.getValidDuration();
            if (validDuration != null && validDuration > 0) {
                LocalDateTime now = LocalDateTime.now();
                lesson.setCreateTime(now);
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            // 2.2. 填充userId和courseId
            lesson.setUserId(userId);
            lesson.setCourseId(cInfo.getId());
            // 2.3. 保存到数据库
            list.add(lesson);
        }

        // 3.批量新增
        saveBatch(list);
    }


    /**
     * 把课程集合构造成Map，其中key是courseId，value是CourseSimpleInfoDTO
     * @param records
     * @return
     */
    private Map<Long, CourseSimpleInfoDTO> queryCourseSimpleInfoList(List<LearningLesson> records) {
        // 3.1 获取课程id集合
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        // 3.2 查询课程信息
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);
        if (CollUtils.isEmpty(cInfoList)) {
            log.error("课程信息不存在，无法查询 courseIds:{}", cIds);
            throw new BadRequestException("课程信息不存在！");
        }
        // 3.3 把课程集合构造成Map，其中key是courseId，value是CourseSimpleInfoDTO
        return cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
    }

    /**
     * 分页查询用户课程信息
     * @param pageQuery
     * @return
     */
    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery pageQuery) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);

        // 2. 分页查询我的课表  select * from learning_lesson where user_id = #{userId} order by latest_learn_time desc limit 0, 5;
        Page<LearningLesson> page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .page(pageQuery.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        log.info("分页查询用户课程信息，共{}条记录", records.size());

        // 3. 查询课程信息，构造Map，键值对：<courseId, CourseSimpleInfoDTO>
        Map<Long, CourseSimpleInfoDTO> courseMap = queryCourseSimpleInfoList(records);
        log.info("构造课程信息Map: {}", courseMap);

        // 4. 封装VO返回
        List<LearningLessonVO> list = new ArrayList<>(records.size());
        // 4.1 循环遍历记录，封装LearningLessonVO
        for (LearningLesson learningLesson : records) {
            // 4.2 拷贝基础属性到VO
            LearningLessonVO learningLessonVO = BeanUtils.copyBean(learningLesson, LearningLessonVO.class);
            // 4.3 根据courseId获取其对应的课程信息
            CourseSimpleInfoDTO cInfo = courseMap.get(learningLesson.getCourseId());
            // 4.4 设置课程名称、封面url、章节数
            learningLessonVO.setCourseName(cInfo.getName());
            learningLessonVO.setCourseCoverUrl(cInfo.getCoverUrl());
            learningLessonVO.setSections(cInfo.getSectionNum());
            list.add(learningLessonVO);
        }

        return PageDTO.of(page, list);
    }


    /**
     * 查询当前正在学习的课程
     * @return
     */
    @Override
    public LearningLessonVO queryMyCurrentLesson() {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);

        // 2. 查询正在学习的课程 select * from xx where user_id = #{userId} AND status = 1 order by latest_learn_time limit 1
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")
                .one();
        if (lesson == null) {
            log.info("当前没有正在学习的课程");
            return null;
        }

        // 3. 拷贝LearningLesson中的基础属性到LearningLessonVO中
        LearningLessonVO learningLessonVO = BeanUtils.copyBean(lesson, LearningLessonVO.class);

        // 4. 通过远程调用，查询课程信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cInfo == null) {
            log.error("课程信息不存在，无法查询 courseId:{}", lesson.getCourseId());
            throw new BadRequestException("课程信息不存在！");
        }
        learningLessonVO.setCourseName(cInfo.getName());    // 课程名称
        learningLessonVO.setCourseCoverUrl(cInfo.getCoverUrl());    // 课程封面url
        learningLessonVO.setSections(cInfo.getSectionNum());    // 课程章节数

        // 5. 统计当前用户课表中的已报名课程总数    select count(1) from xxx where user_id = #{userId}
        Integer courseAmount = lambdaQuery().eq(LearningLesson::getUserId, userId).count();
        learningLessonVO.setCourseAmount(courseAmount);    // 课程总数

        // 6. 通过远程调用，查询出小节信息
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS =
                catalogueClient.batchQueryCatalogue(CollUtils.singletonList(lesson.getLatestSectionId()));
        if (!CollUtils.isEmpty(cataSimpleInfoDTOS)) {
            CataSimpleInfoDTO cataSimpleInfoDTO = cataSimpleInfoDTOS.get(0);
            learningLessonVO.setLatestSectionName(cataSimpleInfoDTO.getName());    // 当前章节名称
            learningLessonVO.setLatestSectionIndex(cataSimpleInfoDTO.getCIndex());    // 当前章节编号
        }

        return learningLessonVO;
    }


    /**
     * 校验用户是否已报名当前课程
     * @param courseId
     * @return
     */
    @Override
    public Long isLessonValid(Long courseId) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);

        // 2. 查询课表
        LearningLesson learningLesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (learningLesson == null) {
            log.info("用户未报名课程 courseId:{}", courseId);
            return null;
        }

        // 3. 校验课程是否过期
        LocalDateTime expireTime = learningLesson.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        if (expireTime != null && expireTime.isBefore(now)) {
            log.info("课程已过期 courseId:{}", courseId);
            return null;
        }

        return learningLesson.getId();
    }

    /**
     * 根据课程id查询指定课程信息
     * @param courseId
     * @return
     */
    @Override
    public LearningLessonVO queryLessonByCourseId(Long courseId) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);

        // 2. 查询课表
        LearningLesson learningLesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (learningLesson == null) {
            log.info("用户未报名课程 courseId:{}", courseId);
            return null;
        }

        // 3. 拷贝LearningLesson中的基础属性到LearningLessonVO中
        LearningLessonVO learningLessonVO = BeanUtils.copyBean(learningLesson, LearningLessonVO.class);

        return learningLessonVO;
    }


    /**
     * 根据课程id删除指定课程信息
     * @param courseId
     */
    @Override
    public void deleteCourseFromLesson(Long courseId) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);
        // 2. 删除该课程
        LambdaQueryWrapper<LearningLesson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId);
        remove(wrapper);
    }


    /**
     * 统计课程学习人数
     * @param courseId
     * @return
     */
    @Override
    public Integer countLearningLessonByCourse(Long courseId) {
        log.info("统计课程courseId:{}的学习人数", courseId);
        // select count(1) from xx where course_id = #{cc} AND status in (0, 1, 2)
        return lambdaQuery()
                .eq(LearningLesson::getCourseId, courseId)
                .in(LearningLesson::getStatus,
                        LessonStatus.NOT_BEGIN.getValue(),
                        LessonStatus.LEARNING.getValue(),
                        LessonStatus.FINISHED.getValue())
                .count();
    }


    /**
     * 创建学习计划
     * @param courseId
     * @param freq
     */
    @Override
    public void createLearningPlan(Long courseId, Integer freq) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);

        // 2. 查询课表
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        AssertUtils.isNotNull(lesson, "用户未报名课程");

        // 3. 创建学习计划
        LearningLesson learningLesson = new LearningLesson();
        learningLesson.setId(lesson.getId());
        learningLesson.setWeekFreq(freq);
        if (lesson.getPlanStatus() == PlanStatus.NO_PLAN) {
            learningLesson.setPlanStatus(PlanStatus.PLAN_RUNNING);
        }
        updateById(learningLesson);
    }


    /**
     * 查询我的学习计划
     * @param pageQuery
     * @return
     */
    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery pageQuery) {
        log.info("查询我的学习计划, pageQuery:{}", pageQuery);
        LearningPlanPageVO result = new LearningPlanPageVO();

        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户userId:{}", userId);

        // 2. 获取本周起始时间
        LocalDate now = LocalDate.now();
        LocalDateTime weekBeginTime = DateUtils.getWeekBeginTime(now);
        LocalDateTime weekEndTime = DateUtils.getWeekEndTime(now);

        // 3. 查询总的统计数据
        // 3.1 本周总的已学习小节数量
        Integer weekFinished = recordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, true)
                .gt(LearningRecord::getFinishTime, weekBeginTime)
                .lt(LearningRecord::getFinishTime, weekEndTime));
        result.setWeekFinished(weekFinished);
        // 3.2 本周总的学习计划小节数量
        Integer weekTotalPlan  = getBaseMapper().queryTotalPlan(userId);
        result.setWeekTotalPlan(weekTotalPlan);
        // 3.3 本周学习积分   TOTO


        // 4. 查询分页数据
        // 4.1 分页查询课表信息以及学习计划信息
        Page<LearningLesson> p = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .page(pageQuery.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = p.getRecords();
        if (CollUtils.isEmpty(records)) {
            log.info("当前没有学习计划");
            LearningPlanPageVO vo = new LearningPlanPageVO();
            vo.setTotal(0L);
            vo.setPages(0L);
            vo.setList(CollUtils.emptyList());
            return vo;
        }
        // 4.2 查询课表对应的课程信息，构造Map，键值对：<courseId, CourseSimpleInfoDTO>
        Map<Long, CourseSimpleInfoDTO> courseMap = queryCourseSimpleInfoList(records);
        // 4.3 统计每一门课程本周已学习小节数量
        List<IdAndNumDTO> list  = recordMapper.countLearnedSections(userId, weekBeginTime, weekEndTime);
        Map<Long, Integer> countMap  = IdAndNumDTO.toMap(list);

        // 4.4 封装VO返回
        List<LearningPlanVO> lessonVOList = new ArrayList<>(records.size());
        for (LearningLesson learningLesson : records) {
            // 4.4.1 拷贝基础属性到VO
            LearningPlanVO learningPlanVO = BeanUtils.copyBean(learningLesson, LearningPlanVO.class);
            // 4.4.2 填充课程详细信息
            CourseSimpleInfoDTO cInfo = courseMap.get(learningLesson.getCourseId());
            if (cInfo != null) {
                learningPlanVO.setCourseName(cInfo.getName());  // 课程名称
                learningPlanVO.setSections(cInfo.getSectionNum());  // 课程章节数
            }
            // 4.4.3 每门课程的本周已学习小节数量
            learningPlanVO.setWeekLearnedSections(countMap.getOrDefault(learningLesson.getId(), 0));
            lessonVOList.add(learningPlanVO);
        }

        // 4.5 封装分页信息
        return result.pageInfo(p.getTotal(), p.getPages(), lessonVOList);
    }
}
