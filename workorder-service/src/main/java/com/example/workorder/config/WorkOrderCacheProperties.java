package com.example.workorder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "workorder.cache")
public class WorkOrderCacheProperties {

    private Duration listTtl = Duration.ofSeconds(30);
    private Duration itemTtl = Duration.ofMinutes(5);
    private Duration nullTtl = Duration.ofMinutes(1);
    private long ttlJitterSeconds = 15;
    private long bloomExpectedInsertions = 10000L;
    private double bloomFalsePositiveRate = 0.01d;
    private Duration lockWaitTime = Duration.ofMillis(200);
    private Duration lockLeaseTime = Duration.ofSeconds(10);

    public Duration getListTtl() {
        return listTtl;
    }

    public void setListTtl(Duration listTtl) {
        this.listTtl = listTtl;
    }

    public Duration getItemTtl() {
        return itemTtl;
    }

    public void setItemTtl(Duration itemTtl) {
        this.itemTtl = itemTtl;
    }

    public Duration getNullTtl() {
        return nullTtl;
    }

    public void setNullTtl(Duration nullTtl) {
        this.nullTtl = nullTtl;
    }

    public long getTtlJitterSeconds() {
        return ttlJitterSeconds;
    }

    public void setTtlJitterSeconds(long ttlJitterSeconds) {
        this.ttlJitterSeconds = ttlJitterSeconds;
    }

    public long getBloomExpectedInsertions() {
        return bloomExpectedInsertions;
    }

    public void setBloomExpectedInsertions(long bloomExpectedInsertions) {
        this.bloomExpectedInsertions = bloomExpectedInsertions;
    }

    public double getBloomFalsePositiveRate() {
        return bloomFalsePositiveRate;
    }

    public void setBloomFalsePositiveRate(double bloomFalsePositiveRate) {
        this.bloomFalsePositiveRate = bloomFalsePositiveRate;
    }

    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    public void setLockWaitTime(Duration lockWaitTime) {
        this.lockWaitTime = lockWaitTime;
    }

    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    public void setLockLeaseTime(Duration lockLeaseTime) {
        this.lockLeaseTime = lockLeaseTime;
    }
}