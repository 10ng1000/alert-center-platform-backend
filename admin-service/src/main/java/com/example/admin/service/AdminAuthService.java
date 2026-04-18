package com.example.admin.service;

import com.example.admin.entity.AdminUserEntity;
import com.example.admin.repository.AdminUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @Value("${security.jwt.expire-minutes:120}")
    private long jwtExpireMinutes;

    public AdminAuthService(AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AdminUserEntity register(String username, String password, String displayName) {
        validateUsernameAndPassword(username, password);
        String normalizedUsername = username.trim();
        if (adminUserRepository.findByUsername(normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }

        AdminUserEntity entity = new AdminUserEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUsername(normalizedUsername);
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : normalizedUsername);
        return adminUserRepository.save(entity);
    }

    public String loginAndIssueToken(String username, String password) {
        validateUsernameAndPassword(username, password);
        AdminUserEntity entity = adminUserRepository.findByUsername(username.trim())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!passwordEncoder.matches(password, entity.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(jwtExpireMinutes));
        return Jwts.builder()
                .setSubject(entity.getUsername())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    public AdminUserEntity findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return adminUserRepository.findByUsername(username.trim()).orElse(null);
    }

    private void validateUsernameAndPassword(String username, String password) {
        if (!StringUtils.hasText(username) || username.trim().length() < 3) {
            throw new IllegalArgumentException("用户名长度至少 3 位");
        }
        if (!StringUtils.hasText(password) || password.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
    }
}