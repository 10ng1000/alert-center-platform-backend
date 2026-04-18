package com.example.alarm.mq;

import com.example.common.constant.TopicConstants;
import com.example.common.dto.AlarmEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class AlarmEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    public AlarmEventPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    public void publish(AlarmEvent alarmEvent) {
        rocketMQTemplate.convertAndSend(TopicConstants.ALARM_TOPIC, alarmEvent);
    }
}
