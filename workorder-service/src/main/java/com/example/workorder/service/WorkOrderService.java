package com.example.workorder.service;

import com.example.common.dto.AlarmEvent;
import com.example.common.dto.NotificationEvent;
import com.example.common.dubbo.AdminAssignmentRpcService;
import com.example.workorder.entity.WorkOrderEntity;
import com.example.workorder.repository.WorkOrderRepository;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WorkOrderService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String DEFAULT_ASSIGNEE = "ops-team-a";

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderNotificationCooldownService cooldownService;

    @DubboReference(check = false)
    private AdminAssignmentRpcService adminAssignmentRpcService;

    public WorkOrderService(WorkOrderRepository workOrderRepository,
                            WorkOrderNotificationCooldownService cooldownService) {
        this.workOrderRepository = workOrderRepository;
        this.cooldownService = cooldownService;
    }

    @Cacheable(cacheNames = "workOrderList", sync = true)
    public List<WorkOrderEntity> listWorkOrders() {
        return workOrderRepository.findAll();
    }

    @Cacheable(cacheNames = "workOrderByStatus", key = "#status", sync = true)
    public List<WorkOrderEntity> listWorkOrdersByStatus(String status) {
        return workOrderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    public List<WorkOrderEntity> listPendingWorkOrders() {
        return listWorkOrdersByStatus(STATUS_PENDING);
    }

    public List<WorkOrderEntity> listCompletedWorkOrders() {
        return listWorkOrdersByStatus(STATUS_COMPLETED);
    }

    @Cacheable(cacheNames = "workOrderByAssignee", key = "#assignee", sync = true)
    public List<WorkOrderEntity> listWorkOrdersByAssignee(String assignee) {
        return workOrderRepository.findByAssigneeOrderByCreatedAtDesc(assignee);
    }

    @Cacheable(cacheNames = "workOrderByAssigneeAndStatus", key = "#assignee + ':' + #status", sync = true)
    public List<WorkOrderEntity> listWorkOrdersByAssigneeAndStatus(String assignee, String status) {
        return workOrderRepository.findByAssigneeAndStatusOrderByCreatedAtDesc(assignee, status);
    }

    @Cacheable(cacheNames = "workOrderById", key = "#workOrderId", sync = true)
    public WorkOrderEntity getWorkOrderById(String workOrderId) {
        return workOrderRepository.findById(workOrderId).orElse(null);
    }

    @CacheEvict(cacheNames = {"workOrderList", "workOrderById", "workOrderByStatus", "workOrderByAssignee", "workOrderByAssigneeAndStatus"}, allEntries = true)
    public WorkOrderEntity createFromAlarm(AlarmEvent alarmEvent) {
        WorkOrderEntity entity = new WorkOrderEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setAlarmEventId(alarmEvent.getEventId());
        entity.setAlarmCode(alarmEvent.getAlarmCode());
        entity.setDeviceId(alarmEvent.getDeviceId());
        entity.setStatus(STATUS_PENDING);
        entity.setAssignee(resolveAssignee(alarmEvent.getDeviceId()));
        return workOrderRepository.save(entity);
    }

    @CacheEvict(cacheNames = {"workOrderList", "workOrderById", "workOrderByStatus", "workOrderByAssignee", "workOrderByAssigneeAndStatus"}, allEntries = true)
    public WorkOrderEntity completeWorkOrder(String workOrderId, String assignee) {
        WorkOrderEntity entity = getWorkOrderById(workOrderId);
        if (entity == null) {
            return null;
        }

        if (STATUS_COMPLETED.equals(entity.getStatus())) {
            return entity;
        }

        entity.setStatus(STATUS_COMPLETED);
        if (StringUtils.hasText(assignee)) {
            entity.setAssignee(assignee);
        }
        WorkOrderEntity saved = workOrderRepository.save(entity);
        cooldownService.activateCooldown(saved.getDeviceId(), saved.getAlarmCode());
        return saved;
    }

    @CacheEvict(cacheNames = {"workOrderList", "workOrderById", "workOrderByStatus", "workOrderByAssignee", "workOrderByAssigneeAndStatus"}, allEntries = true)
    public WorkOrderEntity completeByAssignee(String workOrderId, String assignee) {
        if (!StringUtils.hasText(assignee)) {
            return null;
        }
        WorkOrderEntity entity = getWorkOrderById(workOrderId);
        if (entity == null || !assignee.equals(entity.getAssignee())) {
            return null;
        }
        return completeWorkOrder(workOrderId, assignee);
    }

    public List<WorkOrderEntity> listTimeoutPendingWorkOrders(long timeoutMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        return workOrderRepository.findByStatusAndLastNotifiedAtBeforeOrderByLastNotifiedAtAsc(
                STATUS_PENDING, deadline);
    }

    public void markNotificationSent(String workOrderId) {
        WorkOrderEntity entity = getWorkOrderById(workOrderId);
        if (entity == null) {
            return;
        }
        entity.setLastNotifiedAt(LocalDateTime.now());
        workOrderRepository.save(entity);
    }

    public NotificationEvent buildNotification(WorkOrderEntity workOrderEntity, boolean retry) {
        NotificationEvent event = new NotificationEvent();
        event.setNotificationId(UUID.randomUUID().toString());
        event.setWorkOrderId(workOrderEntity.getId());
        event.setChannel("SMS");
        event.setReceiver(workOrderEntity.getAssignee());
        event.setContent((retry ? "工单长时间未处理，重新提醒: " : "新工单已创建: ") + workOrderEntity.getId());
        return event;
    }

    private String resolveAssignee(String deviceId) {
        try {
            if (adminAssignmentRpcService == null) {
                return DEFAULT_ASSIGNEE;
            }
            String assignee = adminAssignmentRpcService.resolveAssignee(deviceId);
            return StringUtils.hasText(assignee) ? assignee : DEFAULT_ASSIGNEE;
        } catch (Exception ex) {
            return DEFAULT_ASSIGNEE;
        }
    }
}
