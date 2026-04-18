package com.example.alarm.controller;

import com.example.alarm.service.AlarmProcessService;
import com.example.common.dto.DeviceAlarmRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/alarm")
public class AlarmController {

    private final AlarmProcessService alarmProcessService;

    public AlarmController(AlarmProcessService alarmProcessService) {
        this.alarmProcessService = alarmProcessService;
    }

    @PostMapping("/trigger")
    public Map<String, Object> trigger(@RequestBody DeviceAlarmRequest request) {
        boolean accepted = alarmProcessService.process(request);
        return Map.of("accepted", accepted);
    }
}
