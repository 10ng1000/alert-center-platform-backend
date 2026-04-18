package com.example.workorder.repository;

import com.example.workorder.entity.MqConsumeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MqConsumeRecordRepository extends JpaRepository<MqConsumeRecord, String> {
}
