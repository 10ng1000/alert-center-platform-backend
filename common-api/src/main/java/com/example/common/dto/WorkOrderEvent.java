package com.example.common.dto;

public class WorkOrderEvent {
    private String workOrderId;
    private String alarmEventId;
    private String deviceId;
    private String status;
    private String assignee;

    public String getWorkOrderId() {
        return workOrderId;
    }

    public void setWorkOrderId(String workOrderId) {
        this.workOrderId = workOrderId;
    }

    public String getAlarmEventId() {
        return alarmEventId;
    }

    public void setAlarmEventId(String alarmEventId) {
        this.alarmEventId = alarmEventId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }
}
