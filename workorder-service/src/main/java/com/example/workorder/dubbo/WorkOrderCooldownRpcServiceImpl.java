package com.example.workorder.dubbo;

import com.example.common.dubbo.WorkOrderCooldownRpcService;
import com.example.workorder.service.WorkOrderNotificationCooldownService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class WorkOrderCooldownRpcServiceImpl implements WorkOrderCooldownRpcService {

    private final WorkOrderNotificationCooldownService cooldownService;

    public WorkOrderCooldownRpcServiceImpl(WorkOrderNotificationCooldownService cooldownService) {
        this.cooldownService = cooldownService;
    }

    @Override
    public boolean isInCooldown(String deviceId, String alarmCode) {
        return cooldownService.isInCooldown(deviceId, alarmCode);
    }
}