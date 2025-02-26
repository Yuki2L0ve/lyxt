package com.tianji.learning.mq;

import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class LessonChangeListener {
    private final ILearningLessonService lessonService;

    /**
     * 监听订单支付成功的消息，添加课程到课表中
     * @param dto   生产者发送过来的订单基本信息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.pay.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenLessonPay(OrderBasicDTO dto) {
        log.info("LessonChangeListener接收到MQ消息");
        // 1. 健壮性处理
        if (dto == null || dto.getUserId() == null || CollUtils.isEmpty(dto.getCourseIds())) {
            log.error("接收到MQ消息有误，订单数据为空 dto: {}", dto);
            return ;    // 这里不要抛出异常，否则会导致MQ消息一直重试
        }
        // 2. 添加课程，将课程保存到课表中
        log.info("监听到用户{}的订单{}支付成功，需要添加课程{}到课表中", dto.getUserId(), dto.getOrderId(), dto.getCourseIds());
        lessonService.addUserLessons(dto.getUserId(), dto.getCourseIds());
    }
}
