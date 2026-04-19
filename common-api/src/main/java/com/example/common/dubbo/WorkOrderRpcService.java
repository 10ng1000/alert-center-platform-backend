package com.example.common.dubbo;

import com.example.common.dto.WorkOrderEvent;

import java.util.List;

public interface WorkOrderRpcService {
    List<WorkOrderEvent> listMyWorkOrders(String assignee);

    WorkOrderEvent completeMyPendingWorkOrder(String assignee);
}