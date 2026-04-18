package com.example.deviceprofile.controller;

import com.example.common.dubbo.DeviceProfileRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/device")
public class DeviceProfileController {

    private final DeviceProfileRpcService deviceProfileRpcService;

    public DeviceProfileController(DeviceProfileRpcService deviceProfileRpcService) {
        this.deviceProfileRpcService = deviceProfileRpcService;
    }

    @GetMapping("/profile")
    public Map<String, String> profile(@RequestParam String deviceId) {
        return Map.of("deviceId", deviceId, "profileTag", deviceProfileRpcService.getProfileTag(deviceId));
    }
}
