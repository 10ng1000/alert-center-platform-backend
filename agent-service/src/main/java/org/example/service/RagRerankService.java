package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 查询后的重排服务。
 *
 * 使用 DashScope rerank 模型对召回结果进行语义重排，失败时自动降级为原排序。
 */
@Service
public class RagRerankService {

    private static final Logger logger = LoggerFactory.getLogger(RagRerankService.class);

    @Value("${spring.ai.dashscope.api-key:${dashscope.api.key:}}")
    private String apiKey;

    @Value("${rag.rerank.enabled:true}")
    private boolean enabled;

    @Value("${rag.rerank.model:gte-rerank}")
    private String model;

    public List<VectorSearchService.SearchResult> rerank(String query,
                                                         List<VectorSearchService.SearchResult> candidates,
                                                         int topK) {
        int safeTopK = Math.max(1, topK);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<VectorSearchService.SearchResult> truncated = truncate(candidates, safeTopK);
        if (!enabled || candidates.size() <= 1 || query == null || query.isBlank()) {
            return truncated;
        }

        if (apiKey == null || apiKey.isBlank() || "your-api-key-here".equals(apiKey)) {
            logger.warn("Rerank 已启用但 API Key 未配置，降级为原排序");
            return truncated;
        }

        try {
            DashScopeApi dashScopeApi = DashScopeApi.builder()
                    .apiKey(apiKey)
                    .build();

            DashScopeRerankModel rerankModel = DashScopeRerankModel.builder()
                    .dashScopeApi(dashScopeApi)
                    .defaultOptions(DashScopeRerankOptions.builder()
                            .model(model)
                            .topN(Math.min(safeTopK, candidates.size()))
                            .build())
                    .build();

            List<Document> documents = toDocuments(candidates);
            RerankRequest request = new RerankRequest(query, documents);
            RerankResponse response = rerankModel.call(request);

            List<DocumentWithScore> ranked = response == null ? null : response.getResults();
            if (ranked == null || ranked.isEmpty()) {
                logger.warn("Rerank 返回空结果，降级为原排序");
                return truncated;
            }

            Map<String, VectorSearchService.SearchResult> sourceMap = new LinkedHashMap<>();
            for (VectorSearchService.SearchResult item : candidates) {
                if (item.getId() != null && !item.getId().isBlank()) {
                    sourceMap.putIfAbsent(item.getId(), copy(item));
                }
            }

            List<VectorSearchService.SearchResult> reranked = new ArrayList<>();
            for (DocumentWithScore item : ranked) {
                if (item == null || item.getOutput() == null) {
                    continue;
                }
                String id = item.getOutput().getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                VectorSearchService.SearchResult base = sourceMap.get(id);
                if (base == null) {
                    continue;
                }
                Double score = item.getScore();
                if (score != null) {
                    base.setScore(score.floatValue());
                }
                reranked.add(base);
                if (reranked.size() >= safeTopK) {
                    break;
                }
            }

            if (reranked.isEmpty()) {
                logger.warn("Rerank 结果不可用，降级为原排序");
                return truncated;
            }

            logger.info("Rerank 完成，候选数: {}, 输出数: {}, 模型: {}",
                    candidates.size(), reranked.size(), model);
            return reranked;
        } catch (Exception e) {
            logger.warn("Rerank 失败，降级为原排序: {}", e.getMessage());
            return truncated;
        }
    }

    private List<Document> toDocuments(List<VectorSearchService.SearchResult> candidates) {
        List<Document> documents = new ArrayList<>(candidates.size());
        for (VectorSearchService.SearchResult item : candidates) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sourceId", item.getId());
            metadata.put("originScore", item.getScore());
            metadata.put("metadata", item.getMetadata());
            documents.add(new Document(item.getId(), item.getContent(), metadata));
        }
        return documents;
    }

    private List<VectorSearchService.SearchResult> truncate(List<VectorSearchService.SearchResult> source, int topK) {
        if (source.size() <= topK) {
            List<VectorSearchService.SearchResult> copied = new ArrayList<>(source.size());
            for (VectorSearchService.SearchResult item : source) {
                copied.add(copy(item));
            }
            return copied;
        }

        List<VectorSearchService.SearchResult> truncated = new ArrayList<>(topK);
        for (int i = 0; i < topK; i++) {
            truncated.add(copy(source.get(i)));
        }
        return truncated;
    }

    private VectorSearchService.SearchResult copy(VectorSearchService.SearchResult source) {
        VectorSearchService.SearchResult copy = new VectorSearchService.SearchResult();
        copy.setId(source.getId());
        copy.setContent(source.getContent());
        copy.setMetadata(source.getMetadata());
        copy.setScore(source.getScore());
        return copy;
    }
}
