package com.example.alarm.service;

import com.example.common.dubbo.DeviceProfileRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class DeviceProfileService {

    @DubboReference(check = false)
    private DeviceProfileRpcService deviceProfileRpcService;

    @Cacheable(cacheNames = "deviceProfile", key = "#deviceId", sync = true)
    public String getProfileTag(String deviceId) {
        if (deviceProfileRpcService == null) {
            return "mock-tag";
        }
        return deviceProfileRpcService.getProfileTag(deviceId);
    }
}
