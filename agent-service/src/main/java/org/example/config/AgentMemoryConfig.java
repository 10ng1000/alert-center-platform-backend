package org.example.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import com.alibaba.cloud.ai.graph.store.Store;
import org.example.service.memory.RedisLongTermStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * Agent 记忆配置：
 * - 短期记忆使用 RedisSaver 持久化会话状态。
 * - 长期记忆使用 Store 抽象，底层落 Redis。
 */
@Configuration
public class AgentMemoryConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = String.format("redis://%s:%d", redisHost, redisPort);
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase);

        if (StringUtils.hasText(redisPassword)) {
            singleServerConfig.setPassword(redisPassword);
        }

        return Redisson.create(config);
    }

    @Bean
    public BaseCheckpointSaver checkpointSaver(RedissonClient redissonClient) {
        return RedisSaver.builder()
                .redisson(redissonClient)
                .stateSerializer(new SpringAIStateSerializer())
                .build();
    }

    @Bean
    public Store longTermMemoryStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisLongTermStore(stringRedisTemplate, "agent:memory:store:");
    }
}
