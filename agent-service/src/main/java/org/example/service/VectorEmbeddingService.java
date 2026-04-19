package org.example.service;

import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 向量嵌入服务
 * 根据模型自动选择阿里云 DashScope Text Embedding 或 Multimodal Embedding API
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    @Value("${dashscope.embedding.dimension:0}")
    private int embeddingDimension;

    private TextEmbedding textEmbedding;
    private boolean useMultiModalEmbedding;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void init() {
        // 验证 API Key
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            logger.error("API Key 未正确配置！当前值: {}", apiKey);
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY 或在 application.yml 中配置正确的 API Key");
        }
        
        // 打印 API Key 前缀用于调试（不打印完整 Key 保证安全）
        String maskedKey = apiKey.length() > 8 ? 
            apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4) : 
            "***";
        logger.info("API Key 已加载: {}", maskedKey);
        
        // 设置全局 API Key 和服务地址（确保设置成功）
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        
        // 验证 API Key 是否设置成功
        if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
            logger.error("Constants.apiKey 设置失败！");
            throw new IllegalStateException("API Key 设置到 Constants 失败");
        }
        
        logger.info("Constants.apiKey 已设置: {}", Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "...");

        useMultiModalEmbedding = model != null && model.toLowerCase().contains("vl");
        if (useMultiModalEmbedding) {
            logger.info("阿里云 DashScope Multimodal Embedding 服务初始化完成，模型: {}", model);
        } else {
            textEmbedding = new TextEmbedding();
            logger.info("阿里云 DashScope Text Embedding 服务初始化完成，模型: {}", model);
        }
    }

    /**
     * 生成向量嵌入
        * 调用阿里云 DashScope Embedding API（文本或多模态）
     * 
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                logger.warn("内容为空，无法生成向量");
                throw new IllegalArgumentException("内容不能为空");
            }

            logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());
            
            // 确保 API Key 已设置（防止被其他地方覆盖）
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                logger.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }
            
            logger.debug("调用 API 前 Constants.apiKey: {}", 
                Constants.apiKey != null ? Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "..." : "null");

            List<Float> floatEmbedding = useMultiModalEmbedding
                    ? generateMultiModalEmbedding(content)
                    : generateTextEmbedding(content);

            logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}", 
                content.length(), floatEmbedding.size());

            return floatEmbedding;

        } catch (ApiException e) {
            Status status = e.getStatus();
            if (status != null) {
                logger.error("DashScope 调用失败, statusCode={}, code={}, requestId={}, message={}",
                        status.getStatusCode(), status.getCode(), status.getRequestId(), status.getMessage(), e);
                throw new RuntimeException(String.format("DashScope 调用失败(code=%s, requestId=%s): %s",
                        status.getCode(), status.getRequestId(), status.getMessage()), e);
            }
            logger.error("DashScope 调用失败", e);
            throw new RuntimeException("DashScope 调用失败: " + e.getMessage(), e);
        } catch (NoApiKeyException e) {
            logger.error("API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    private List<Float> generateTextEmbedding(String content)
            throws ApiException, NoApiKeyException {
        TextEmbeddingParam param = TextEmbeddingParam
                .builder()
                .apiKey(apiKey)
                .model(model)
                .text(content)
                .build();

        TextEmbeddingResult result = textEmbedding.call(param);
        return getTextEmbeddingFloats(result);
    }

    private List<Float> generateMultiModalEmbedding(String content)
            throws ApiException, NoApiKeyException, UploadFileException {
        // SDK 2.17.0 的 MultiModalEmbeddingOutput 仅定义了 output.embedding，
        // 但 qwen3-vl-embedding 返回 output.embeddings[]，这里优先走原生 HTTP 解析。
        return generateMultiModalEmbeddingByHttp(content);
    }

    private List<Float> generateMultiModalEmbeddingByHttp(String content) {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("model", model);

            Map<String, Object> input = new HashMap<>();
            input.put("contents", List.of(Map.of("text", content)));
            root.put("input", input);

            if (embeddingDimension > 0) {
                root.put("parameters", Map.of("dimension", embeddingDimension));
            }

            String requestBody = objectMapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = getStringOrDefault(json, "code", "UNKNOWN");
                String message = getStringOrDefault(json, "message", "DashScope 调用失败");
                String requestId = getStringOrDefault(json, "request_id", "");
                throw new RuntimeException(String.format(
                        "DashScope Multimodal Embedding 调用失败(status=%d, code=%s, requestId=%s): %s",
                        response.statusCode(), code, requestId, message));
            }

            JsonNode embeddings = json.path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                throw new RuntimeException("DashScope Multimodal Embedding API 返回空结果");
            }

            JsonNode firstEmbedding = embeddings.get(0).path("embedding");
            if (!firstEmbedding.isArray() || firstEmbedding.isEmpty()) {
                throw new RuntimeException("DashScope Multimodal Embedding API 返回空向量");
            }

            List<Float> result = new ArrayList<>(firstEmbedding.size());
            for (JsonNode node : firstEmbedding) {
                result.add((float) node.asDouble());
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DashScope Multimodal Embedding HTTP 调用失败: " + e.getMessage(), e);
        }

    }

    private static String getStringOrDefault(JsonNode json, String fieldName, String defaultValue) {
        JsonNode node = json.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        return node.asText();
    }

    private static List<Float> getTextEmbeddingFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null
                || result.getOutput().getEmbeddings().isEmpty()) {
            throw new RuntimeException("DashScope API 返回空结果");
        }

        TextEmbeddingOutput output = result.getOutput();
        TextEmbeddingResultItem firstItem = output.getEmbeddings().get(0);
        List<Double> embeddingDoubles = firstItem != null ? firstItem.getEmbedding() : null;

        if (embeddingDoubles == null || embeddingDoubles.isEmpty()) {
            throw new RuntimeException("DashScope API 返回空向量");
        }

        return toFloatList(embeddingDoubles);
    }

    private static List<Float> toFloatList(List<Double> embeddingDoubles) {
        if (embeddingDoubles == null || embeddingDoubles.isEmpty()) {
            throw new RuntimeException("DashScope API 返回空向量");
        }

        List<Float> floatEmbedding = new ArrayList<>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            floatEmbedding.add(value.floatValue());
        }
        return floatEmbedding;
    }

    /**
     * 批量生成向量嵌入
     * 
     * @param contents 文本内容列表
     * @return 向量嵌入列表
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                logger.warn("内容列表为空，无法生成向量");
                return Collections.emptyList();
            }

            logger.info("开始批量生成向量嵌入, 数量: {}", contents.size());
            
            // 确保 API Key 已设置
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                logger.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }

            List<List<Float>> embeddings = new ArrayList<>();
            for (String content : contents) {
                embeddings.add(generateEmbedding(content));
            }

            logger.info("成功批量生成向量嵌入, 数量: {}, 维度: {}", 
                embeddings.size(), 
                embeddings.isEmpty() ? 0 : embeddings.get(0).size());

            return embeddings;

        } catch (Exception e) {
            logger.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     * 
     * @param query 查询文本
     * @return 向量嵌入
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }
}
