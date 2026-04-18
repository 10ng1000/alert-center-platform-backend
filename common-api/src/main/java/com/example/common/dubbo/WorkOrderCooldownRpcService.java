package com.example.common.dubbo;

public interface WorkOrderCooldownRpcService {
    boolean isInCooldown(String deviceId, String alarmCode);
}