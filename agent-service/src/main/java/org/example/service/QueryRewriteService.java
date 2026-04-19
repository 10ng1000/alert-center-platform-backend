package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写服务
 * 在检索前将用户原始问题改写为更适合向量/BM25召回的检索查询。
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean enabled;

    @Value("${rag.query-rewrite.model:qwen-plus}")
    private String model;

    @Value("${rag.query-rewrite.max-length:200}")
    private int maxLength;

    private Generation generation;

    @PostConstruct
    public void init() {
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        generation = new Generation();
        logger.info("QueryRewrite 初始化完成，enabled: {}, model: {}", enabled, model);
    }

    /**
     * 对检索查询做轻量改写。若改写失败或结果为空，回退到原始 query。
     */
    public String rewriteForRetrieval(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return originalQuery;
        }
        if (!enabled) {
            return originalQuery;
        }

        try {
            String prompt = buildRewritePrompt(originalQuery);
            List<Message> messages = List.of(Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build());

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .resultFormat("message")
                    .messages(messages)
                    .build();

            GenerationResult result = generation.call(param);
            String rewritten = extractContent(result);
            String normalized = normalizeRewrittenQuery(rewritten, originalQuery);

            if (!normalized.equals(originalQuery)) {
                logger.info("QueryRewrite 生效，original: {}, rewritten: {}", originalQuery, normalized);
            }

            return normalized;
        } catch (Exception e) {
            logger.warn("QueryRewrite 失败，回退原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }

    private String buildRewritePrompt(String query) {
        return "你是检索优化助手。请将用户问题改写为适合知识库检索的一条短查询。\n"
                + "要求：\n"
                + "1) 保留原意和关键实体；\n"
                + "2) 去掉寒暄和无关信息；\n"
                + "3) 输出中文短句，不要解释；\n"
                + "4) 只输出改写后的查询文本。\n\n"
                + "用户问题：" + query;
    }

    private String extractContent(GenerationResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()
                || result.getOutput().getChoices().get(0).getMessage() == null) {
            return null;
        }
        return result.getOutput().getChoices().get(0).getMessage().getContent();
    }

    private String normalizeRewrittenQuery(String rewritten, String fallback) {
        if (rewritten == null) {
            return fallback;
        }
        String cleaned = rewritten.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replace("改写后查询：", "")
                .replace("改写查询：", "")
                .replace("查询：", "")
                .trim();

        if (cleaned.isBlank()) {
            return fallback;
        }

        int safeMaxLength = Math.max(20, maxLength);
        if (cleaned.length() > safeMaxLength) {
            cleaned = cleaned.substring(0, safeMaxLength);
        }
        return cleaned;
    }
}