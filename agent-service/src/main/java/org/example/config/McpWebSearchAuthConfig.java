package org.example.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * Adds DashScope API key to outgoing MCP HTTP requests.
 */
@Configuration
public class McpWebSearchAuthConfig {

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Bean
    public McpSyncHttpClientRequestCustomizer mcpSyncHttpClientRequestCustomizer() {
        return (requestBuilder, method, uri, body, context) -> {
            if (!StringUtils.hasText(dashscopeApiKey) || "your-api-key-here".equals(dashscopeApiKey)) {
                return;
            }

            if (isDashScopeHost(uri)) {
                requestBuilder.header("Authorization", "Bearer " + dashscopeApiKey.trim());
            }
        };
    }

    private boolean isDashScopeHost(URI uri) {
        return uri != null && uri.getHost() != null && uri.getHost().contains("dashscope.aliyuncs.com");
    }
}