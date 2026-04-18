package com.example.workorder.controller;

import com.example.workorder.entity.WorkOrderEntity;
import com.example.workorder.service.WorkOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/workorder")
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    public WorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping("/list")
    public List<WorkOrderEntity> list() {
        return workOrderService.listWorkOrders();
    }

    @GetMapping("/list/pending")
    public List<WorkOrderEntity> listPending() {
        return workOrderService.listPendingWorkOrders();
    }

    @GetMapping("/list/completed")
    public List<WorkOrderEntity> listCompleted() {
        return workOrderService.listCompletedWorkOrders();
    }

    @GetMapping("/list/my")
    public ResponseEntity<List<WorkOrderEntity>> listMy(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(workOrderService.listWorkOrdersByAssignee(userId));
    }

    @GetMapping("/list/my/pending")
    public ResponseEntity<List<WorkOrderEntity>> listMyPending(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(workOrderService.listWorkOrdersByAssigneeAndStatus(userId, WorkOrderService.STATUS_PENDING));
    }

    @GetMapping("/list/my/completed")
    public ResponseEntity<List<WorkOrderEntity>> listMyCompleted(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(workOrderService.listWorkOrdersByAssigneeAndStatus(userId, WorkOrderService.STATUS_COMPLETED));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkOrderEntity> getById(@PathVariable String id) {
        WorkOrderEntity workOrderEntity = workOrderService.getWorkOrderById(id);
        if (workOrderEntity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workOrderEntity);
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<WorkOrderEntity> complete(@PathVariable String id,
                                                    @RequestParam(required = false) String assignee) {
        WorkOrderEntity workOrderEntity = workOrderService.completeWorkOrder(id, assignee);
        if (workOrderEntity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workOrderEntity);
    }

    @PostMapping("/{id}/my-complete")
    public ResponseEntity<WorkOrderEntity> myComplete(@PathVariable String id,
                                                      @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.status(401).build();
        }
        WorkOrderEntity workOrderEntity = workOrderService.completeByAssignee(id, userId);
        if (workOrderEntity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workOrderEntity);
    }
}
