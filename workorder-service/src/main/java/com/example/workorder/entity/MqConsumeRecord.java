package com.example.workorder.entity;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "mq_consume_record")
public class MqConsumeRecord {
    @Id
    private String eventId;

    public MqConsumeRecord() {
    }

    public MqConsumeRecord(String eventId) {
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }
}
