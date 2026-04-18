package com.example.workorder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "workorder.retry")
public class WorkOrderRetryProperties {

    private boolean enabled = true;
    private long timeoutMinutes = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(long timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }
}
