package com.example.admin.controller;

import com.example.admin.controller.dto.AdminAuthRequest;
import com.example.admin.controller.dto.DeviceBindingRequest;
import com.example.admin.entity.AdminUserEntity;
import com.example.admin.entity.DeviceAdminBindingEntity;
import com.example.admin.service.AdminAuthService;
import com.example.admin.service.DeviceAdminBindingService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminAuthService adminAuthService;
    private final DeviceAdminBindingService bindingService;

    public AdminController(AdminAuthService adminAuthService,
                           DeviceAdminBindingService bindingService) {
        this.adminAuthService = adminAuthService;
        this.bindingService = bindingService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AdminAuthRequest request) {
        try {
            AdminUserEntity entity = adminAuthService.register(request.getUsername(), request.getPassword(), request.getDisplayName());
            return ResponseEntity.ok(Map.of(
                    "username", entity.getUsername(),
                    "displayName", entity.getDisplayName()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AdminAuthRequest request) {
        try {
            String token = adminAuthService.loginAndIssueToken(request.getUsername(), request.getPassword());
            AdminUserEntity entity = adminAuthService.findByUsername(request.getUsername());
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "username", entity == null ? request.getUsername() : entity.getUsername(),
                    "displayName", entity == null ? request.getUsername() : entity.getDisplayName()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(401).body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.status(401).build();
        }
        AdminUserEntity entity = adminAuthService.findByUsername(userId);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "username", entity.getUsername(),
                "displayName", entity.getDisplayName()
        ));
    }

    @GetMapping("/bindings")
    public List<DeviceAdminBindingEntity> listBindings() {
        return bindingService.listAll();
    }

    @PostMapping("/bindings")
    public ResponseEntity<?> bind(@RequestBody DeviceBindingRequest request) {
        try {
            DeviceAdminBindingEntity entity = bindingService.bind(request.getDeviceId(), request.getAdminId());
            return ResponseEntity.ok(entity);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/bindings/{deviceId}")
    public Map<String, Object> unbind(@PathVariable String deviceId) {
        bindingService.unbind(deviceId);
        return Map.of("success", true, "deviceId", deviceId);
    }
}