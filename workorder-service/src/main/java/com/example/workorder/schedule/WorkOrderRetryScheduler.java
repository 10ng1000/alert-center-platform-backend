package com.example.workorder.schedule;

import com.example.common.constant.TopicConstants;
import com.example.common.dto.NotificationEvent;
import com.example.workorder.config.WorkOrderRetryProperties;
import com.example.workorder.entity.WorkOrderEntity;
import com.example.workorder.service.WorkOrderService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WorkOrderRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderRetryScheduler.class);

    private final WorkOrderService workOrderService;
    private final WorkOrderRetryProperties retryProperties;
    private final RocketMQTemplate rocketMQTemplate;

    public WorkOrderRetryScheduler(WorkOrderService workOrderService,
                                   WorkOrderRetryProperties retryProperties,
                                   RocketMQTemplate rocketMQTemplate) {
        this.workOrderService = workOrderService;
        this.retryProperties = retryProperties;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Scheduled(fixedDelayString = "${workorder.retry.scan-fixed-delay-ms:60000}")
    public void resendTimeoutWorkOrders() {
        if (!retryProperties.isEnabled()) {
            return;
        }

        List<WorkOrderEntity> timeoutWorkOrders = workOrderService
                .listTimeoutPendingWorkOrders(retryProperties.getTimeoutMinutes());
        if (timeoutWorkOrders.isEmpty()) {
            return;
        }

        for (WorkOrderEntity workOrderEntity : timeoutWorkOrders) {
            NotificationEvent notificationEvent = workOrderService.buildNotification(workOrderEntity, true);
            rocketMQTemplate.convertAndSend(TopicConstants.WORKORDER_TOPIC, notificationEvent);
            workOrderService.markNotificationSent(workOrderEntity.getId());
        }

        log.info("Resent {} timeout pending work orders", timeoutWorkOrders.size());
    }
}
