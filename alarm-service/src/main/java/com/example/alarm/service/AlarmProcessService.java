package com.example.alarm.service;

import com.example.alarm.mq.AlarmEventPublisher;
import com.example.common.dto.AlarmEvent;
import com.example.common.dto.DeviceAlarmRequest;
import com.example.common.enums.AlarmLevel;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AlarmProcessService {

    private final AlarmAggregationService aggregationService;
    private final AlarmEventPublisher alarmEventPublisher;
    private final DeviceProfileService deviceProfileService;
    private final WorkOrderCooldownService workOrderCooldownService;

    public AlarmProcessService(AlarmAggregationService aggregationService,
                               AlarmEventPublisher alarmEventPublisher,
                               DeviceProfileService deviceProfileService,
                               WorkOrderCooldownService workOrderCooldownService) {
        this.aggregationService = aggregationService;
        this.alarmEventPublisher = alarmEventPublisher;
        this.deviceProfileService = deviceProfileService;
        this.workOrderCooldownService = workOrderCooldownService;
    }

    public boolean process(DeviceAlarmRequest request) {
        if (aggregationService.shouldDropInWindow(request)) {
            return false;
        }

        if (workOrderCooldownService.isInCooldown(request.getDeviceId(), request.getCode())) {
            return false;
        }

        AlarmLevel level = aggregationService.upgradeLevel(request);
        AlarmEvent event = new AlarmEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setDeviceId(request.getDeviceId());
        event.setAlarmCode(request.getCode());
        event.setContent(request.getMessage());
        event.setLevel(level);
        event.setTimestamp(request.getTimestamp());
        event.setProfileTag(deviceProfileService.getProfileTag(request.getDeviceId()));

        alarmEventPublisher.publish(event);
        return true;
    }
}
