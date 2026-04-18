package com.example.workorder.service;

import com.example.workorder.config.WorkOrderNotificationCooldownProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Service
public class WorkOrderNotificationCooldownService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderNotificationCooldownService.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final WorkOrderNotificationCooldownProperties properties;

    public WorkOrderNotificationCooldownService(StringRedisTemplate stringRedisTemplate,
                                                WorkOrderNotificationCooldownProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    public void activateCooldown(String deviceId, String alarmCode) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = buildKey(deviceId, alarmCode);
        if (key == null) {
            return;
        }

        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofMinutes(properties.getMinutes()));
        log.info("Activated workorder notification cooldown, deviceId={}, alarmCode={}, minutes={}",
                deviceId, alarmCode, properties.getMinutes());
    }

    public boolean isInCooldown(String deviceId, String alarmCode) {
        if (!properties.isEnabled()) {
            return false;
        }
        String key = buildKey(deviceId, alarmCode);
        if (key == null) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    private String buildKey(String deviceId, String alarmCode) {
        if (!StringUtils.hasText(deviceId) || !StringUtils.hasText(alarmCode)) {
            return null;
        }
        return "workorder:notification:cooldown:" + deviceId + ":" + alarmCode;
    }
}