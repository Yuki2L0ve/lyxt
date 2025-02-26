package com.tianji.learning.mq;

import com.tianji.api.dto.msg.LikedTimesDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 监听点赞数变更的消息，更新本地的点赞数量
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeTimesChangeListener {
    private final IInteractionReplyService replyService;


//    @RabbitListener(bindings = @QueueBinding(
//            value = @Queue(value = "qa.liked.times.queue", durable = "true"),
//            exchange = @Exchange(value = MqConstants.Exchange.LIKE_RECORD_EXCHANGE, type = ExchangeTypes.TOPIC),
//            key = MqConstants.Key.QA_LIKED_TIMES_KEY
//    ))
//    public void listenReplyLikedTimesChange(LikedTimesDTO dto) {
//        log.info("监听到回答或评论{}的点赞消息", dto);
//        InteractionReply reply = replyService.getById(dto.getBizId());
//        if (reply == null) {
//            log.info("回答或评论{}不存在，忽略更新点赞数", dto);
//            return ;
//        }
//        reply.setLikedTimes(dto.getLikedTimes());    // 更新点赞数
//        replyService.updateById(reply);
//    }



    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "qa.liked.times.queue", durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LIKE_RECORD_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.QA_LIKED_TIMES_KEY
    ))
    public void listenReplyLikedTimesChange(List<LikedTimesDTO> list) {
        log.info("监听到回答或评论{}的点赞消息", list);

        List<InteractionReply> replyList = new ArrayList<>();
        for (LikedTimesDTO dto : list) {
            InteractionReply reply = new InteractionReply();
            reply.setId(dto.getBizId());
            reply.setLikedTimes(dto.getLikedTimes());    // 更新点赞数
            replyList.add(reply);
        }

        replyService.updateBatchById(replyList);
    }
}
