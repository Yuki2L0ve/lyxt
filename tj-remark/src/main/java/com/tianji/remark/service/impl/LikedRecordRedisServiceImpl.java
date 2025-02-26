package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.dto.msg.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constants.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author lzj
 * @since 2025-02-05
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LikedRecordRedisServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {
    private final RabbitMqHelper rabbitMqHelper;
    private final StringRedisTemplate redisTemplate;


    /**
     * 基于Redis缓存，完成点赞或取消点赞
     * @param dto
     */
    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        // 1. 基于前端的参数，判断是执行点赞还是取消点赞
        boolean success = dto.getLiked() ? liked(dto) : unliked(dto);


        // 2. 判断是否执行成功，如果失败，则直接结束
        if (!success) {
            log.error("点赞或取消点赞失败！");
            return ;
        }

        // 3. 如果执行成功，从Redis缓存中查询点赞数
        Long likedTimes = redisTemplate.opsForSet().size(RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId());
        log.info("业务{}的点赞数为{}", dto.getBizId(), likedTimes);
        if (likedTimes == null) {
            log.info("业务{}的点赞数为0", dto.getBizId());
            return ;
        }
        log.info("业务{}的点赞数为{}", dto.getBizId(), likedTimes);


        // 4. 使用Redis中的zset结构缓存点赞总数
        redisTemplate.opsForZSet().add(
                RedisConstants.LIKE_COUNT_KEY_PREFIX + dto.getBizType(),
                dto.getBizId().toString(),
                likedTimes
        );
    }


    /**
     * 使用Redis Pipeline来改造：批量查询指定业务id的点赞状态, 封装好业务id塞到Set集合中, 以便供其他微服务远程调用
     * @param bizIds
     * @return
     */
    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        // 1. 获取登录用户id
        Long userId = UserContext.getUser();


        // 2. 查询点赞状态    短时间执行大量Redis命令，使用Redis Pipeline来改造
        List<Object> objects = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId;
                src.sIsMember(key, userId.toString());
            }
            return null;
        });


        // 3. 返回结果
        return IntStream.range(0, objects.size())
                .filter(i -> (boolean) objects.get(i))
                .mapToObj(bizIds::get)
                .collect(Collectors.toSet());
    }

    /**
     * 将Redis中的点赞总数交由消息队列来异步更新到数据库中
     * @param bizType
     * @param maxBizSize
     */
    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        // 1. 读取并移除Redis中缓存的点赞总数
        String key = RedisConstants.LIKE_COUNT_KEY_PREFIX + bizType;
        // 从Redis的zset结构中，按照分数排序后，取出最前面的maxBizSize个元素
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().popMin(key, maxBizSize);
        if (CollUtils.isEmpty(tuples)) {
            log.info("业务类型{}的点赞总数缓存为空", bizType);
            return;
        }


        // 2. 封装数据
        List<LikedTimesDTO> list = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String bizId = tuple.getValue();
            Double likedTimes = tuple.getScore();
            if (bizId == null || likedTimes == null) {
                continue;
            }
            list.add(LikedTimesDTO.of(Long.valueOf(bizId), likedTimes.intValue()));
        }


        // 3. 发送MQ消息
        log.info("批量发送点赞消息到MQ，消息内容：{}", list);
        if (!CollUtils.isEmpty(list)) {
            rabbitMqHelper.send(
                    MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                    StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, bizType),
                    list);
        }
    }

    /**
     * 使用Redis缓存，完成缓存取消点赞
     * @param dto
     * @return
     */
    private boolean unliked(LikeRecordFormDTO dto) {
        // 1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        log.info("用户{}点赞了{}的{}", userId, dto.getBizId(), dto.getBizType());

        // 2. 拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();

        // 3. 执行SADD命令  往Redis的Set中移除点赞记录
        Long result = redisTemplate.opsForSet().remove(key, userId.toString());

        return result != null && result > 0;
    }


    /**
     * 使用Redis缓存，完成缓存点赞
     * @param dto
     * @return
     */
    private boolean liked(LikeRecordFormDTO dto) {
        // 1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        log.info("用户{}点赞了{}的{}", userId, dto.getBizId(), dto.getBizType());

        // 2. 拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();

        // 3. 执行SADD命令  往Redis的Set中添加点赞记录
        Long result = redisTemplate.opsForSet().add(key, userId.toString());

        return result != null && result > 0;
    }
}
