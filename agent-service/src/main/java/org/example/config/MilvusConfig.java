package org.example.config;

import io.milvus.v2.client.MilvusClientV2;
import org.example.client.MilvusClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 配置类
 * 负责创建和管理 Milvus 客户端 Bean
 */
@Configuration
public class MilvusConfig {

    private static final Logger logger = LoggerFactory.getLogger(MilvusConfig.class);

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2() {
        logger.info("正在初始化 Milvus v2 客户端...");
        MilvusClientV2 milvusClientV2 = milvusClientFactory.createClientV2();
        logger.info("Milvus v2 客户端初始化完成");
        return milvusClientV2;
    }
}
