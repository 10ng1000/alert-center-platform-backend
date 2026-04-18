package com.example.admin.repository;

import com.example.admin.entity.AdminUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUserEntity, String> {

    Optional<AdminUserEntity> findByUsername(String username);
}