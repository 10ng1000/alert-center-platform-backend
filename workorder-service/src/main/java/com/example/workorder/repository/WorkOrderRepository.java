package com.example.workorder.repository;

import com.example.workorder.entity.WorkOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkOrderRepository extends JpaRepository<WorkOrderEntity, String> {
	List<WorkOrderEntity> findByStatusOrderByCreatedAtDesc(String status);

	List<WorkOrderEntity> findByAssigneeOrderByCreatedAtDesc(String assignee);

	List<WorkOrderEntity> findByAssigneeAndStatusOrderByCreatedAtDesc(String assignee, String status);

	List<WorkOrderEntity> findByStatusAndLastNotifiedAtBeforeOrderByLastNotifiedAtAsc(String status,
																					   LocalDateTime lastNotifiedAt);
}
