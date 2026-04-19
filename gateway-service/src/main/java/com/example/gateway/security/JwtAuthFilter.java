package com.example.gateway.security;

import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

        private static final List<String> WHITELIST = List.of(
            "/auth/**",
            "/actuator/**",
            "/verify.html",
            "/admin.html",
            "/agent/**",
            "/favicon.ico",
            "/api/agent/auth/login",
            "/api/agent/auth/register",
            "/api/agent/auth/refresh",
            "/api/workorder/admin/register",
            "/api/workorder/admin/login"
        );

    private final JwtTokenService jwtTokenService;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public JwtAuthFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (WHITELIST.stream().anyMatch(pattern -> antPathMatcher.match(pattern, path))) {
            return chain.filter(exchange);
        }

        String token = resolveToken(exchange.getRequest().getHeaders());
        if (token == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            Claims claims = jwtTokenService.parseToken(token);
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        } catch (Exception ex) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private String resolveToken(HttpHeaders headers) {
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7);
    }
}
