package com.example.alarm.service;

import com.example.common.dto.DeviceAlarmRequest;
import com.example.common.enums.AlarmLevel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AlarmAggregationService {

    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(2);

    private final StringRedisTemplate stringRedisTemplate;

    public AlarmAggregationService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean shouldDropInWindow(DeviceAlarmRequest request) {
        String key = "alarm:window:" + request.getDeviceId() + ":" + request.getCode();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", DEDUP_WINDOW);
        return Boolean.FALSE.equals(success);
    }

    public AlarmLevel upgradeLevel(DeviceAlarmRequest request) {
        String key = "alarm:counter:" + request.getDeviceId() + ":" + request.getCode();
        Long count = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, Duration.ofMinutes(10));
        if (count != null && count >= 5 && request.getLevel() == AlarmLevel.WARN) {
            return AlarmLevel.CRITICAL;
        }
        return request.getLevel();
    }
}
