package com.example.admin.repository;

import com.example.admin.entity.DeviceAdminBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceAdminBindingRepository extends JpaRepository<DeviceAdminBindingEntity, String> {

    Optional<DeviceAdminBindingEntity> findByDeviceId(String deviceId);
}