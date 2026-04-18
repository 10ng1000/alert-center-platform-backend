package com.example.gateway.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Component
@ConditionalOnProperty(prefix = "gateway.nacos.route-publish", name = "enabled", havingValue = "true")
public class NacosGatewayRoutePublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NacosGatewayRoutePublisher.class);

    private final ResourceLoader resourceLoader;

    @Value("${spring.cloud.nacos.config.server-addr}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.username:nacos}")
    private String username;

    @Value("${spring.cloud.nacos.password:nacos}")
    private String password;

    @Value("${gateway.nacos.route-publish.data-id:gateway-routes.yaml}")
    private String dataId;

    @Value("${gateway.nacos.route-publish.group:DEFAULT_GROUP}")
    private String group;

    @Value("${gateway.nacos.route-publish.type:yaml}")
    private String type;

    @Value("${gateway.nacos.route-publish.classpath-file:gateway-routes.yaml}")
    private String classpathFile;

    public NacosGatewayRoutePublisher(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        String content = loadRouteConfigContent();
        if (content == null || content.isBlank()) {
            log.warn("Skip publishing gateway routes to Nacos because content is empty. dataId={}, group={}", dataId, group);
            return;
        }

        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.USERNAME, username);
        properties.setProperty(PropertyKeyConst.PASSWORD, password);

        try {
            ConfigService configService = NacosFactory.createConfigService(properties);
            boolean published = configService.publishConfig(dataId, group, content, type);
            if (published) {
                log.info("Published gateway routes to Nacos successfully. dataId={}, group={}", dataId, group);
            } else {
                log.warn("Failed to publish gateway routes to Nacos. dataId={}, group={}", dataId, group);
            }
        } catch (Exception ex) {
            log.error("Error while publishing gateway routes to Nacos. dataId={}, group={}", dataId, group, ex);
        }
    }

    private String loadRouteConfigContent() {
        Resource resource = resourceLoader.getResource("classpath:" + classpathFile);
        if (!resource.exists()) {
            log.warn("Gateway route file does not exist: classpath:{}", classpathFile);
            return null;
        }

        try {
            try (InputStream inputStream = resource.getInputStream()) {
                byte[] bytes = inputStream.readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            log.error("Failed to read gateway route file from classpath: {}", classpathFile, ex);
            return null;
        }
    }
}
