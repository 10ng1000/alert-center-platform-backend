package com.example.common.dto;

import com.example.common.enums.AlarmLevel;

public class AlarmEvent {
    private String eventId;
    private String deviceId;
    private String alarmCode;
    private String content;
    private AlarmLevel level;
    private String profileTag;
    private long timestamp;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getAlarmCode() {
        return alarmCode;
    }

    public void setAlarmCode(String alarmCode) {
        this.alarmCode = alarmCode;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public AlarmLevel getLevel() {
        return level;
    }

    public void setLevel(AlarmLevel level) {
        this.level = level;
    }

    public String getProfileTag() {
        return profileTag;
    }

    public void setProfileTag(String profileTag) {
        this.profileTag = profileTag;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
