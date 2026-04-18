package com.example.deviceprofile.dubbo;

import com.example.common.dubbo.DeviceProfileRpcService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class DeviceProfileRpcServiceImpl implements DeviceProfileRpcService {
    @Override
    public String getProfileTag(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return "unknown";
        }
        if (deviceId.startsWith("COLD")) {
            return "critical-freezer";
        }
        return "normal-cooling";
    }
}
