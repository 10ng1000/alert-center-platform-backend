package com.example.alarm.service;

import com.example.common.dubbo.WorkOrderCooldownRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

@Service
public class WorkOrderCooldownService {

    @DubboReference(check = false)
    private WorkOrderCooldownRpcService workOrderCooldownRpcService;

    public boolean isInCooldown(String deviceId, String alarmCode) {
        if (workOrderCooldownRpcService == null) {
            return false;
        }
        return workOrderCooldownRpcService.isInCooldown(deviceId, alarmCode);
    }
}