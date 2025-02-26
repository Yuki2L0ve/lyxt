package com.tianji.remark.task;


import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LikedTimesCheckTask {
    private static final List<String> BIZ_TYPES = List.of("QA", "NOTE");    // 业务类型
    private static final int MAX_BIZ_SIZE = 30; // 业务最大容量，任务每次取的biz数量，防止一次性往MQ发送消息太多

    private final ILikedRecordService likedRecordService;

    @Scheduled(cron = "0/20 * * * * ?") // 每20秒执行一次
    public void checkLikedTimes() {
        for (String bizType : BIZ_TYPES) {
            likedRecordService.readLikedTimesAndSendMessage(bizType, MAX_BIZ_SIZE);
        }
    }
}
