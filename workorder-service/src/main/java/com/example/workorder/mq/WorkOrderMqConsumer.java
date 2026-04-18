package com.example.workorder.mq;

import com.example.common.constant.TopicConstants;
import com.example.common.dto.AlarmEvent;
import com.example.common.dto.NotificationEvent;
import com.example.workorder.entity.WorkOrderEntity;
import com.example.workorder.service.IdempotentConsumeService;
import com.example.workorder.service.WorkOrderNotificationCooldownService;
import com.example.workorder.service.WorkOrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = TopicConstants.ALARM_TOPIC, consumerGroup = "workorder-consumer-group")
public class WorkOrderMqConsumer implements RocketMQListener<AlarmEvent> {

    private final IdempotentConsumeService idempotentConsumeService;
    private final WorkOrderNotificationCooldownService cooldownService;
    private final WorkOrderService workOrderService;
    private final RocketMQTemplate rocketMQTemplate;

    public WorkOrderMqConsumer(IdempotentConsumeService idempotentConsumeService,
                               WorkOrderNotificationCooldownService cooldownService,
                               WorkOrderService workOrderService,
                               RocketMQTemplate rocketMQTemplate) {
        this.idempotentConsumeService = idempotentConsumeService;
        this.cooldownService = cooldownService;
        this.workOrderService = workOrderService;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void onMessage(AlarmEvent alarmEvent) {
        if (!idempotentConsumeService.tryConsume(alarmEvent.getEventId())) {
            return;
        }

        if (cooldownService.isInCooldown(alarmEvent.getDeviceId(), alarmEvent.getAlarmCode())) {
            return;
        }

        WorkOrderEntity workOrderEntity = workOrderService.createFromAlarm(alarmEvent);
        NotificationEvent notificationEvent = workOrderService.buildNotification(workOrderEntity, false);
        rocketMQTemplate.convertAndSend(TopicConstants.WORKORDER_TOPIC, notificationEvent);
        workOrderService.markNotificationSent(workOrderEntity.getId());
    }
}
