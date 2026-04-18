package com.example.notification.mq;

import com.example.common.constant.TopicConstants;
import com.example.common.dto.NotificationEvent;
import com.example.notification.service.NotificationService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = TopicConstants.WORKORDER_TOPIC, consumerGroup = "notification-consumer-group")
public class NotificationMqConsumer implements RocketMQListener<NotificationEvent> {

    private final NotificationService notificationService;

    public NotificationMqConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onMessage(NotificationEvent notificationEvent) {
        notificationService.send(notificationEvent);
    }
}
