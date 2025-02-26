package com.tianji.learning.service.impl;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-01-28
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {
    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;

    /**
     * 查询指定课程的学习记录
     * @param courseId
     * @return
     */
    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        // 1. 获取登录用户
        Long userId = UserContext.getUser();
        log.info("查询用户{}的学习记录", userId);

        // 2. 查询课表信息
        LearningLesson lesson = lessonService.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            log.info("用户{}未学习课程{}", userId, courseId);
            throw new BizIllegalException("用户未学习该课程");
        }

        // 3. 查询学习记录
        // select * from xx where lesson_id = #{lessonId}
        List<LearningRecord> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId()).list();

        // 4. 封装返回结果
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());  // 课表id
        dto.setLatestSectionId(lesson.getLatestSectionId());    // 最近学习章节id
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));   // 学习过的小节的记录

        return dto;
    }


    /**
     * 提交学习记录
     * @param recordDTO
     */
    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        // 1. 获取登录用户
        Long userId = UserContext.getUser();
        log.info("用户{}提交学习记录", userId);

        // 2. 处理学习记录
        boolean finished = false;   // 是否完成第一次学习
        if (recordDTO.getSectionType() == SectionType.VIDEO) {
            // 2.1 视频学习记录
            finished = handleVideoRecord(userId, recordDTO);
        } else {
            // 2.2 考试学习记录
            finished = handleExamRecord(userId, recordDTO);
        }

        if (!finished) {    // 没有新学完的小节，无需更新课表中的学习进度
            log.info("用户{}没有新学完的小节，无需更新课表中的学习进度", userId);
            return ;
        }

        // 3. 处理课表数据
        handleLearningLessonsChanges(recordDTO);
    }


    /**
     * 查询旧的学习记录: 先查询缓存，如果缓存中有记录，直接返回；如果缓存中没有记录，查询数据库，并写入缓存
     * @param lessonId
     * @param sectionId
     * @return
     */
    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        log.info("查询旧的学习记录: 先查询缓存，如果缓存中有记录，直接返回；如果缓存中没有记录，查询数据库，并写入缓存");
        // 1.查询Redis缓存
        LearningRecord record = taskHandler.readRecordCache(lessonId, sectionId);

        // 2.如果命中，直接返回
        if (record != null) {
            return record;
        }

        // 3.未命中，查询MySQL数据库
        record = lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        if (record == null) {
            log.info("数据库中也查询不到旧的学习记录，返回null");
            return null;
        }

        // 4.写入Redis缓存
        taskHandler.writeRecordCache(record);
        return record;
    }


    /**
     * 处理播放视频的学习记录
     * @param userId
     * @param recordDTO
     * @return  返回值表示是否完成播放视频学习
     */
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        log.info("用户{}新增播放视频的学习记录", userId);
        // 1. 查询旧的视频学习记录
        LearningRecord old = queryOldRecord(recordDTO.getLessonId(), recordDTO.getSectionId());

        // 2. 如果不存在旧的视频学习记录，那么旧新增视频学习记录
        if (old == null) {  // 说明是第一次进行播放视频学习
            // 2.1 将DTO转换为PO
            LearningRecord record = BeanUtils.copyProperties(recordDTO, LearningRecord.class);
            // 2.2 填充数据
            record.setUserId(userId);
            // 2.3 写入数据库
            boolean success = save(record);
            if (!success) {
                log.error("用户{}新增视频学习记录失败", userId);
                throw new DbException("新增视频学习记录失败");
            }
            return false;   // 本小节并未完成播放视频学习
        }

        // 3. 如果存在旧的视频学习记录，那么更新旧的视频学习记录
        // 3.1 判断是否为第一次完成视频学习   (条件1：旧的播放视频学习记录未完成，条件2：新记录播放进度超过50%)
        boolean finished = !old.getFinished() && recordDTO.getMoment() * 2 >= recordDTO.getDuration();

        if (!finished) {    // 走《是否是第一次学完》的否分支
            LearningRecord record = new LearningRecord();
            record.setLessonId(recordDTO.getLessonId());
            record.setSectionId(recordDTO.getSectionId());
            record.setMoment(recordDTO.getMoment());
            record.setId(old.getId());
            record.setFinished(old.getFinished());

            taskHandler.addLearningRecordTask(record);

            return false;   // 本小节并未完成播放视频学习
        }

        // 3.2 更新视频学习记录 update learning_record set moment = #{moment}, finished = #{finished}, finish_time = #{finishTime} where id = #{id}
        boolean success = lambdaUpdate()
                .set(LearningRecord::getMoment, recordDTO.getMoment())
                .set(LearningRecord::getFinished, true)
                .set(LearningRecord::getFinishTime, recordDTO.getCommitTime())
                .eq(LearningRecord::getId, old.getId())
                .update();
        if (!success) {
            log.error("用户{}更新视频学习记录失败", userId);
            throw new DbException("更新视频学习记录失败");
        }

        // 3.3 清理Redis缓存
        taskHandler.cleanRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());

        return true;
    }

    /**
     * 处理考试的学习记录
     * @param userId
     * @param recordDTO
     * @return  返回值表示是否完成考试学习
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        log.info("用户{}新增考试的学习记录", userId);
        // 1. 转换DTO为PO
        LearningRecord record = BeanUtils.copyProperties(recordDTO, LearningRecord.class);

        // 2. 填充数据
        record.setUserId(userId);
        record.setFinished(true);
        record.setFinishTime(recordDTO.getCommitTime());

        // 3. 写入数据库
        boolean success = save(record);
        if (!success) {
            log.error("用户{}新增考试学习记录失败", userId);
            throw new DbException("新增考试学习记录失败");
        }

        return true;
    }


    /**
     * 处理课表数据
     * @param recordDTO
     */
    public void handleLearningLessonsChanges(LearningRecordFormDTO recordDTO) {
        log.info("处理课表数据");
        // 1. 查询课表信息
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            log.error("课表{}不存在，无法更新数据！", recordDTO.getLessonId());
            throw new BizIllegalException("课表不存在，无法更新数据！");
        }

        // 2. 判断是否有新的完成小节
        boolean allLearned = false;
        // 2.1 远程调用，查询课程数据
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cInfo == null) {
            log.error("课程{}不存在，无法更新数据！", lesson.getCourseId());
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 2.2 比较课程是否全部学完：已学习小节 >= 课程总小节
        allLearned = lesson.getLearnedSections() + 1 >= cInfo.getSectionNum();

        // 3. 更新课表数据
        lessonService.lambdaUpdate()
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED.getValue())
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }
}
