package com.example.admin.service;

import com.example.admin.entity.AdminUserEntity;
import com.example.admin.entity.DeviceAdminBindingEntity;
import com.example.admin.repository.DeviceAdminBindingRepository;
import com.example.common.dubbo.AdminAssignmentRpcService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@DubboService
public class DeviceAdminBindingService implements AdminAssignmentRpcService {

    public static final String DEFAULT_ASSIGNEE = "ops-team-a";

    private final DeviceAdminBindingRepository bindingRepository;
    private final AdminAuthService adminAuthService;

    public DeviceAdminBindingService(DeviceAdminBindingRepository bindingRepository,
                                     AdminAuthService adminAuthService) {
        this.bindingRepository = bindingRepository;
        this.adminAuthService = adminAuthService;
    }

    public DeviceAdminBindingEntity bind(String deviceId, String adminId) {
        if (!StringUtils.hasText(deviceId) || !StringUtils.hasText(adminId)) {
            throw new IllegalArgumentException("deviceId 和 adminId 不能为空");
        }

        AdminUserEntity adminUserEntity = adminAuthService.findByUsername(adminId);
        if (adminUserEntity == null) {
            throw new IllegalArgumentException("管理员不存在: " + adminId);
        }

        DeviceAdminBindingEntity entity = bindingRepository.findByDeviceId(deviceId.trim())
                .orElseGet(DeviceAdminBindingEntity::new);
        if (!StringUtils.hasText(entity.getId())) {
            entity.setId(UUID.randomUUID().toString());
        }
        entity.setDeviceId(deviceId.trim());
        entity.setAdminId(adminUserEntity.getUsername());
        return bindingRepository.save(entity);
    }

    public void unbind(String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            return;
        }
        bindingRepository.findByDeviceId(deviceId.trim()).ifPresent(bindingRepository::delete);
    }

    @Override
    public String resolveAssignee(String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            return DEFAULT_ASSIGNEE;
        }
        return bindingRepository.findByDeviceId(deviceId.trim())
                .map(DeviceAdminBindingEntity::getAdminId)
                .orElse(DEFAULT_ASSIGNEE);
    }

    public List<DeviceAdminBindingEntity> listAll() {
        return bindingRepository.findAll();
    }
}