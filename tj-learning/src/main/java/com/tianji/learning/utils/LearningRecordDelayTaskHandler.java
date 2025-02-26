package com.tianji.learning.utils;

import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.DelayQueue;


/**
 * 延迟任务处理器，用于处理学习记录的延迟任务，将播放进度持久化到数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final LearningRecordMapper recordMapper;
    private final ILearningLessonService lessonService;
    private final DelayQueue<DelayTask<RecordTaskData>> queue = new DelayQueue<>();
    private static final String RECORD_KEY_TEMPLATE = "learning:record:{}";
    private static volatile boolean begin = true;

    @PostConstruct
    public void init(){
        CompletableFuture.runAsync(this::handleDelayTask);
    }
    @PreDestroy
    public void destroy(){
        begin = false;
        log.debug("延迟任务停止执行！");
    }


    /**
     * 异步延迟任务处理器，每隔20秒执行一次，处理延迟任务队列中的任务。
     */
    public void handleDelayTask(){
        log.debug("异步延迟任务开始执行！");
        while (begin) {
            try {
                // 1.获取到期的延迟任务
                DelayTask<RecordTaskData> task = queue.take();
                RecordTaskData data = task.getData();

                // 2.查询Redis缓存
                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());
                if (record == null) {
                    continue;
                }

                // 3.比较数据，moment值
                if (!Objects.equals(data.getMoment(), record.getMoment())) {
                    // 不一致，说明用户还在持续提交播放进度，放弃旧数据
                    continue;
                }

                // 4.一致，持久化播放进度数据到数据库
                // 4.1.更新学习记录表，但是这里并还没有更新learning_record学习记录表中的moment字段
                record.setFinished(null);
                recordMapper.updateById(record);
                // 4.2.更新learning_lesson课表中的最近学习信息
                LearningLesson lesson = new LearningLesson();
                lesson.setId(data.getLessonId());   // 更新learning_lesson课表中的id
                lesson.setLatestSectionId(data.getSectionId()); // 更新learning_lesson课表中的latest_section_id
                lesson.setLatestLearnTime(LocalDateTime.now()); // 更新learning_lesson课表中的latest_learn_time
                lessonService.updateById(lesson);
            } catch (Exception e) {
                log.error("处理延迟任务发生异常", e);
            }
        }
    }


    /**
     * 添加学习记录的延迟任务: 这里其实是《是否是第一次学完》的否分支
     * 1.写入Redis缓存
     * 2.提交延迟任务到延迟队列 DelayQueue
     * @param record
     */
    public void addLearningRecordTask(LearningRecord record){
        log.info("添加学习记录的延迟任务：{}", record);
        // 1.添加数据到Redis缓存
        writeRecordCache(record);
        // 2.提交延迟任务到延迟队列 DelayQueue
        queue.add(new DelayTask<>(new RecordTaskData(record), Duration.ofSeconds(20)));
    }


    /**
     * 写入学习记录的Redis缓存数据
     * key: learning:record:lessonId    field: sectionId:01, sectionId:02...  value: id, moment, finished
     * @param record
     */
    public void writeRecordCache(LearningRecord record) {
        log.info("更新学习记录的缓存数据：{}", record);
        try {
            // 1.数据转换
            String json = JsonUtils.toJsonStr(new RecordCacheData(record));
            // 2.写入Redis
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, record.getLessonId());
            redisTemplate.opsForHash().put(key, record.getSectionId().toString(), json);
            // 3.添加缓存过期时间
            redisTemplate.expire(key, Duration.ofMinutes(1));
        } catch (Exception e) {
            log.error("更新学习记录缓存异常", e);
        }
    }


    /**
     * 从Redis缓存中读取学习记录数据
     * @param lessonId
     * @param sectionId
     * @return
     */
    public LearningRecord readRecordCache(Long lessonId, Long sectionId) {
        log.info("从Redis缓存中读取学习记录数据：lessonId={}, sectionId={}", lessonId, sectionId);
        try {
            // 1.读取Redis数据
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if (cacheData == null) {    // 缓存中没有数据
                return null;
            }
            // 2.数据检查和转换
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        } catch (Exception e) {
            log.error("缓存读取异常", e);
            return null;
        }
    }


    /**
     * 清除学习记录的Redis缓存数据
     * @param lessonId
     * @param sectionId
     */
    public void cleanRecordCache(Long lessonId, Long sectionId){
        log.info("清除学习记录的缓存数据：lessonId={}, sectionId={}", lessonId, sectionId);
        // 删除数据
        String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
        redisTemplate.opsForHash().delete(key, sectionId.toString());
    }


    /**
     * Redis中缓存的Hash结构数据
     * key: lessonId    field: sectionId:01, sectionId:02...  value: id, moment, finished
     */
    @Data
    @NoArgsConstructor
    private static class RecordCacheData{
        private Long id;
        private Integer moment;
        private Boolean finished;

        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }


    /**
     * 需要更新数据库中的数据
     */
    @Data
    @NoArgsConstructor
    private static class RecordTaskData{
        private Long lessonId;
        private Long sectionId;
        private Integer moment;

        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}