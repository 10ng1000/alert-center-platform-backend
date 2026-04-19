package com.example.workorder.dubbo;

import com.example.common.dubbo.WorkOrderRpcService;
import com.example.common.dto.WorkOrderEvent;
import com.example.workorder.entity.WorkOrderEntity;
import com.example.workorder.service.WorkOrderService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@DubboService
public class WorkOrderRpcServiceImpl implements WorkOrderRpcService {

    private final WorkOrderService workOrderService;

    public WorkOrderRpcServiceImpl(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @Override
    public List<WorkOrderEvent> listMyWorkOrders(String assignee) {
        if (!StringUtils.hasText(assignee)) {
            return Collections.emptyList();
        }

        return workOrderService.listWorkOrdersByAssignee(assignee).stream()
                .map(this::toEvent)
                .toList();
    }

    @Override
    public WorkOrderEvent completeMyPendingWorkOrder(String assignee) {
        if (!StringUtils.hasText(assignee)) {
            return null;
        }

        List<WorkOrderEntity> pendingList = workOrderService
                .listWorkOrdersByAssigneeAndStatus(assignee, WorkOrderService.STATUS_PENDING);
        if (pendingList.isEmpty()) {
            return null;
        }

        WorkOrderEntity target = pendingList.get(0);
        WorkOrderEntity completed = workOrderService.completeByAssignee(target.getId(), assignee);
        return completed == null ? null : toEvent(completed);
    }

    private WorkOrderEvent toEvent(WorkOrderEntity entity) {
        WorkOrderEvent event = new WorkOrderEvent();
        event.setWorkOrderId(entity.getId());
        event.setAlarmEventId(entity.getAlarmEventId());
        event.setDeviceId(entity.getDeviceId());
        event.setStatus(entity.getStatus());
        event.setAssignee(entity.getAssignee());
        return event;
    }
}