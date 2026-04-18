package com.example.workorder;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo
@EnableCaching
@EnableScheduling
public class WorkOrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkOrderApplication.class, args);
    }
}
