package com.tianji.learning.service;

import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningRecord;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 学习记录表 服务类
 * </p>
 *
 * @author lzj
 * @since 2025-01-28
 */
public interface ILearningRecordService extends IService<LearningRecord> {
    /**
     * 查询指定课程的学习记录
     * @param courseId
     * @return
     */
    LearningLessonDTO queryLearningRecordByCourse(Long courseId);

    /**
     * 提交学习记录
     * @param learningRecordFormDTO
     */
    void addLearningRecord(LearningRecordFormDTO learningRecordFormDTO);
}
