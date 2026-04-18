package com.example.notification.service;

import com.example.common.dto.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void send(NotificationEvent event) {
        log.info("Mock send notification: channel={}, receiver={}, content={}",
                event.getChannel(), event.getReceiver(), event.getContent());
    }
}
