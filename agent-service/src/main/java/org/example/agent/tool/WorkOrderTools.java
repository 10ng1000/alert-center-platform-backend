package org.example.agent.tool;

import com.example.common.dubbo.WorkOrderRpcService;
import com.example.common.dto.WorkOrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.annotation.DubboReference;
import org.example.context.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkOrderTools {

	private static final Logger logger = LoggerFactory.getLogger(WorkOrderTools.class);

	public static final String TOOL_GET_MY_WORK_ORDERS = "getMyWorkOrders";
	public static final String TOOL_COMPLETE_MY_PENDING_WORK_ORDER = "completeMyPendingWorkOrder";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@DubboReference(check = false)
	private WorkOrderRpcService workOrderRpcService;

	@Tool(description = "Get current user's work orders from workorder center via Dubbo.")
	public String getMyWorkOrders() {
		String userId = UserContextHolder.getCurrentUser();
		if (!StringUtils.hasText(userId)) {
			return buildErrorResponse("无法获取当前用户身份");
		}
		if (workOrderRpcService == null) {
			return buildErrorResponse("工单中心 Dubbo 服务不可用");
		}

		try {
			List<WorkOrderEvent> workOrders = workOrderRpcService.listMyWorkOrders(userId);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("userId", userId);
			result.put("count", workOrders == null ? 0 : workOrders.size());
			result.put("workOrders", workOrders);

			return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
		} catch (Exception e) {
			logger.error("查询当前用户工单失败, userId={}", userId, e);
			return buildErrorResponse("查询当前用户工单失败: " + e.getMessage());
		}
	}

	@Tool(description = "Complete one unconfirmed (pending) work order for current user via Dubbo.")
	public String completeMyPendingWorkOrder() {
		String userId = UserContextHolder.getCurrentUser();
		if (!StringUtils.hasText(userId)) {
			return buildErrorResponse("无法获取当前用户身份");
		}
		if (workOrderRpcService == null) {
			return buildErrorResponse("工单中心 Dubbo 服务不可用");
		}

		try {
			WorkOrderEvent completed = workOrderRpcService.completeMyPendingWorkOrder(userId);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("userId", userId);

			if (completed == null) {
				result.put("message", "当前用户没有待确认工单");
				result.put("completedWorkOrder", null);
			} else {
				result.put("message", "已完成一条待确认工单");
				result.put("completedWorkOrder", completed);
			}

			return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
		} catch (Exception e) {
			logger.error("完成当前用户待确认工单失败, userId={}", userId, e);
			return buildErrorResponse("完成当前用户待确认工单失败: " + e.getMessage());
		}
	}

	private String buildErrorResponse(String message) {
		try {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", false);
			result.put("message", message);
			return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
		} catch (Exception e) {
			return "{\"success\":false,\"message\":\"" + message + "\"}";
		}
	}
}
