package com.example.notification.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/notification")
public class NotificationController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "mode", "mock");
    }
}
