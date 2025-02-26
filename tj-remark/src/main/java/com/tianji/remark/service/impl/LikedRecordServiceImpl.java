//package com.tianji.remark.service.impl;
//
//import com.tianji.api.dto.msg.LikedTimesDTO;
//import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
//import com.tianji.common.constants.MqConstants;
//import com.tianji.common.utils.StringUtils;
//import com.tianji.common.utils.UserContext;
//import com.tianji.remark.domain.dto.LikeRecordFormDTO;
//import com.tianji.remark.domain.po.LikedRecord;
//import com.tianji.remark.mapper.LikedRecordMapper;
//import com.tianji.remark.service.ILikedRecordService;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//
///**
// * <p>
// * 点赞记录表 服务实现类
// * </p>
// *
// * @author lzj
// * @since 2025-02-05
// */
//@Service
//@Slf4j
//@RequiredArgsConstructor
//public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {
//    private final RabbitMqHelper rabbitMqHelper;
//
//
//    /**
//     * 点赞或取消点赞
//     * @param dto
//     */
//    @Override
//    public void addLikeRecord(LikeRecordFormDTO dto) {
//        // 1. 基于前端的参数，判断是执行点赞还是取消点赞
//        boolean success = dto.getLiked() ? liked(dto) : unliked(dto);
//
//
//        // 2. 判断是否执行成功，如果失败，则直接结束
//        if (!success) {
//            log.error("点赞或取消点赞失败！");
//            return ;
//        }
//
//        // 3. 如果执行成功，统计点赞总数
//        Integer likedTimes = this.lambdaQuery().eq(LikedRecord::getBizId, dto.getBizId()).count();
//
//
//        // 4. 发送MQ通知
//        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, dto.getBizType());
//        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), likedTimes);
//        log.info("发送MQ通知，routingKey={}, msg={}", routingKey, msg);
//        rabbitMqHelper.send(
//                MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
//                routingKey,
//                msg
//        );
//    }
//
//
//    /**
//     * 批量查询指定业务id的点赞状态, 封装好业务id塞到Set集合中, 以便供其他微服务远程调用
//     * @param bizIds
//     * @return
//     */
//    @Override
//    public Set<Long> isBizLiked(List<Long> bizIds) {
//        // 1. 获取当前登录用户id
//        Long userId = UserContext.getUser();
//        log.info("查询用户{}的点赞状态", userId);
//
//
//        // 2. 查询指定业务id的点赞状态
//        List<LikedRecord> list = this.lambdaQuery()
//                .in(LikedRecord::getBizId, bizIds)
//                .eq(LikedRecord::getUserId, userId)
//                .list();
//
//
//        // 3. 返回结果
//        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
//    }
//
//    /**
//     * 取消点赞
//     * @param dto
//     * @return
//     */
//    private boolean unliked(LikeRecordFormDTO dto) {
//        // 1. 获取当前登录用户id
//        Long userId = UserContext.getUser();
//        log.info("用户{}取消点赞了{}的{}", userId, dto.getBizId(), dto.getBizType());
//
//        // 2. 判断是否存在点赞记录
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if (record == null) {   // 说明之前没有点赞过，那么就不用取消点赞
//            log.info("用户{}没有点赞过{}的{}，不需要取消点赞", userId, dto.getBizId(), dto.getBizType());
//            return false;
//        }
//
//        // 3. 存在点赞记录，那么就删除点赞记录
//        boolean result = this.removeById(record.getId());
//
//
//        return result;
//    }
//
//
//    /**
//     * 点赞
//     * @param dto
//     * @return
//     */
//    private boolean liked(LikeRecordFormDTO dto) {
//        // 1. 获取当前登录用户id
//        Long userId = UserContext.getUser();
//        log.info("用户{}点赞了{}的{}", userId, dto.getBizId(), dto.getBizType());
//
//        // 2.判断是否存在点赞记录
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if (record != null) {
//            log.info("用户{}已经点赞过{}的{}", userId, dto.getBizId(), dto.getBizType());
//            return false;
//        }
//
//        // 3. 说明之前没有点赞过，那么就新增点赞记录
//        LikedRecord likedRecord = new LikedRecord();
//        likedRecord.setUserId(userId);
//        likedRecord.setBizId(dto.getBizId());
//        likedRecord.setBizType(dto.getBizType());
//        boolean result = this.save(likedRecord);
//
//
//        return result;
//    }
//}
