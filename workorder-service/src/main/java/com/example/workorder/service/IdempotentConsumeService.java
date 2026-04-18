package com.example.workorder.service;

import com.example.workorder.entity.MqConsumeRecord;
import com.example.workorder.repository.MqConsumeRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotentConsumeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final MqConsumeRecordRepository recordRepository;

    public IdempotentConsumeService(StringRedisTemplate stringRedisTemplate,
                                    MqConsumeRecordRepository recordRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.recordRepository = recordRepository;
    }

    public boolean tryConsume(String eventId) {
        String redisKey = "consume:dedup:" + eventId;
        Boolean firstTime = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "1", Duration.ofMinutes(30));
        if (Boolean.FALSE.equals(firstTime)) {
            return false;
        }

        try {
            recordRepository.save(new MqConsumeRecord(eventId));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}
