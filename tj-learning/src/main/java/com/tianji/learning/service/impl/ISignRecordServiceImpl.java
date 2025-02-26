package com.tianji.learning.service.impl;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ISignRecordServiceImpl implements ISignRecordService {
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;


    /**
     * 添加签到记录
     * @return
     */
    @Override
    public SignResultVO addSignRecords() {
        // 1. 签到
        // 1.1 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户id: {}", userId);

        // 1.2 拼接key
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + userId.toString() + format;
        log.info("Redis中的key: {}", key);

        // 1.3 计算偏移量offset, 下标从0开始
        long offset = now.getDayOfMonth() - 1;
        Boolean setBit = redisTemplate.opsForValue().setBit(key, offset, true);
        log.info("设置偏移量offset: {}, 是否已经签过到: {}", offset, setBit);
        if (setBit) {
            log.info("已经签过到了，不能重复签到~");
            throw new BizIllegalException("已经签过到了，不能重复签到~");
        }


        // 2. 计算连续签到天数
        int signDays = countSignDays(key, now.getDayOfMonth());
        log.info("连续签到天数: {}", signDays);


        // 3. 计算连续签到时，能获得的奖励积分
        int rewardPoints = 0;
        switch (signDays) {
            case 7: // 连续签到7天，奖励10积分
                rewardPoints = 10;
                break;
            case 14:    // 连续签到14天，奖励20积分
                rewardPoints = 20;
                break;
            case 28:    // 连续签到28天，奖励40积分
                rewardPoints = 40;
                break;
        }
        log.info("连续签到奖励积分: {}", rewardPoints);


        // 4. 保存积分      发送消息到MQ
        log.info("发送签到消息到MQ");
        rabbitMqHelper.send(
                MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1)  // 实际增加的积分: 连续签到的奖励积分 + 今天签到的1分
        );


        // 5. 封装VO对象，返回签到结果
        SignResultVO signResultVO = new SignResultVO();
        signResultVO.setSignDays(signDays);
        signResultVO.setRewardPoints(rewardPoints);

        return signResultVO;
    }


    /**
     * 查询签到记录
     * @return
     */
    @Override
    public Byte[] querySignRecords() {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUser();
        log.info("当前登录用户id: {}", userId);


        // 2. 拼接key
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + userId.toString() + format;
        log.info("Redis中的key: {}", key);


        // 3. 从Redis中获取本月第一天到今天的所有签到记录
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            log.info("本月没有签到记录");
            return new Byte[0];
        }


        // 4. 转换为Byte数组，返回
        Long n = result.get(0);
        int offset = dayOfMonth - 1;
        Byte[] arr = new Byte[dayOfMonth];
        while (offset >= 0) {
            arr[offset -- ] = (byte)(n & 1);
            n >>>= 1;
        }

        return arr;
    }


    /**
     * 计算连续签到天数
     * 从最后一次签到开始向前统计，直到遇到第一个未签到的日期为止，计算总的签到次数，那么就是连续签到天数
     * @param key   Redis中的key
     * @param len   本月第一天到今天的天数
     * @return
     */
    private int countSignDays(String key, int len) {
        log.info("计算连续签到天数, key: {}, len: {}", key, len);

        // 1. 获取本月从第一天开始，到今天为止的所有签到记录
        List<Long> result = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(len)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            log.info("本月没有签到记录");
            return 0;
        }


        // 2. 统计连续签到天数
        Long n = result.get(0);
        int count = 0;
        while ((n & 1) == 1) {
            ++ count;
            n >>>= 1;
        }

        return count;
    }
}
