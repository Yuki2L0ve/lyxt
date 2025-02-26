package com.tianji.learning.scheduleTask;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LessonStatusCheckJob {
    private final ILearningLessonService lessonService;

    @Scheduled(cron = "0 * * * * ?")
    public void lessonStatusCheck() {
        log.info("SpringTask定时任务，定期检查learning_lesson表中的课程是否过期，如果过期则将课程状态修改为已过期");
        // 1. 查询状态为未过期的课程
        List<LearningLesson> list = lessonService.list(Wrappers.<LearningLesson>lambdaQuery()
                .ne(LearningLesson::getStatus, LessonStatus.EXPIRED));

        // 2. 遍历课程，检查是否有过期的课程
        LocalDateTime now = LocalDateTime.now();
        for (LearningLesson lesson : list) {
            if (now.isAfter(lesson.getExpireTime())) {
                lesson.setStatus(LessonStatus.EXPIRED);
            }
        }

        // 3. 更新课程状态
        lessonService.updateBatchById(list);
    }
}
